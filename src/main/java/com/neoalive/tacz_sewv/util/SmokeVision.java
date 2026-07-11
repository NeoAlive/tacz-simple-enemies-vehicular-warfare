package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Line-of-fire smoke test. SuperbWarfare's {@link SmokeDecoyEntity} is a bare
 * particle emitter with no collision, so block-based line-of-sight never sees it.
 * This treats each live decoy as a soft sphere and reports whether any of them
 * straddles the segment between a shooter's muzzle and its target — i.e. whether
 * the shot would pass through a smoke screen.
 */
public final class SmokeVision {

    private SmokeVision() {
    }

    /**
     * True if any {@link SmokeDecoyEntity} lies within {@code radius} of the
     * {@code from}→{@code to} segment. Only the decoys inside the segment's
     * bounding box (inflated by {@code radius}) are examined, so the world query
     * stays bounded to the shot corridor.
     */
    public static boolean lineBlockedBySmoke(Level level, Entity shooter, Vec3 from, Vec3 to, double radius) {
        AABB corridor = new AABB(from, to).inflate(radius);
        List<Entity> decoys = level.getEntities(shooter, corridor, e -> e instanceof SmokeDecoyEntity);
        if (decoys.isEmpty()) return false;

        double radiusSq = radius * radius;
        for (Entity decoy : decoys) {
            Vec3 c = decoy.getBoundingBox().getCenter(); // sit the test point inside the cloud
            if (distancePointToSegmentSq(c, from, to) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    // Squared distance from point p to the segment [a, b].
    private static double distancePointToSegmentSq(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double abz = b.z - a.z;
        double lenSq = abx * abx + aby * aby + abz * abz;

        double apx = p.x - a.x;
        double apy = p.y - a.y;
        double apz = p.z - a.z;

        // Degenerate segment (muzzle == target): fall back to point distance.
        double t = lenSq < 1.0E-6 ? 0.0 : (apx * abx + apy * aby + apz * abz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double dx = apx - abx * t;
        double dy = apy - aby * t;
        double dz = apz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }
}
