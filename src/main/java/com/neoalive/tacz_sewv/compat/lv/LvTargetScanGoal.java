package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;

/**
 * Gives an LV crew a vehicle's eyes. SEM's own scans reach only {@code FOLLOW_RANGE} and ±4 blocks
 * vertically, which is infantry reach, and {@code VehicleTargetScanGoal} is typed to SBW hulls —
 * so without this an LV gunner would only ever shoot what wandered into rifle range.
 *
 * <p>Deliberately the plain version: the same cylinder ({@code vehicleTargetScanRadius} /
 * {@code ...Height}), the same hostility filter every scan in this mod uses, line of sight from the
 * crewman, nearest first. It only fills an empty target slot and never retargets.
 */
final class LvTargetScanGoal extends Goal {

    private final AbstractUnit unit;
    private int cooldown;
    private LivingEntity pending;

    LvTargetScanGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !LvCompat.isLvCrew(this.unit) || !LvCompat.aiEnabled()) return false;
        if (LvOrders.liveTarget(this.unit) != null) return false;
        if (!VehicleTargeting.ordersAllowAutoTargets(this.unit)) return false;
        if (this.cooldown-- > 0) return false;
        this.cooldown = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();
        this.pending = nearestHostile();
        return this.pending != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false; // set once in start(); the target slot keeps it
    }

    @Override
    public void start() {
        this.unit.setTarget(this.pending);
        this.pending = null;
    }

    @Nullable
    private LivingEntity nearestHostile() {
        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double height = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get();
        AABB box = this.unit.getBoundingBox().inflate(radius, height, radius);
        double r2 = radius * radius;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity e : this.unit.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != this.unit && e.isAlive() && VehicleTargeting.isValidHostileTarget(this.unit, e))) {
            double dx = e.getX() - this.unit.getX();
            double dz = e.getZ() - this.unit.getZ();
            double d = dx * dx + dz * dz;
            if (d > r2 || d >= bestSq) continue;
            if (!this.unit.getSensing().hasLineOfSight(e)) continue;
            best = e;
            bestSq = d;
        }
        return best;
    }
}
