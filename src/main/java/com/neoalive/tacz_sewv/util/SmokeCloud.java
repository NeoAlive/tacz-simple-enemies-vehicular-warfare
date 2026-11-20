package com.neoalive.tacz_sewv.util;

/**
 * Size and shape of one SBW smoke cloud, fitted to its particles measured live in a dev client
 * (50 CustomSmoke puffs, friction 0.98, quads about 1.5 blocks across their centre).
 *
 * <p>A decoy burst reaches a visible edge of about 2.6 blocks after 1 s, 4.2 after 3 s and 5.4
 * once settled. An M18 grenade puffs a small trail every other tick instead, so it grows far
 * slower: 2.3 after 1 s, 3.8 after 5 s, 4.9 after 10 s. Both are modelled as
 * {@code QUAD + (full - QUAD) * (1 - e^(-age / tau))}. Height stays at about 0.78 of the
 * horizontal reach, so the cloud is an ellipsoid, not the cube an inflated AABB gives.
 */
public final class SmokeCloud {

    /** Half size of one puff: the cloud is never thinner than a single quad. */
    public static final double QUAD = 1.5;

    /** Growth time constants in game ticks. */
    public static final double DECOY_TAU = 40.0;
    public static final double M18_TAU = 140.0;

    /** Vertical semi axis over horizontal. */
    public static final double VERTICAL = 0.78;

    /** Ticks an M18's cloud stays after the grenade entity is removed (measured: about 17 s). */
    public static final long M18_LINGER_TICKS = 340;

    private SmokeCloud() {}

    /** Horizontal reach of a cloud {@code age} ticks after it started, settling at {@code full}. */
    public static double radius(double full, double age, double tau) {
        if (age < 0.0) return 0.0;
        if (full <= QUAD) return full;
        return QUAD + (full - QUAD) * (1.0 - Math.exp(-age / tau));
    }

    /**
     * Does the segment {@code a -> b} pass through the ellipsoid of horizontal radius {@code r}
     * centred at {@code c}? Coordinates are relative to nothing in particular; only differences
     * are used.
     */
    public static boolean segmentHits(double ax, double ay, double az, double bx, double by, double bz,
                                      double cx, double cy, double cz, double r) {
        if (r <= 0.0) return false;
        double inv = 1.0 / VERTICAL;
        double ux = ax - cx, uy = (ay - cy) * inv, uz = az - cz;
        double dx = bx - ax, dy = (by - ay) * inv, dz = bz - az;
        double lenSq = dx * dx + dy * dy + dz * dz;
        double t = lenSq < 1.0E-12 ? 0.0 : -(ux * dx + uy * dy + uz * dz) / lenSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        double px = ux + dx * t, py = uy + dy * t, pz = uz + dz * t;
        return px * px + py * py + pz * pz <= r * r;
    }
}
