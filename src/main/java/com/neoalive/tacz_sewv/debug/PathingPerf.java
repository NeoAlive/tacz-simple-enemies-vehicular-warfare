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

    private PathingPerf() {}

    public static String snapshotAndReset() {
        String s = String.format(
                "pathing fan=%.2fms/%d path=%.2fms/%d slotFlips=%d pathFlips=%d",
                fanNanos / 1.0e6, fanCalls, pathNanos / 1.0e6, pathCalls, slotFlips, pathFlips);
        fanNanos = 0;
        fanCalls = 0;
        pathNanos = 0;
        pathCalls = 0;
        slotFlips = 0;
        pathFlips = 0;
        return s;
    }
}
