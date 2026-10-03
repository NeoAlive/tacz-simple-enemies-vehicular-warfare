package com.neoalive.tacz_sewv.heli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;

/**
 * Builds the barrier's {@link ObstacleSet} for one hull, once per tick (plan sections 4.9 and
 * 4.9a).
 *
 * <ul>
 * <li><b>Airframes:</b> every helicopter and plane hull except this one: any faction, any pilot
 *     or none, wrecks too. Each is a box with its own velocity. No faction or pilot filter: a
 *     collision with an enemy or a player helicopter is still a collision. Wingmen at matched
 *     velocity are not pushed apart, because the band is sized from the closing speed.</li>
 * <li><b>Terrain:</b> the first solid block along a short fan of rays from mid-hull: along the
 *     velocity (and +-20, +-45 degrees) when moving, the eight compass points when hovering. Rays
 *     reach the band this speed needs plus a margin, never past the detect radius, and stop at
 *     unloaded chunks (never sync-load; unknown is not a wall). Bands already being tracked keep
 *     their block in the set so a ray that now misses cannot drop a live band.</li>
 * <li><b>Ground:</b> the {@code MOTION_BLOCKING_NO_LEAVES} surface under the hull, unless the
 *     active procedure is putting the hull on the ground.</li>
 * </ul>
 * Entity keys are offset into a range {@code BlockPos.asLong()} cannot reach inside the world
 * border, so the two kinds never collide.
 */
public final class WorldObstacleField {

    /** Entity keys: {@code ENTITY_KEY_BASE + id}. */
    public static final long ENTITY_KEY_BASE = Long.MIN_VALUE + 1;
    private static final double[] MOVING_FAN_DEG = {0, 20, -20, 45, -45};
    private static final double MAX_ELEVATION = Math.toRadians(30);

    private WorldObstacleField() {}

    public static ObstacleSet build(VehicleEntity hull, AvoidForce barrier, Vector3dc p, Vector3dc v,
                                    double detect, boolean groundBarrier) {
        Level level = hull.level();
        double halfWidth = hull.getBbWidth() / 2.0, height = hull.getBbHeight();
        double speed = v.length();
        double reach = Math.min(detect, barrier.band(speed, Double.POSITIVE_INFINITY) + 4.0);
        List<ObstacleSet.Box> boxes = new ArrayList<>();
        Set<Long> keys = new HashSet<>();

        AABB search = hull.getBoundingBox().inflate(reach + halfWidth);
        for (VehicleEntity o : level.getEntitiesOfClass(VehicleEntity.class, search, o -> o != hull && isAirframe(o))) {
            AABB b = o.getBoundingBox();
            Vec3 dm = o.getDeltaMovement();
            long key = ENTITY_KEY_BASE + o.getId();
            keys.add(key);
            boxes.add(new ObstacleSet.Box(key, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                    dm.x * 20.0, dm.y * 20.0, dm.z * 20.0));
        }

        double ox = p.x(), oy = p.y() + height / 2.0, oz = p.z();
        double vh = Math.sqrt(v.x() * v.x() + v.z() * v.z());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (vh > 1.0) {
            double yaw = StrictMath.atan2(v.z(), v.x());
            double elev = Math.max(-MAX_ELEVATION, Math.min(MAX_ELEVATION, StrictMath.atan2(v.y(), vh)));
            if (!groundBarrier) elev = Math.max(0.0, elev);
            for (double off : MOVING_FAN_DEG) {
                double a = yaw + Math.toRadians(off);
                ray(level, pos, ox, oy, oz, StrictMath.cos(a) * StrictMath.cos(elev), StrictMath.sin(elev),
                        StrictMath.sin(a) * StrictMath.cos(elev), halfWidth, reach, boxes, keys);
            }
        } else {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                ray(level, pos, ox, oy, oz, StrictMath.cos(a), 0.0, StrictMath.sin(a), halfWidth, reach, boxes, keys);
            }
        }
        for (long key : barrier.trackedKeys()) {
            if (key == ObstacleSet.GROUND || keys.contains(key) || isEntityKey(key)) continue;
            pos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            if (solid(level, pos)) addBlock(pos, boxes, keys);
        }

        double groundY = Double.NaN;
        if (groundBarrier) {
            int bx = hull.getBlockX(), bz = hull.getBlockZ();
            if (level.hasChunk(bx >> 4, bz >> 4)) groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        }
        return new ObstacleSet(boxes, groundY);
    }

    /** Ground surface under the hub for ground effect; +infinity when the column is not loaded. */
    public static double groundUnder(VehicleEntity hull) {
        int bx = hull.getBlockX(), bz = hull.getBlockZ();
        Level level = hull.level();
        if (!level.hasChunk(bx >> 4, bz >> 4)) return Double.POSITIVE_INFINITY;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    private static boolean isEntityKey(long key) {
        return key >= ENTITY_KEY_BASE && key < ENTITY_KEY_BASE + (1L << 32);
    }

    private static boolean isAirframe(VehicleEntity o) {
        EngineType t = HullFacts.engineType(o);
        return t == EngineType.HELICOPTER || t == EngineType.AIRCRAFT;
    }

    private static void ray(Level level, BlockPos.MutableBlockPos pos, double ox, double oy, double oz,
                            double dx, double dy, double dz, double start, double reach,
                            List<ObstacleSet.Box> boxes, Set<Long> keys) {
        for (double d = start; d <= start + reach; d += 1.0) {
            pos.set(Math.floor(ox + dx * d), Math.floor(oy + dy * d), Math.floor(oz + dz * d));
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
            if (solid(level, pos)) {
                addBlock(pos, boxes, keys);
                return;
            }
        }
    }

    private static boolean solid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static void addBlock(BlockPos pos, List<ObstacleSet.Box> boxes, Set<Long> keys) {
        long key = pos.asLong();
        if (!keys.add(key)) return;
        boxes.add(new ObstacleSet.Box(key, pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0, 0, 0, 0));
    }
}
