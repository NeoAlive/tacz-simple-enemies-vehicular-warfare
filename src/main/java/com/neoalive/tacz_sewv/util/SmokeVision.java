package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Line-of-fire smoke test. SuperbWarfare's {@link SmokeDecoyEntity} is a bare
 * particle emitter with no collision, so block-based line-of-sight never sees it.
 * This treats each live decoy as a cloud {@code radius} blocks around its box and
 * reports whether any of them straddles the segment between a shooter's muzzle and
 * its target — i.e. whether the shot would pass through a smoke screen.
 */
public final class SmokeVision {

    private SmokeVision() {
    }

    /**
     * True if any {@link SmokeDecoyEntity}'s cloud straddles the {@code from}→{@code to}
     * segment. Only the decoys inside the segment's bounding box (inflated by
     * {@code radius}) are examined, so the world query stays bounded to the shot corridor.
     */
    public static boolean lineBlockedBySmoke(Level level, Entity shooter, Vec3 from, Vec3 to, double radius) {
        AABB corridor = new AABB(from, to).inflate(radius);
        for (Entity decoy : level.getEntities(shooter, corridor, e -> e instanceof SmokeDecoyEntity)) {
            // Vanilla ray-vs-box does the segment test. AABB.clip finds no ENTRY face when
            // the segment starts inside the box, so a muzzle already sitting in its own
            // smoke — the common case right after popping a screen — needs contains().
            AABB cloud = decoy.getBoundingBox().inflate(radius);
            if (cloud.contains(from) || cloud.clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
