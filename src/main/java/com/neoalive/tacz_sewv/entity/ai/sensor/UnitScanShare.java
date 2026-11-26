package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.bridge.IAcquisition;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.DeferredFilterList;

/**
 * One entity gather per SEM unit, shared by its {@code VerticalAwareTargetGoal}s.
 *
 * <p>A unit carries four or five of those goals, and all of them walk the same follow-range box on
 * their own random roll, differing only in the predicate. Here the first scan gathers every living
 * entity over the box plus {@link #MARGIN}, and the others reuse that list for {@link #TTL_TICKS} while
 * the stored box still contains theirs. Class and box are applied live on every call, and SEM's predicate
 * lazily as SEM walks the sorted list ({@link DeferredFilterList}), so its range, alive and sight checks are
 * unchanged but never run past the first target it takes. The only staleness: an entity that joins, or
 * moves in from beyond the margin, is seen up to half a second late.
 */
public final class UnitScanShare {

    static final long TTL_TICKS = 10;
    static final double MARGIN = 8.0;

    private static final class Cache {
        final List<LivingEntity> list;
        final AABB box;
        final long at;

        Cache(List<LivingEntity> list, AABB box, long at) {
            this.list = list;
            this.box = box;
            this.at = at;
        }
    }

    private static long gathers;
    private static long reuses;

    private UnitScanShare() {}

    public static boolean enabled() {
        try {
            return SewvConfig.SEM_SCAN_SHARE.get();
        } catch (IllegalStateException unbaked) {
            return false;
        }
    }

    /** Same answer as {@code level.getEntitiesOfClass(cls, box, predicate)}, from the shared gather. */
    public static <T extends LivingEntity> List<T> candidates(Mob mob, Level level, Class<T> cls, AABB box,
                                                             Predicate<? super T> predicate) {
        if (!enabled() || !(mob instanceof IAcquisition holder)) {
            return level.getEntitiesOfClass(cls, box, predicate);
        }
        long now = level.getGameTime();
        Cache cache = holder.sewv$getScanCache() instanceof Cache c ? c : null;
        if (cache == null || now - cache.at >= TTL_TICKS || now < cache.at || !contains(cache.box, box)) {
            AABB gather = box.inflate(MARGIN);
            cache = new Cache(level.getEntitiesOfClass(LivingEntity.class, gather, e -> e != mob), gather, now);
            holder.sewv$setScanCache(cache);
            gathers++;
        } else {
            reuses++;
        }
        List<T> raw = new ArrayList<>();
        for (LivingEntity e : cache.list) {
            if (cls.isInstance(e) && e.getBoundingBox().intersects(box)) raw.add(cls.cast(e));
        }
        // SEM sorts by distance and stops at the first visible match, so test its predicate lazily.
        return new DeferredFilterList<>(raw, predicate);
    }

    public static String snapshotAndReset() {
        String out = "semScan gathers=" + gathers + " reuses=" + reuses + " " + DeferredFilterList.snapshotAndReset();
        gathers = 0;
        reuses = 0;
        return out;
    }

    static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }
}
