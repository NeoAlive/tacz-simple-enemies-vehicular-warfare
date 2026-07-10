package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

/**
 * While mounted in a vehicle, drops targets that are too close for the
 * vehicle's weapons to physically engage (e.g. a mob on top of the turret).
 * Prevents the tank locking up trying to aim at an unhittable hugger.
 */
public class VehicleMinRangeGoal extends Goal {

    // Minimum engagement distance from the VEHICLE — inside this, the vehicle can't aim, so drop the target.
    private static final double MIN_ENGAGE_DISTANCE_SQ = 25.0; // 5 blocks

    private final PmcUnitEntity unit;
    private VehicleEntity vehicle;

    public VehicleMinRangeGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // just a monitor, claims no flags
    }

    @Override
    public boolean canUse() {
        // Only relevant while mounted with a target
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        this.vehicle = v;
        return this.unit.getTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null || this.vehicle == null) return;

        double distSq = this.vehicle.distanceToSqr(target);
        if (distSq < MIN_ENGAGE_DISTANCE_SQ) {
            // Too close for the vehicle to aim — drop it so targeting picks something else
            this.unit.setTarget(null);
        }
    }
}