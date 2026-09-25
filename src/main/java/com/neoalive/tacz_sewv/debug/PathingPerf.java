package com.neoalive.tacz_sewv.debug;

/**
 * Running totals for ground path + whisker cost, and for the other per-tick hot paths the perf
 * heuristics trade against. Written from server-thread ticks, read (and reset) by
 * {@code /sewv debug perf}. Observe-only.
 */
public final class PathingPerf {

    public static long fanNanos;
    public static long fanCalls;
    public static long pathNanos;
    public static long pathCalls;
    public static int slotFlips;
    public static int pathFlips;
    /** {@code (x,baseY,z)→Column} cache in {@code GroundTerrainSensor}. */
    public static int columnCacheHits;
    public static int columnCacheMisses;
    public static int gradeCacheHits;
    public static int gradeCacheMisses;
    public static int gradeSkipped;
    /** Per-search cell classification cache in {@code GroundVehicleNodeEvaluator}. */
    public static long cellCacheHits;
    public static long cellCacheMisses;
    /** Searches pushed to a later tick by {@code pathSearchesPerTick}. */
    public static int pathDeferred;
    public static long treeScanNanos;
    public static int treeScans;
    public static int treeSectionsSkipped;
    public static long coverBakeNanos;
    public static int coverChunksBaked;
    public static long podQueryNanos;
    public static int podRefreshes;

    private PathingPerf() {}

    public static String snapshotAndReset() {
        String s = String.format(
                "pathing fan=%.2fms/%d path=%.2fms/%d deferred=%d slotFlips=%d pathFlips=%d "
                        + "colCache=%d/%d gradeCache=%d/%d gradeSkip=%d cellCache=%d/%d"
                        + " | trees=%.2fms/%d sectionsSkipped=%d | cover=%.2fms/%d chunks | pods=%.2fms/%d refreshes",
                fanNanos / 1.0e6, fanCalls, pathNanos / 1.0e6, pathCalls, pathDeferred, slotFlips, pathFlips,
                columnCacheHits, columnCacheHits + columnCacheMisses,
                gradeCacheHits, gradeCacheHits + gradeCacheMisses, gradeSkipped,
                cellCacheHits, cellCacheHits + cellCacheMisses,
                treeScanNanos / 1.0e6, treeScans, treeSectionsSkipped,
                coverBakeNanos / 1.0e6, coverChunksBaked,
                podQueryNanos / 1.0e6, podRefreshes);
        fanNanos = 0;
        fanCalls = 0;
        pathNanos = 0;
        pathCalls = 0;
        pathDeferred = 0;
        slotFlips = 0;
        pathFlips = 0;
        columnCacheHits = 0;
        columnCacheMisses = 0;
        gradeCacheHits = 0;
        gradeCacheMisses = 0;
        gradeSkipped = 0;
        cellCacheHits = 0;
        cellCacheMisses = 0;
        treeScanNanos = 0;
        treeScans = 0;
        treeSectionsSkipped = 0;
        coverBakeNanos = 0;
        coverChunksBaked = 0;
        podQueryNanos = 0;
        podRefreshes = 0;
        return s;
    }
}
