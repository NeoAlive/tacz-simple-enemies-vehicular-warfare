package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * While mounted in a vehicle, drops targets that are too close for the
 * vehicle's weapons to physically engage (e.g. a mob on top of the turret).
 * Prevents the tank locking up trying to aim at an unhittable hugger.
 */
public class VehicleMinRangeGoal extends Goal {

    // Minimum HORIZONTAL engagement distance from the VEHICLE, inside this the
    // vehicle can't aim so drop the target. Horizontal (cylinder-style, matching
    // VehicleTargetScanGoal's scan shape) so a mob perched on the turret is inside
    // the dead zone no matter how far above the hull it sits. Shared with the scan
    // goal so acquisition never picks what this goal is about to drop.
    public static final double MIN_ENGAGE_DISTANCE_SQ = 25.0; // 5 blocks

    private final AbstractUnit unit;
    private VehicleEntity vehicle;

    public VehicleMinRangeGoal(AbstractUnit unit) {
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

        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < MIN_ENGAGE_DISTANCE_SQ) {
            // Too close for the vehicle to aim, drop it so targeting picks something else
            this.unit.setTarget(null);
        }
    }
}