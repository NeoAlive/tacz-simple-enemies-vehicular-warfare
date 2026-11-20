package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.atsuishio.superbwarfare.entity.projectile.M18SmokeGrenadeEntity;
import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.neoalive.tacz_sewv.mixin.AccessorM18SmokeGrenade;

/**
 * Line-of-fire smoke test. SuperbWarfare's {@link SmokeDecoyEntity} is a bare particle emitter
 * with no collision, so block-based line-of-sight never sees it. Each smoking decoy, smoking M18
 * and recently removed M18 is a cloud sized by {@link SmokeCloud}: it grows from one puff to
 * {@code radius} as it ages, and is an ellipsoid rather than a cube.
 *
 * <p>Two SBW facts shape the set. A decoy only smokes once, at {@code igniteTime}, so before that
 * it is a canister in flight. And the eight decoys an M18 throws out have {@code releaseSmoke}
 * off: they only distract missiles, the grenade's own trail is the smoke, and that trail stays
 * for about 17 s after the grenade entity is gone.
 */
public final class SmokeVision {

    private record Linger(double x, double y, double z, long startTick, long endTick) {}

    private static final Map<Level, List<Linger>> LINGERING = new WeakHashMap<>();

    private SmokeVision() {
    }

    /**
     * True if any smoke cloud straddles the {@code from}→{@code to} segment. {@code radius} is a
     * settled cloud's reach. Only entities inside the segment's box inflated by it are examined.
     */
    public static boolean lineBlockedBySmoke(Level level, Entity shooter, Vec3 from, Vec3 to, double radius) {
        AABB corridor = new AABB(from, to).inflate(radius);
        for (Entity e : level.getEntities(shooter, corridor, SmokeVision::mayBeSmoke)) {
            double r = cloudRadius(e, radius);
            if (r > 0.0 && SmokeCloud.segmentHits(
                    from.x, from.y, from.z, to.x, to.y, to.z, e.getX(), e.getY(), e.getZ(), r)) {
                return true;
            }
        }
        List<Linger> lingering = LINGERING.get(level);
        if (lingering == null || lingering.isEmpty()) return false;
        long now = level.getGameTime();
        lingering.removeIf(c -> now >= c.endTick() || now < c.startTick());
        for (Linger c : lingering) {
            if (!corridor.contains(c.x(), c.y(), c.z())) continue;
            double r = SmokeCloud.radius(radius, now - c.startTick(), SmokeCloud.M18_TAU);
            if (SmokeCloud.segmentHits(from.x, from.y, from.z, to.x, to.y, to.z, c.x(), c.y(), c.z(), r)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayBeSmoke(Entity e) {
        return e instanceof SmokeDecoyEntity || e instanceof M18SmokeGrenadeEntity;
    }

    /** Current reach of a smoke entity, 0 when it is not smoking. */
    private static double cloudRadius(Entity e, double full) {
        if (e instanceof SmokeDecoyEntity decoy) {
            if (!decoy.getReleaseSmoke()) return 0.0;
            return SmokeCloud.radius(full, decoy.tickCount - decoy.getIgniteTime(), SmokeCloud.DECOY_TAU);
        }
        if (e instanceof M18SmokeGrenadeEntity m18) {
            int fuse = ((AccessorM18SmokeGrenade) m18).tacz_sewv$getFuse();
            if (fuse > 0) return 0.0;
            return SmokeCloud.radius(full, -fuse, SmokeCloud.M18_TAU);
        }
        return 0.0;
    }

    /** A smoking M18 that is removed leaves its trail cloud where it was. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof M18SmokeGrenadeEntity m18)) return;
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        Entity.RemovalReason reason = m18.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) return;
        int fuse = ((AccessorM18SmokeGrenade) m18).tacz_sewv$getFuse();
        if (fuse > 0) return;
        long now = level.getGameTime();
        List<Linger> list = LINGERING.computeIfAbsent(level, l -> new ArrayList<>());
        list.removeIf(c -> now >= c.endTick() || now < c.startTick());
        list.add(new Linger(m18.getX(), m18.getY(), m18.getZ(), now + fuse, now + SmokeCloud.M18_LINGER_TICKS));
    }
}
