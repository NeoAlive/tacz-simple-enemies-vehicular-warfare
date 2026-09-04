package com.neoalive.tacz_sewv.debug;

/**
 * Running totals for ground path + whisker cost. Written from the drive tick, read by
 * {@code /sewv debug perf}. Observe-only.
 */
public final class PathingPerf {

    public static long fanNanos;
    public static long fanCalls;
    public static long pathNanos;
    public static long pathCalls;
    public static int slotFlips;
    public static int pathFlips;
    /** Intra-tick {@code (x,z)→Column} cache in {@code GroundTerrainSensor}. */
    public static int columnCacheHits;
    public static int columnCacheMisses;
    public static int gradeCacheHits;
    public static int gradeCacheMisses;
    public static int gradeSkipped;

    private PathingPerf() {}

    public static String snapshotAndReset() {
        String s = String.format(
                "pathing fan=%.2fms/%d path=%.2fms/%d slotFlips=%d pathFlips=%d "
                        + "colCache=%d/%d gradeCache=%d/%d gradeSkip=%d",
                fanNanos / 1.0e6, fanCalls, pathNanos / 1.0e6, pathCalls, slotFlips, pathFlips,
                columnCacheHits, columnCacheHits + columnCacheMisses,
                gradeCacheHits, gradeCacheHits + gradeCacheMisses, gradeSkipped);
        fanNanos = 0;
        fanCalls = 0;
        pathNanos = 0;
        pathCalls = 0;
        slotFlips = 0;
        pathFlips = 0;
        columnCacheHits = 0;
        columnCacheMisses = 0;
        gradeCacheHits = 0;
        gradeCacheMisses = 0;
        gradeSkipped = 0;
        return s;
    }
}
