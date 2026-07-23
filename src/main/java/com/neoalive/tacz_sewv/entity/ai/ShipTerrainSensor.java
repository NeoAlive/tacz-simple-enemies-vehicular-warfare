package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.List;

/**
 * Shoreline sensing for {@link DriveShipGoal}. A grounded ship is a dead ship — SBW gates both
 * thrust and yaw change behind being in fluid, and scales yaw change by current speed, so a hull
 * that beaches has no input left that does anything. Every probe here exists to make sure that
 * never happens.
 *
 * <p>Stricter than the ground sensor in three ways: samples at half-block steps, sweeps the hull's
 * full BEAM (port and starboard offsets, not just the centreline) so a bow that clears doesn't
 * drag a quarter onto a sandbar, and scales reach with current speed since a boat carries way.
 */
final class ShipTerrainSensor extends TerrainSensor {

    private static final double SAMPLE_STEP = 1.0;
    /** Extra reach per block/tick of current speed. */
    private static final double SPEED_LOOKAHEAD = 24.0;
    private static final double MIN_REACH = 6.0;

    ShipTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    boolean enabled() {
        return SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    }

    /** Base look-ahead plus a stopping-distance allowance for the speed actually being carried. */
    double lookahead() {
        double speed = this.vehicle == null ? 0.0 : this.vehicle.getDeltaMovement().horizontalDistance();
        return Math.max(MIN_REACH, SewvConfig.VEHICLE_LOOKAHEAD_DISTANCE.get() + speed * SPEED_LOOKAHEAD);
    }

    Vec3 chooseClearBearing(Vec3 desired) {
        return chooseClearBearing(desired, lookahead());
    }

    @Override
    boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double half = halfWidth();
        // Probe the beam, not a line: a ship is wide and turns by swinging its stern out.
        double beam = half + 0.5;
        int y = this.vehicle.getBlockY();
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        List<AABB> hulls = obstacles(distance);

        for (double d = half + 0.5; d <= half + distance; d += SAMPLE_STEP) {
            double cx = this.vehicle.getX() + dir.x * d;
            double cz = this.vehicle.getZ() + dir.z * d;
            for (double lateral = -beam; lateral <= beam; lateral += beam) {
                double x = cx + side.x * lateral;
                double z = cz + side.z * lateral;
                if (isBlockedByHull(hulls, x, z)) return false;
                if (!WaterSupport.floatableAt(level, Mth.floor(x), y, Mth.floor(z))) return false;
            }
        }
        return true;
    }

    @Override
    protected List<AABB> buildObstacles(double reach) {
        double half = halfWidth();
        AABB search = this.vehicle.getBoundingBox().inflate(reach + half + 1.0, 2.0, reach + half + 1.0);
        return this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                        v -> v != this.vehicle && (v.isWreck() || isAllied(v))).stream()
                .map(v -> v.getBoundingBox().inflate(half, 0.0, half))
                .toList();
    }

    private boolean isAllied(VehicleEntity other) {
        return other.getFirstPassenger() instanceof AbstractUnit driver
                && VehicleTargeting.isSameFaction(this.unit, driver);
    }

    private static boolean isBlockedByHull(List<AABB> obstacles, double x, double z) {
        for (AABB box : obstacles) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) return true;
        }
        return false;
    }
}
