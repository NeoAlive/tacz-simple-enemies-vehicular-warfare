package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.config.SewvConfig;
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

    /** Spend one treatment on the patient — from the issued supply if any, else the inventory. */
    private void treat() {
        // Issued kit (RU/US medic): unlimited, conjured rather than consumed. Same pattern as
        // MortarSupport.takeShell checking issued ammo before scanning the inventory.
        MedicalKitItem issued = issuedKit();
        if (issued != null) {
            issued.treat(this.patient); // heal + Regeneration II, amounts from SuperbWarfare's config
            this.cooldown = TREAT_COOLDOWN;
            this.patient = null; // re-evaluated next canUse; one kit may not have been enough
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

        medkit.treat(this.patient);
        this.cooldown = TREAT_COOLDOWN;
        this.patient = null;
    }

    /**
     * Worst-off same-faction unit in range (this unit first if it is hurt). Self-first is the cheap
     * ordering as well as the sensible one — treating itself needs no pathfinding.
     */
    private AbstractUnit findPatient() {
        if (this.unit.getHealth() < this.unit.getMaxHealth()) return this.unit;

        double radius = SewvConfig.MEDIC_SEARCH_RADIUS.get();
        List<AbstractUnit> nearby = this.unit.level().getEntitiesOfClass(
                AbstractUnit.class,
                this.unit.getBoundingBox().inflate(radius),
                other -> other != this.unit
                        && other.isAlive()
                        && other.getHealth() < other.getMaxHealth()
                        // Same faction only — a medic patches up its own side.
                        && VehicleTargeting.isFriendly(this.unit, other)
                        // Don't walk into a firefight to bandage someone who is still in it.
                        && other.getTarget() == null
                        && !other.isPassenger());

        return nearby.stream()
                .min(Comparator.comparingDouble(other -> other.getHealth() / other.getMaxHealth()))
                .orElse(null);
    }

    private boolean hasKit() {
        return issuedKit() != null || findKitSlot() >= 0;
    }

    /** The medic's issued (unlimited) medical kit, or null if it has none. */
    private MedicalKitItem issuedKit() {
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
