package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Lets a unit patch itself and its same-faction squadmates up between fights with a SuperbWarfare
 * medical kit. Runs on any {@link AbstractUnit}: a PMC (added in {@code MixinPmcUnitEntity}) draws
 * real kits from its inventory and can run out, while a dedicated RU/US medic entity has an
 * <em>issued</em> kit — an unlimited supply set at spawn via {@link IIssuedAmmo}, the same channel
 * mortar/TOW crews use — because RU/US units have no inventory to carry one in.
 *
 * <p>SimpleEnemyMod has no healing of any kind, so a unit that survives a firefight stays hurt until
 * it dies in the next one. SuperbWarfare's {@code MedicalKitItem.treat(LivingEntity)} is public and
 * takes any living entity, so the whole feature is "walk over and call it". {@code use()} is not used:
 * it is typed on {@code Player} and unreachable from a mob; {@code treat} is the part that heals.
 */
public class MedicGoal extends Goal {

    /** Close enough to work on someone. Roughly arm's reach plus the width of two units. */
    private static final double TREAT_DISTANCE_SQ = 4.0;
    /** Goal ticks between treatments, so a stack of kits isn't burned in one tick. */
    private static final int TREAT_COOLDOWN = 40;
    /** Goal ticks between pulses of an unlimited supply — faster, since each one heals only a little. */
    private static final int DRIP_TREAT_COOLDOWN = 20;
    /** Wool sounds, picked at random per pulse for tonal variety — bandage-ish and distinctly non-combat. */
    private static final SoundEvent[] TREAT_SOUNDS = {
            SoundEvents.WOOL_PLACE, SoundEvents.WOOL_BREAK, SoundEvents.WOOL_HIT,
            SoundEvents.WOOL_STEP, SoundEvents.WOOL_FALL
    };
    /**
     * Goal ticks before looking for a patient again after finding none. Load-bearing: without it the
     * kit check and the entity search would run on every evaluation of every idle unit on the map.
     */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a patient after this long — it may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 200;

    private final AbstractUnit unit;
    private AbstractUnit patient;
    private int cooldown;
    private int approachTicks;

    public MedicGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!SewvConfig.MEDIC_ENABLED.get()) return false;
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        // A crew member is busy working the vehicle, and a unit in contact has better things to do
        // than first aid. "Holding a target" is this codebase's only notion of being in combat.
        if (this.unit.isPassenger() || this.unit.getTarget() != null) return false;

        // Cheapest first: rule out every unit with no kit source before touching the world.
        if (!hasKit()) {
            this.cooldown = IDLE_RESCAN;
            return false;
        }

        this.patient = findPatient();
        if (this.patient == null) {
            this.cooldown = IDLE_RESCAN;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.patient != null
                && this.patient.isAlive()
                && this.patient.getHealth() < this.patient.getMaxHealth()
                && this.unit.getTarget() == null
                && this.approachTicks < MAX_APPROACH_TICKS
                && hasKit();
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        if (this.patient != this.unit) {
            this.unit.getNavigation().moveTo(this.patient, 1.0);
        }
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        this.patient = null;
        this.approachTicks = 0;
    }

    @Override
    public void tick() {
        if (this.patient == null) return;
        this.approachTicks++;

        if (this.patient != this.unit) {
            this.unit.getLookControl().setLookAt(this.patient, 30.0F, 30.0F);
            if (this.unit.distanceToSqr(this.patient) > TREAT_DISTANCE_SQ) {
                // Repath only once the last one has run out — an unreachable patient reports "done"
                // every tick and would otherwise force a full path search every tick until timeout.
                if (this.unit.getNavigation().isDone()) {
                    this.unit.getNavigation().moveTo(this.patient, 1.0);
                }
                return;
            }
            this.unit.getNavigation().stop();
        }

        treat();
    }

    /** Spend one treatment on the patient — from the unlimited supply if any, else the inventory. */
    private void treat() {
        // Unlimited supply (a dedicated medic): a modest amount per pulse, repeated, so it visibly
        // works on someone over a few seconds instead of topping them up in a single tick.
        if (issuedKit() != null) {
            this.patient.heal(SewvConfig.MEDIC_HEAL_PER_TREAT.get().floatValue());
            playTreatSound();
            this.cooldown = DRIP_TREAT_COOLDOWN;
            this.patient = null; // re-evaluated next canUse; usually the same patient, still hurt
            return;
        }

        int slot = findKitSlot();
        if (slot < 0) return;
        IItemHandler inventory = inventory();
        if (inventory == null) return;

        // Extract rather than shrink in place: going through the handler's extract path keeps the
        // unit's openable inventory in step with what was used.
        ItemStack kit = inventory.extractItem(slot, 1, false);
        if (!(kit.getItem() instanceof MedicalKitItem medkit)) return;

        // A consumed kit does its full job — SuperbWarfare's heal + Regeneration II.
        medkit.treat(this.patient);
        playTreatSound();
        this.cooldown = TREAT_COOLDOWN;
        this.patient = null;
    }

    private void playTreatSound() {
        SoundEvent sound = TREAT_SOUNDS[this.unit.getRandom().nextInt(TREAT_SOUNDS.length)];
        // Entity-bound overload so the clip follows the patient rather than being left behind.
        this.unit.level().playSound(null, this.patient, sound, SoundSource.NEUTRAL,
                1.0F, 0.9F + this.unit.getRandom().nextFloat() * 0.3F);
    }

    /**
     * Worst-off same-faction unit in range (this unit first if it is hurt). Self-first is the cheap
     * ordering as well as the sensible one — treating itself needs no pathfinding.
     */
    private AbstractUnit findPatient() {
        if (this.unit.getHealth() < this.unit.getMaxHealth()) return this.unit;

        // A dedicated medic is neutral — nothing targets it — so it can safely work on someone who is
        // still in contact. That matters far more than it sounds: SEM holds a target for 600 ticks
        // after contact, so a wounded unit is almost ALWAYS holding one, and requiring otherwise
        // (which is right for an ordinary PMC that would be walking into a firefight) excluded
        // essentially every realistic patient and made the medic look broken.
        // A PMC holding a kit is neutral by the same rule (SupportRole vetoes its targets in
        // MixinAbstractUnit), so it treats patients still in contact exactly as a medic entity does.
        boolean neutral = VehicleTargeting.isMedic(this.unit) || SupportRole.of(this.unit) == SupportRole.MEDIC;

        double radius = SewvConfig.MEDIC_SEARCH_RADIUS.get();
        List<AbstractUnit> nearby = this.unit.level().getEntitiesOfClass(
                AbstractUnit.class,
                this.unit.getBoundingBox().inflate(radius),
                other -> other != this.unit
                        && other.isAlive()
                        && other.getHealth() < other.getMaxHealth()
                        // Same faction only — a medic patches up its own side.
                        && VehicleTargeting.isFriendly(this.unit, other)
                        && (neutral || other.getTarget() == null)
                        && !other.isPassenger());

        return nearby.stream()
                .min(Comparator.comparingDouble(other -> other.getHealth() / other.getMaxHealth()))
                .orElse(null);
    }

    private boolean hasKit() {
        return issuedKit() != null || findKitSlot() >= 0;
    }

    /**
     * The unit's unlimited medical-kit supply, or null if it has none (a PMC spends real kits instead).
     *
     * <p>A dedicated medic's own held kit counts, which is what makes it work whatever spawned it —
     * a spawn egg or structure NBT never goes through {@code SupportSpawner}, so relying on the
     * issued-NBT tag alone left egg-spawned medics silently unable to treat anyone.
     */
    private MedicalKitItem issuedKit() {
        if (VehicleTargeting.isMedic(this.unit)
                && this.unit.getMainHandItem().getItem() instanceof MedicalKitItem handKit) {
            return handKit;
        }
        if (this.unit instanceof IIssuedAmmo issued
                && issued.sewv$getIssuedAmmo() instanceof MedicalKitItem kit) {
            return kit;
        }
        return null;
    }

    /** Index of the first medical kit in the unit's inventory, or -1 (RU/US units have none). */
    private int findKitSlot() {
        IItemHandler inventory = inventory();
        if (inventory == null) return -1;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).getItem() instanceof MedicalKitItem) return slot;
        }
        return -1;
    }

    private IItemHandler inventory() {
        return this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
    }
}
