package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModParticleTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * A mechanical engineer walks to a damaged friendly/empty hull and patches it up. Modelled on
 * {@link MedicGoal}: the repair primitive is SuperbWarfare's public {@code VehicleEntity.heal},
 * called directly (no gun ray) — the engineer's repair tool is cosmetic. On foot only: an engineer
 * riding a hull cannot work on one.
 */
public class RepairGoal extends Goal {

    /** Close enough to work on a hull — a couple of blocks off its edge. */
    private static final double WORK_DISTANCE_SQ = 9.0;
    /** Goal ticks before scanning for a hull again after finding none. */
    private static final int IDLE_RESCAN = 40;
    /** Give up walking to a hull after this long — it may be somewhere unreachable. */
    private static final int MAX_APPROACH_TICKS = 400;

    private final AbstractUnit unit;
    private VehicleEntity target;
    private int cooldown;
    private int approachTicks;

    public RepairGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        // Holding the tool IS the job. An RU/US engineer entity always is (EngineerLoadout keeps it
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
        this.unit.getNavigation().moveTo(this.target, 1.0);
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
        this.target = null;
        this.approachTicks = 0;
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        this.approachTicks++;
        this.unit.getLookControl().setLookAt(this.target, 30.0F, 30.0F);

        if (this.unit.distanceToSqr(this.target) > WORK_DISTANCE_SQ) {
            // Repath only once the last one has run out — same as MedicGoal.
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.target, 1.0);
            }
            return;
        }
        this.unit.getNavigation().stop();

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }
        this.target.heal(SewvConfig.ENGINEER_REPAIR_PER_TREAT.get().floatValue());
        showRepairEffects();
        this.cooldown = SewvConfig.ENGINEER_REPAIR_COOLDOWN.get();
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

    /** Nearest damaged friendly/empty hull in range. */
    private VehicleEntity findHull() {
        double radius = SewvConfig.ENGINEER_SEARCH_RADIUS.get();
        List<VehicleEntity> nearby = this.unit.level().getEntitiesOfClass(
                VehicleEntity.class,
                this.unit.getBoundingBox().inflate(radius),
                v -> !v.isWreck()
                        && v.getHealth() < v.getMaxHealth()
                        && VehicleTargeting.isFriendlyOrEmptyHull(this.unit, v));
        return nearby.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }
}
