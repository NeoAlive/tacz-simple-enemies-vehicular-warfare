package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModParticleTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewFacts;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.RepairLockSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SupportRole;
import com.neoalive.tacz_sewv.skin.VehicleSkinSupport;

/**
 * A mechanical engineer walks to a damaged friendly/empty hull and patches it up. Modelled on
 * {@link MedicGoal}: the repair primitive is SuperbWarfare's public {@code VehicleEntity.heal},
 * called directly (no gun ray) — the engineer's repair tool is cosmetic. On foot only: an engineer
 * riding a hull cannot work on one.
 */
public class RepairGoal extends Goal {

    /** Close enough to work on a hull — a couple of blocks off its edge. */
    private static final double WORK_DISTANCE_SQ = 9.0;
    /**
     * Leave the work band only past this — without hysteresis a hull that drifts across the 3-block
     * edge restarts walk↔idle every tick (visible flicker).
     */
    private static final double LOSE_WORK_DISTANCE_SQ = 25.0;
    /** Goal ticks before scanning for a hull again after finding none. */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a hull after this long — it may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 400;

    /**
     * A tracked/wheeled hull is faster than a walking unit, so without a boost an engineer sent
     * after a moving tank simply never catches it. Fixed id so re-applying (goal restarts on a new
     * target) never stacks a second copy.
     */
    private static final java.util.UUID SPEED_BOOST_ID =
            java.util.UUID.fromString("b6a1f6a0-6e6c-4c9a-9d3a-2f6a2b6d3e10");

    private final AbstractUnit unit;
    private VehicleEntity target;
    private int cooldown;
    private int approachTicks;
    private boolean working;

    public RepairGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        // Holding the tool IS the job. An RU/US engineer entity always is (UnitHolster keeps it
        // in one hand or the other), so this changes nothing for them; it is what lets the goal be
        // installed on every PMC and stay dormant until a player actually hands one a repair tool.
        if (SupportRole.of(this.unit) != SupportRole.ENGINEER) return false;
        // On foot only, and not while fighting.
        if (this.unit.isPassenger() || this.unit.getTarget() != null) return false;
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        this.target = findHull();
        if (this.target == null) {
            this.cooldown = IDLE_RESCAN;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null
                && !(this.target instanceof DroneEntity)
                && this.target.isAlive()
                && !this.target.isWreck()
                && this.target.getHealth() < this.target.getMaxHealth()
                && !this.unit.isPassenger()
                && this.unit.getTarget() == null
                && this.approachTicks < MAX_APPROACH_TICKS
                && VehicleTargeting.isFriendlyOrEmptyHull(this.unit, this.target);
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.cooldown = 0;
        this.working = false;
        // Repair outranks drone sit — unlock so LockGoal releases MOVE and holster restores the tool.
        DroneOperatorGoal.unlockEngineer(this.unit);
        CrewRadio.speakUnit(this.unit, CrewRadio.Line.FIXING);
        this.unit.getNavigation().moveTo(this.target, 1.0);
        applySpeedBoost();
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        clearSpeedBoost();
        // Clean exit (repaired to full, target destroyed, engineer reassigned/engaged) — release the
        // hull instantly rather than waiting out RepairLockSupport's grace period, which exists for
        // the unclean exits (engineer dies/despawns) this stop() is never called for.
        if (this.target != null) RepairLockSupport.clear(this.target);
        this.target = null;
        this.approachTicks = 0;
        this.working = false;
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        this.approachTicks++;
        this.unit.getLookControl().setLookAt(this.target, 30.0F, 30.0F);

        double distSq = this.unit.distanceToSqr(this.target);
        double band = this.working ? LOSE_WORK_DISTANCE_SQ : WORK_DISTANCE_SQ;
        if (distSq > band) {
            this.working = false;
            // Repath only once the last one has run out — same as MedicGoal.
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.target, 1.0);
            }
            return;
        }
        this.working = true;
        this.unit.getNavigation().stop();
        // Holds the hull still while it's genuinely being worked on — refreshed every tick in the
        // work band, independent of the heal cooldown below, so the lock tracks "an engineer is
        // standing here" rather than "a heal just landed".
        RepairLockSupport.refresh(this.target);

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        this.target.heal(SewvConfig.ENGINEER_REPAIR_PER_TREAT.get().floatValue());
        // Sticky faction paint: an engineer repairing a hull paints it in their colours when a
        // filesystem skin exists for that hull+faction (client no-ops if the PNG is missing).
        VehicleSkinSupport.apply(this.target, CrewFacts.factionOfCrew(this.unit));
        CrewFacts.Faction painted = CrewFacts.factionOfCrew(this.unit);
        if (painted == CrewFacts.Faction.PMC && this.unit instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc) {
            com.neoalive.tacz_sewv.skin.PmcVehicleLogoSupport.applyIfPmcCaptured(this.target, pmc.getOwnerUUID());
        }
        showRepairEffects();
        this.cooldown = SewvConfig.ENGINEER_REPAIR_COOLDOWN.get();
    }

    /** Movement boost for the approach — a foot unit must be able to catch a moving tank. */
    private void applySpeedBoost() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(SPEED_BOOST_ID) != null) return;
        double amount = SewvConfig.ENGINEER_REPAIR_SPEED_BOOST.get() - 1.0;
        speed.addTransientModifier(new AttributeModifier(
                SPEED_BOOST_ID, "sewv_repair_speed_boost", amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private void clearSpeedBoost() {
        AttributeInstance speed = this.unit.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED_BOOST_ID);
    }

    /**
     * Sparks and the repair-tool sound at the hull, so a repair reads as work being done rather than
     * a unit standing next to a tank. Uses SuperbWarfare's own repair sound and particles, which is
     * what a player sees when they repair with the tool by hand.
     */
    private void showRepairEffects() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;

        level.playSound(null, this.target, ModSounds.REPAIRING.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);

        // Around the middle of the hull rather than its origin, which for a tank sits at the tracks.
        double x = this.target.getX();
        double y = this.target.getY() + this.target.getBbHeight() * 0.5;
        double z = this.target.getZ();
        double spread = Math.max(0.5, this.target.getBbWidth() * 0.4);
        level.sendParticles(ModParticleTypes.FIRE_STAR.get(), x, y, z, 4, spread, 0.3, spread, 0.02);
        level.sendParticles(ModParticleTypes.RISING_SMOKE.get(), x, y, z, 3, spread, 0.2, spread, 0.01);
    }

    /** Nearest damaged friendly/empty hull in range, or null. Drones are never repair targets. */
    @javax.annotation.Nullable
    public static VehicleEntity findNearestRepairable(AbstractUnit unit) {
        if (SupportRole.of(unit) != SupportRole.ENGINEER) return null;
        if (unit.isPassenger() || unit.getTarget() != null) return null;
        double radius = SewvConfig.ENGINEER_SEARCH_RADIUS.get();
        List<VehicleEntity> nearby = unit.level().getEntitiesOfClass(
                VehicleEntity.class,
                unit.getBoundingBox().inflate(radius),
                v -> !(v instanceof DroneEntity)
                        && !v.isWreck()
                        && v.getHealth() < v.getMaxHealth()
                        && VehicleTargeting.isFriendlyOrEmptyHull(unit, v));
        return nearby.stream()
                .min(Comparator.comparingDouble(unit::distanceToSqr))
                .orElse(null);
    }

    /** Nearest damaged friendly/empty hull in range. */
    private VehicleEntity findHull() {
        return findNearestRepairable(this.unit);
    }
}
