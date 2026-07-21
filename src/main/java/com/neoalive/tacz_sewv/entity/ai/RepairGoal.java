package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
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
        this.cooldown = SewvConfig.ENGINEER_REPAIR_COOLDOWN.get();
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
