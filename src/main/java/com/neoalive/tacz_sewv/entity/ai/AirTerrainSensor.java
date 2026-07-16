package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.List;

/**
 * Terrain sensing for {@link DriveHelicopterGoal}: what an airframe must not fly into.
 *
 * <p>Probes actual blocks rather than the heightmap the collective follows, so overhangs,
 * arches and interiors are judged correctly — and allied airframes, which no block probe
 * can see and which is the whole reason helicopters used to fly through each other.
 */
final class AirTerrainSensor extends TerrainSensor {

    /**
     * Separation bubble grown around every allied airframe on top of our own half-width.
     * Point-sampling against a bare bounding box is fine for a wall that isn't going
     * anywhere; two aircraft close at their COMBINED speed, so the bubble is what makes a
     * bearing read as blocked while there is still room to turn out of it, rather than at
     * the moment of contact.
     */
    private static final double CLEARANCE = 4.0;

    AirTerrainSensor(AbstractUnit unit) {
        super(unit);
    }

    /**
     * True when flying {@code distance} blocks along {@code dir} keeps the hull slab — its
     * height, with a block of margin above and below — free of collidable blocks and of
     * allied airframes.
     */
    @Override
    boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double startX = this.vehicle.getX();
        double startZ = this.vehicle.getZ();
        int yBottom = Mth.floor(this.vehicle.getY()) - 1;
        int yTop = Mth.floor(this.vehicle.getY() + this.vehicle.getBbHeight()) + 1;
        double half = halfWidth();
        List<AABB> traffic = obstacles(distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (double d = half + 1.0; d <= half + distance; d += 1.0) {
            double sampleX = startX + dir.x * d;
            double sampleZ = startZ + dir.z * d;
            if (isBlockedByAircraft(traffic, sampleX, sampleZ, yBottom, yTop)) return false;

            int px = Mth.floor(sampleX);
            int pz = Mth.floor(sampleZ);
            for (int y = yBottom; y <= yTop; y++) {
                if (!level.getBlockState(pos.set(px, y, pz)).getCollisionShape(level, pos).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Allied airframes and wrecks (still a hull hanging in the air on its way down); enemy
     * hulls are NOT, since the combat profile deliberately flies AT its target.
     *
     * <p>Restricted to helicopters on top of the ground sensor's rule: a ground hull is never
     * a flight hazard at cruise, and while the slab test would reject it anyway, keeping it
     * out of the scan means the common case — nothing but tanks below — pays nothing.
     */
    @Override
    protected List<AABB> buildObstacles(double reach) {
        double half = halfWidth();
        double horizontal = reach + half + CLEARANCE + 1.0;
        AABB search = this.vehicle.getBoundingBox()
                .inflate(horizontal, this.vehicle.getBbHeight() + CLEARANCE + 1.0, horizontal);
        return this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                        v -> v != this.vehicle && isObstacle(v)).stream()
                .map(v -> v.getBoundingBox().inflate(half + CLEARANCE, CLEARANCE, half + CLEARANCE))
                .toList();
    }

    private boolean isObstacle(VehicleEntity other) {
        if (!(other.getEngineInfo() instanceof EngineInfo.Helicopter)) return false;
        if (other.isWreck()) return true;
        return other.getFirstPassenger() instanceof AbstractUnit pilot
                && VehicleTargeting.isSameFaction(this.unit, pilot);
    }

    /**
     * Vertical containment is what keeps this from being the ground sensor's flat test: an
     * allied airframe holding station 40 blocks below shares our X/Z all day and must not
     * veto the bearing. Only traffic overlapping the slab we are about to fly through counts.
     */
    private static boolean isBlockedByAircraft(List<AABB> obstacles, double x, double z,
                                               double slabBottom, double slabTop) {
        for (AABB box : obstacles) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ
                    && slabTop >= box.minY && slabBottom <= box.maxY) {
                return true;
            }
        }
        return false;
    }
}
