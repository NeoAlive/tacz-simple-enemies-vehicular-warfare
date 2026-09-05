package com.neoalive.tacz_sewv.debug;

/**
 * Running totals for fixed-wing AI + plane chunk tickets. Written from
 * {@code DrivePlaneGoal.tick} / {@link com.neoalive.tacz_sewv.util.ChunkTicket}, read by
 * {@code /sewv debug perf}. Observe-only — zero when nothing is flying.
 *
 * <p>NEAR vs FAR are the LOD bands: per-call averages here are independent of how rare plane ticks
 * are relative to the rest of the server thread. Sub-buckets under FAR (ally scan vs skip) and NEAR
 * (parked vs active) exist to diagnose composition / throttling artifacts — not to change LOD.
 */
public final class PlanePerf {

    /** Wall time inside {@code DrivePlaneGoal.tick} while {@code farLod == false}. */
    public static long nearNanos;
    public static long nearCalls;
    /** NEAR ticks in {@code LANDED}/{@code GROUNDED} — cheap holdPark. */
    public static long nearParkedNanos;
    public static long nearParkedCalls;
    /** NEAR ticks that are actually flying / fighting / landing. */
    public static long nearActiveNanos;
    public static long nearActiveCalls;

    /** Wall time inside {@code DrivePlaneGoal.tick} while {@code farLod == true}. */
    public static long farNanos;
    public static long farCalls;
    /** FAR ticks that ran the RU/US ally AABB scan this tick. */
    public static long farScanNanos;
    public static long farScanCalls;
    /** FAR ticks that skipped the ally scan (throttled). */
    public static long farSkipNanos;
    public static long farSkipCalls;
    /** Wall time spent inside the ally scan body only (FAR ticks that scanned). */
    public static long farAllyScanBodyNanos;
    public static long farAllyScanBodyCalls;

    /** {@link com.neoalive.tacz_sewv.entity.ai.support.AirLod#nearPlayers} cache. */
    public static long playerNearHits;
    public static long playerNearMisses;

    /**
     * Live count of plane goals currently holding a Forge ticket. +1 on acquire, −1 on release.
     * Parked ({@code LANDED}/{@code GROUNDED}) should drive this toward zero.
     */
    public static int planeTicketsHeld;
    /** Game ticks (goal ticks) during which a plane goal held a ticket — duration proxy. */
    public static long planeTicketHeldTicks;

    // --- ChunkTicket (all entity-owned tickets: plane, heli, mortar, …) -----------------------
    public static long ticketFollowCalls;
    public static long ticketFollowNoops;
    public static long ticketFollowCrosses;
    public static long ticketForceAdds;
    public static long ticketForceRemoves;
    /** Live count of ChunkTicket instances with a forced chunk. */
    public static int ticketHeldNow;

    private PlanePerf() {}

    /**
     * @param farLod      post-mode FAR transit flag
     * @param farAllyScan true when this FAR tick actually ran {@code refreshAllies}' scan
     * @param parked      mode is LANDED or GROUNDED (NEAR composition)
     */
    public static void recordTick(long nanos, boolean farLod, boolean farAllyScan, boolean parked) {
        if (farLod) {
            farNanos += nanos;
            farCalls++;
            if (farAllyScan) {
                farScanNanos += nanos;
                farScanCalls++;
            } else {
                farSkipNanos += nanos;
                farSkipCalls++;
            }
        } else {
            nearNanos += nanos;
            nearCalls++;
            if (parked) {
                nearParkedNanos += nanos;
                nearParkedCalls++;
            } else {
                nearActiveNanos += nanos;
                nearActiveCalls++;
            }
        }
    }

    public static void noteFarAllyScanBody(long nanos) {
        farAllyScanBodyNanos += nanos;
        farAllyScanBodyCalls++;
    }

    public static void notePlayerNearHit() {
        playerNearHits++;
    }

    public static void notePlayerNearMiss() {
        playerNearMisses++;
    }

    public static void notePlaneTicketHeld(boolean holding) {
        if (holding) planeTicketHeldTicks++;
    }

    public static String snapshotAndReset() {
        String s = String.format(
                "plane tick near=%.2fms/%d (avg=%.3fms) [parked avg=%.3fms/%d active avg=%.3fms/%d] "
                        + "far=%.2fms/%d (avg=%.3fms) [scan avg=%.3fms/%d skip avg=%.3fms/%d "
                        + "allyBody avg=%.3fms/%d] "
                        + "playerNearCache=%d/%d | "
                        + "planeTicketsHeld=%d heldTicks=%d | "
                        + "ticket follow=%d (noop=%d cross=%d) force +%d/-%d heldNow=%d",
                nearNanos / 1.0e6, nearCalls, avgMs(nearNanos, nearCalls),
                avgMs(nearParkedNanos, nearParkedCalls), nearParkedCalls,
                avgMs(nearActiveNanos, nearActiveCalls), nearActiveCalls,
                farNanos / 1.0e6, farCalls, avgMs(farNanos, farCalls),
                avgMs(farScanNanos, farScanCalls), farScanCalls,
                avgMs(farSkipNanos, farSkipCalls), farSkipCalls,
                avgMs(farAllyScanBodyNanos, farAllyScanBodyCalls), farAllyScanBodyCalls,
                playerNearHits, playerNearHits + playerNearMisses,
                planeTicketsHeld, planeTicketHeldTicks,
                ticketFollowCalls, ticketFollowNoops, ticketFollowCrosses,
                ticketForceAdds, ticketForceRemoves, ticketHeldNow);
        nearNanos = 0;
        nearCalls = 0;
        nearParkedNanos = 0;
        nearParkedCalls = 0;
        nearActiveNanos = 0;
        nearActiveCalls = 0;
        farNanos = 0;
        farCalls = 0;
        farScanNanos = 0;
        farScanCalls = 0;
        farSkipNanos = 0;
        farSkipCalls = 0;
        farAllyScanBodyNanos = 0;
        farAllyScanBodyCalls = 0;
        playerNearHits = 0;
        playerNearMisses = 0;
        planeTicketHeldTicks = 0;
        ticketFollowCalls = 0;
        ticketFollowNoops = 0;
        ticketFollowCrosses = 0;
        ticketForceAdds = 0;
        ticketForceRemoves = 0;
        // planeTicketsHeld and ticketHeldNow are live gauges — do not reset.
        return s;
    }

    private static double avgMs(long nanos, long calls) {
        return calls == 0 ? 0.0 : (nanos / 1.0e6) / calls;
    }
}
