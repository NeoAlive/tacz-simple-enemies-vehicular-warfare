package com.neoalive.tacz_sewv.entity.ai.navigation;

/**
 * Reciprocal Velocity Obstacle (van den Berg / Lin / Manocha 2008) for one hull vs
 * another. Terrain stays on the context maps; this is only the moving-peer half.
 *
 * <p>{@code v_rel = 2 v_cand − v_A − v_B} is the paper's RVO: each side takes half the
 * avoidance, which is what stops two agents swapping dodge sides every tick (plain VO).
 */
public final class GroundRvo {

    /** Ignore collisions further than this many ticks. */
    public static final double TAU = 30.0;

    /** Inside this many ticks is a hard block, not a rank. */
    public static final double IMMINENT = 8.0;

    /** Disc is a hair larger than the AABB half-widths so a corner clip still counts. */
    public static final double RADIUS_PAD = 1.1;

    private GroundRvo() {}

    /**
     * Time-to-collision of a ray from the origin with velocity {@code (vx, vz)} against a
     * disc at {@code (px, pz)} of radius {@code r}. {@code +∞} when it never hits.
     */
    public static double timeToCollision(double px, double pz, double vx, double vz, double r) {
        double distSq = px * px + pz * pz;
        double rSq = r * r;
        if (distSq <= rSq) return 0.0;
        double a = vx * vx + vz * vz;
        if (a < 1.0E-12) return Double.POSITIVE_INFINITY;
        double pv = px * vx + pz * vz;
        if (pv <= 0.0) return Double.POSITIVE_INFINITY;
        double c = distSq - rSq;
        double disc = pv * pv - a * c;
        if (disc < 0.0) return Double.POSITIVE_INFINITY;
        double t = (pv - Math.sqrt(disc)) / a;
        return t < 0.0 ? Double.POSITIVE_INFINITY : t;
    }

    /** Combined disc radius for two hulls. */
    public static double radius(double halfA, double halfB) {
        return (halfA + halfB) * RADIUS_PAD;
    }

    /**
     * RVO relative velocity: {@code 2 v_cand − v_A − v_B}.
     *
     * @return 1 if overlapping and closing, or a hit within {@link #IMMINENT}; a (0, 1)
     *         rank if a later hit inside {@link #TAU}; else 0. Overlap that is already
     *         separating is 0 so two clipped hulls can peel apart instead of every slot
     *         going hard. Ranking stays below {@link GroundMobility#HARD_CAP} so it
     *         cannot mask a slot by itself (same doctrine as the old peer skirt).
     */
    public static float danger(double px, double pz,
                               double candX, double candZ,
                               double ax, double az,
                               double bx, double bz,
                               double radius) {
        double vx = 2.0 * candX - ax - bx;
        double vz = 2.0 * candZ - az - bz;
        if (px * px + pz * pz <= radius * radius) {
            return px * vx + pz * vz > 0.0 ? 1.0F : 0.0F;
        }
        double t = timeToCollision(px, pz, vx, vz, radius);
        if (t > TAU) return 0.0F;
        if (t <= IMMINENT) return 1.0F;
        return Math.min(0.99F, (float) (1.0 - t / TAU));
    }
}
