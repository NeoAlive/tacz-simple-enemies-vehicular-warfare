package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.Arrays;

import net.minecraft.world.entity.LivingEntity;

import com.neoalive.tacz_sewv.bridge.IAcquisition;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Reuse of SEM's infantry line of sight ({@code TacticalLineOfSightHelper.hasFoliageTransparentLineOfSight}).
 *
 * <p>Five SEM goals ask the same question, mostly about the unit's current target, and each casts up to four
 * rays plus a block walk. A four-slot ring on the observer keeps the last answers. One tick would be exact,
 * but the goals ask on different ticks and reuse was 0.3% in a 450-unit battle; at the default of three ticks
 * half the rays are saved and an answer is at most 0.15 s old. Server thread only.
 */
public final class SemLosCache {

    static final int SLOTS = 4;
    private static final long EMPTY = Long.MIN_VALUE;

    private static long hits;
    private static long misses;

    private SemLosCache() {}

    /** Slots of (target id, game time, answer), then the next slot to overwrite. */
    public static long[] newRing() {
        long[] ring = new long[SLOTS * 3 + 1];
        Arrays.fill(ring, EMPTY);
        ring[SLOTS * 3] = 0;
        return ring;
    }

    static int ticks() {
        try {
            return SewvConfig.SEM_LOS_CACHE_TICKS.get();
        } catch (IllegalStateException unbaked) {
            return 0;
        }
    }

    /** 1 seen, 0 not seen, -1 not cached. */
    public static int lookup(LivingEntity observer, LivingEntity target) {
        int ticks = ticks();
        if (ticks <= 0 || !(observer instanceof IAcquisition holder)) return -1;
        int found = find(holder.sewv$losCache(), target.getId(), observer.level().getGameTime(), ticks);
        if (found >= 0) hits++;
        else misses++;
        return found;
    }

    public static void store(LivingEntity observer, LivingEntity target, boolean seen) {
        if (ticks() <= 0 || !(observer instanceof IAcquisition holder)) return;
        put(holder.sewv$losCache(), target.getId(), observer.level().getGameTime(), seen);
    }

    static int find(long[] ring, long id, long now, int ticks) {
        for (int i = 0; i < SLOTS; i++) {
            int o = i * 3;
            if (ring[o] != id) continue;
            long age = now - ring[o + 1];
            return age >= 0 && age < ticks ? (int) ring[o + 2] : -1;
        }
        return -1;
    }

    static void put(long[] ring, long id, long now, boolean seen) {
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (ring[i * 3] == id) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = (int) ring[SLOTS * 3];
            ring[SLOTS * 3] = (slot + 1) % SLOTS;
        }
        int o = slot * 3;
        ring[o] = id;
        ring[o + 1] = now;
        ring[o + 2] = seen ? 1 : 0;
    }

    public static String snapshotAndReset() {
        long total = hits + misses;
        String out = "semLos hits=" + hits + " misses=" + misses
                + " (" + (total == 0 ? 0 : Math.round(100.0 * hits / total)) + "% reused, ticks=" + ticks() + ")";
        hits = 0;
        misses = 0;
        return out;
    }
}
