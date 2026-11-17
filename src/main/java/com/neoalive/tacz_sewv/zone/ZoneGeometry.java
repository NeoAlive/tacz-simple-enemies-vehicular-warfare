package com.neoalive.tacz_sewv.zone;

import java.util.function.LongPredicate;

/** Pure chunk maths for spawn zones. Packing matches {@code ChunkPos.asLong}. */
public final class ZoneGeometry {

    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 10;

    private ZoneGeometry() {}

    public static long pack(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }

    public static int clampRadius(int r, int max) {
        return Math.max(MIN_RADIUS, Math.min(Math.min(max, MAX_RADIUS), r));
    }

    public static int chunksCovered(int radius) {
        int side = 2 * radius + 1;
        return side * side;
    }

    public static long upkeepPerTick(int radius, double fePerChunk) {
        return (long) Math.ceil(chunksCovered(radius) * fePerChunk);
    }

    public static boolean covers(int centreX, int centreZ, int radius, int cx, int cz) {
        return Math.abs(cx - centreX) <= radius && Math.abs(cz - centreZ) <= radius;
    }

    public interface ChunkVisitor {
        void visit(long key);
    }

    public static void square(int centreX, int centreZ, int radius, ChunkVisitor v) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) v.visit(pack(centreX + dx, centreZ + dz));
    }

    /** Squared distance in blocks from a block position to the centre of a chunk. */
    public static double distSqToChunkCentre(double bx, double bz, long key) {
        double dx = (x(key) << 4) + 8 - bx;
        double dz = (z(key) << 4) + 8 - bz;
        return dx * dx + dz * dz;
    }

    /**
     * Uniform pick among candidate chunks whose centre lies within [min, max] blocks of the origin
     * and that pass {@code filter}. One pass; the filter only runs on chunks inside the ring.
     * Returns {@code Long.MIN_VALUE} when none qualify. {@code roll} is in [0, 1).
     */
    public static long pickInRing(long[] candidates, double ox, double oz, double min, double max,
            LongPredicate filter, double roll) {
        double minSq = min * min, maxSq = max * max;
        long[] hits = new long[Math.min(candidates.length, 256)];
        int count = 0;
        for (long k : candidates) {
            double d = distSqToChunkCentre(ox, oz, k);
            if (d < minSq || d > maxSq || !filter.test(k)) continue;
            if (count == hits.length) hits = java.util.Arrays.copyOf(hits, count * 2);
            hits[count++] = k;
        }
        if (count == 0) return Long.MIN_VALUE;
        return hits[Math.min(count - 1, (int) (roll * count))];
    }

    /**
     * Nearest chunk (ring by ring, Chebyshev) to the start that {@code allowed} accepts, within
     * {@code maxRings}. Ties within a ring go to the first scanned. {@code Long.MIN_VALUE} if none.
     */
    public static long nearestAllowed(int cx, int cz, int maxRings, LongPredicate allowed) {
        for (int r = 0; r <= maxRings; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    long k = pack(cx + dx, cz + dz);
                    if (allowed.test(k)) return k;
                }
            }
        }
        return Long.MIN_VALUE;
    }
}
