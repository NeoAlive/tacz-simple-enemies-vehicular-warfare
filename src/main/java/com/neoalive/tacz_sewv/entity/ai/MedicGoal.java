package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.item.misc.MedicalKitItem;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Lets a PMC unit patch itself and its squadmates up between fights with a SuperbWarfare
 * medical kit.
 *
 * <p>SimpleEnemyMod has no healing of any kind — no medic role, no regeneration, not one call to
 * {@code heal()} anywhere — so a unit that survives a firefight stays hurt until it dies in the
 * next one. SuperbWarfare's {@code MedicalKitItem.treat(LivingEntity)} is public and takes any
 * living entity, so the whole feature is "walk over and call it".
 *
 * <p><b>PMC only</b>, and that is a supply fact rather than a doctrine: kits are player-supplied
 * out of the unit's inventory, and a PMC is the one unit type SEM gives an inventory to. RU/US
 * have no {@code ITEM_HANDLER} at all, so there is nowhere to put a kit and nothing to read.
 *
 * <p>Note {@code MedicalKitItem.use()} is deliberately not used: it is typed on {@code Player}
 * and unreachable from a mob. {@code treat} is the part that actually heals, and it is the same
 * call {@code finishUsingItem} makes.
 */
public class MedicGoal extends Goal {

    /** Close enough to work on someone. Roughly arm's reach plus the width of two units. */
    private static final double TREAT_DISTANCE_SQ = 4.0;
    /** Goal ticks between treatments, so a stack of kits isn't burned in one tick. */
    private static final int TREAT_COOLDOWN = 40;
    /**
     * Goal ticks before looking for a patient again after finding none.
     *
     * <p>Load-bearing, not politeness: without it the inventory scan and the entity search below
     * would run on <b>every</b> evaluation of every idle PMC on the map — and the overwhelmingly
     * common case is a unit carrying no kit at all, since kits are player-supplied. Nobody bleeds
     * out in the two seconds this costs.
     */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a patient after this long — it may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 200;

    private final PmcUnitEntity unit;
    private PmcUnitEntity patient;
    private int cooldown;
    private int approachTicks;

    public MedicGoal(PmcUnitEntity unit) {
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
        // A crew member is busy working the vehicle, and a unit in contact has better things to
        // do than first aid. "Holding a target" is this codebase's only notion of being in
        // combat — the same signal DriveVehicleGoal's fight branch runs off.
        if (this.unit.isPassenger() || this.unit.getTarget() != null) return false;

        // Ordered cheapest-first: the inventory read rules out every unit the player never
        // handed a kit to, which is nearly all of them, before anything touches the world.
        if (findKitSlot() < 0) {
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
                && findKitSlot() >= 0;
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
                // Repath only once the last one has run out, the way BoardVehicleGoal does —
                // an unreachable patient reports "done" every tick and would otherwise force a
                // full path search every tick until the timeout.
                if (this.unit.getNavigation().isDone()) {
                    this.unit.getNavigation().moveTo(this.patient, 1.0);
                }
                return;
            }
            this.unit.getNavigation().stop();
        }

        treat();
    }

    /** Spend one kit on the patient. */
    private void treat() {
        int slot = findKitSlot();
        if (slot < 0) return;

        IItemHandler inventory = inventory();
        if (inventory == null) return;

        // Extract rather than shrink in place: the handler is SEM's own, and going through its
        // extract path is what keeps the unit's openable inventory in step with what was used.
        ItemStack kit = inventory.extractItem(slot, 1, false);
        if (!(kit.getItem() instanceof MedicalKitItem medkit)) return;

        medkit.treat(this.patient); // heal + Regeneration II, amounts from SuperbWarfare's config
        this.cooldown = TREAT_COOLDOWN;
        this.patient = null; // re-evaluated next canUse; one kit may not have been enough
    }

    /**
     * Who needs treating: this unit first if it is hurt, otherwise the worst-off squadmate in
     * range.
     *
     * <p>Self-first is the cheap ordering as well as the sensible one — a medic that bleeds out
     * walking to someone else helps nobody, and treating itself needs no pathfinding at all.
     */
    private PmcUnitEntity findPatient() {
        if (this.unit.getHealth() < this.unit.getMaxHealth()) return this.unit;

        double radius = SewvConfig.MEDIC_SEARCH_RADIUS.get();
        List<PmcUnitEntity> nearby = this.unit.level().getEntitiesOfClass(
                PmcUnitEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                other -> other != this.unit
                        && other.isAlive()
                        && other.getHealth() < other.getMaxHealth()
                        // Don't walk into a firefight to bandage someone who is still in it.
                        && other.getTarget() == null
                        && !other.isPassenger());

        return nearby.stream()
                .min(Comparator.comparingDouble(other -> other.getHealth() / other.getMaxHealth()))
                .orElse(null);
    }

    /** Index of the first medical kit in the unit's inventory, or -1. */
    private int findKitSlot() {
        IItemHandler inventory = inventory();
        if (inventory == null) return -1;
        // Equipment slots included, exactly as MortarSupport.takeShell scans: only kits match,
        // so a rifle in the main hand is never at risk and a kit stashed anywhere still counts.
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).getItem() instanceof MedicalKitItem) return slot;
        }
        return -1;
    }

    private IItemHandler inventory() {
        return this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
    }
}
