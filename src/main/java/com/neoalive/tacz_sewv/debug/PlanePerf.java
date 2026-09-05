package com.neoalive.tacz_sewv.debug;

/**
 * Running totals for fixed-wing AI + plane chunk tickets. Written from
 * {@code DrivePlaneGoal.tick} / {@link com.neoalive.tacz_sewv.util.ChunkTicket}, read by
 * {@code /sewv debug perf}. Observe-only — zero when nothing is flying.
 *
 * <p>NEAR vs FAR are the LOD bands: per-call averages here are independent of how rare plane ticks
 * are relative to the rest of the server thread (the sampling-rarity problem that killed profiler
 * passes). {@code planeTicketsHeld} is a live gauge — it should track airborne planes, not lifetime
 * spawns.
 */
public final class PlanePerf {

    /** Wall time inside {@code DrivePlaneGoal.tick} while {@code farLod == false}. */
    public static long nearNanos;
    public static long nearCalls;
    /** Wall time inside {@code DrivePlaneGoal.tick} while {@code farLod == true}. */
    public static long farNanos;
    public static long farCalls;

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

    public static void recordTick(long nanos, boolean farLod) {
        if (farLod) {
            farNanos += nanos;
            farCalls++;
        } else {
            nearNanos += nanos;
            nearCalls++;
        }
    }

    public static void notePlaneTicketHeld(boolean holding) {
        if (holding) planeTicketHeldTicks++;
    }

    public static String snapshotAndReset() {
        String s = String.format(
                "plane tick near=%.2fms/%d (avg=%.3fms) far=%.2fms/%d (avg=%.3fms) "
                        + "planeTicketsHeld=%d heldTicks=%d | "
                        + "ticket follow=%d (noop=%d cross=%d) force +%d/-%d heldNow=%d",
                nearNanos / 1.0e6, nearCalls, avgMs(nearNanos, nearCalls),
                farNanos / 1.0e6, farCalls, avgMs(farNanos, farCalls),
                planeTicketsHeld, planeTicketHeldTicks,
                ticketFollowCalls, ticketFollowNoops, ticketFollowCrosses,
                ticketForceAdds, ticketForceRemoves, ticketHeldNow);
        nearNanos = 0;
        nearCalls = 0;
        farNanos = 0;
        farCalls = 0;
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
