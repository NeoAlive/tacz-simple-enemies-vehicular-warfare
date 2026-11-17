package com.neoalive.tacz_sewv.procedural.events;

/**
 * The distance ring an event may set up in, clamped to what the server actually simulates. Pure, so
 * {@code selfCheckEventSpots} can pin it.
 *
 * <p>An event placed past the simulated radius either lands in an unloaded chunk, where vanilla's
 * height read answers the bottom of the world and SEM's spawn gate fails, or in a loaded chunk that
 * does not tick, where the units stand frozen until a player walks over. So the far edge is capped at
 * the radius, and a near edge past the cap is pulled in to a share of it to keep a ring at all.
 */
public record EventBand(int min, int max) {

    /** Where the near edge goes when the requested one is past the cap. */
    static final double NEAR_SHARE = 0.6;
    /** Smallest ring kept, so a tiny view distance still leaves room to sample. */
    static final int MIN_WIDTH = 16;

    /**
     * @param min requested near edge, blocks
     * @param max requested far edge, blocks
     * @param radius simulated radius around the player, blocks
     */
    public static EventBand clamp(int min, int max, int radius) {
        return clamp(min, max, radius, 0);
    }

    /**
     * As above for an event whose pieces reach {@code reach} blocks either side of its centre,
     * laid across the line of sight ({@link EventSpawns#acrossX}): the centre may then be at most
     * {@code sqrt(radius^2 - reach^2)} out for both ends to stay inside the radius.
     */
    public static EventBand clamp(int min, int max, int radius, int reach) {
        if (radius > 0 && reach > 0) {
            double inner = Math.sqrt(Math.max(0.0, (double) radius * radius - (double) reach * reach));
            radius = Math.max(MIN_WIDTH * 2, (int) inner);
        }
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        if (radius <= 0) return new EventBand(lo, hi);
        hi = Math.min(hi, radius);
        if (lo > hi - MIN_WIDTH) lo = Math.min(lo, (int) (hi * NEAR_SHARE));
        lo = Math.max(0, Math.min(lo, hi - MIN_WIDTH));
        return new EventBand(lo, hi);
    }

    /** Radius in blocks the server simulates around a player, one chunk short so a whole fight fits. */
    public static int simulatedRadius(int viewChunks, int simulationChunks) {
        return Math.max(MIN_WIDTH * 2, Math.min(viewChunks, simulationChunks) * 16 - 16);
    }
}
