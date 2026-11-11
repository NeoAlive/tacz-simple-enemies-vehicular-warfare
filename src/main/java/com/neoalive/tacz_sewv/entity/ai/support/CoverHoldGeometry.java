package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * Pure maths for the Cover and Hold order. Touches no game class, so {@code selfCheckCoverHold}
 * runs headless.
 */
public final class CoverHoldGeometry {

    /** How far downrange of the aimed block the order's threat point sits. */
    public static final double THREAT_RANGE = 32.0;
    /** Lateral gap between the search origins of units ordered together. */
    public static final double SPREAD = 3.0;
    /** Eye height the threat point is raised by, so SEM's rays start at a standing head. */
    public static final double THREAT_EYE = 1.6;

    private CoverHoldGeometry() {}

    /**
     * Unit horizontal bearing {x, z} from the issuer through the aimed block. A zero-length bearing
     * (issuer standing on the block) falls back to the issuer's look yaw, Mojmap degrees.
     */
    public static double[] bearing(double issuerX, double issuerZ, double anchorX, double anchorZ, float yawDeg) {
        double dx = anchorX - issuerX;
        double dz = anchorZ - issuerZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            double yaw = Math.toRadians(yawDeg);
            return new double[] {-Math.sin(yaw), Math.cos(yaw)};
        }
        return new double[] {dx / len, dz / len};
    }

    /** The threat point {x, y, z}: {@link #THREAT_RANGE} downrange of the anchor along {@code bearing}. */
    public static double[] threatPoint(double anchorX, double anchorY, double anchorZ, double[] bearing) {
        return new double[] {
                anchorX + bearing[0] * THREAT_RANGE,
                anchorY + THREAT_EYE,
                anchorZ + bearing[1] * THREAT_RANGE};
    }

    /**
     * Search origin {x, z} for the {@code index}-th of {@code count} units: spread across the bearing,
     * centred on the anchor, so a squad searches a short line instead of one point.
     */
    public static double[] spreadOrigin(double anchorX, double anchorZ, double[] bearing, int index, int count) {
        double offset = (index - (count - 1) * 0.5) * SPREAD;
        return new double[] {anchorX - bearing[1] * offset, anchorZ + bearing[0] * offset};
    }

    /** Absolute game time a hold ends, {@link Long#MAX_VALUE} for a forever hold ({@code holdTicks <= 0}). */
    public static long deadline(long now, long holdTicks) {
        return holdTicks <= 0 ? Long.MAX_VALUE : now + holdTicks;
    }
}
