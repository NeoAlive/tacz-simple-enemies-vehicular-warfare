package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * While mounted in a vehicle, drops targets that are too close for the
 * vehicle's weapons to physically engage (e.g. a mob on top of the turret).
 * Prevents the tank locking up trying to aim at an unhittable hugger.
 *
 * <p>Aircraft are exempt: overflying inside this band is what an attack run is.
 * A helicopter is exempt while its run is active, a fixed-wing hull always, since
 * it has no phase in which passing over its target is avoidable.
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
            // Firing-run overfly is intentional — keep the lock for the pass.
            if (HullFacts.isHelicopterHull(this.vehicle)
                    && DriveHelicopterGoal.inFiringRun(this.vehicle)) {
                return;
            }
            // A fixed-wing hull passes over everything it attacks — it cannot stop, and every run
            // ends inside this radius by construction. Dropping the lock there aborted the pass at
            // the exact moment of the shot and sent the aircraft round to re-acquire, which is the
            // "flies over the target and does nothing" loop. There is no equivalent of the
            // helicopter's firing-run test to make here: for a plane the whole engagement is one.
            if (HullFacts.isPlaneHull(this.vehicle)) return;
            // Too close for the vehicle to aim, drop it so targeting picks something else
            this.unit.setTarget(null);
        }
    }
}
