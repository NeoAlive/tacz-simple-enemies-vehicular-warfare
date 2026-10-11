package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

/**
 * Pure drag-free ballistics: the launch directions that put a shell of muzzle speed {@code v}
 * (blocks/tick) under gravity {@code g} (blocks/tick², positive) on a point offset
 * {@code (dx, dy, dz)} from the muzzle. No Minecraft, StrictMath only, no randomness —
 * {@code ./gradlew selfCheckArtillery}.
 *
 * <p>{@link #roots} is a line-for-line transcription of SBW's
 * {@code TrajectoryCalculator.calculateShootVectors} (including {@code Vec3.normalize}'s
 * {@code < 1e-4 → zero} rule), so a caller swapping one for the other gets bit-identical vectors.
 * The difference is the naming: index 0 is the HIGH root and index 1 the LOW one <i>by
 * construction</i> ({@code u1 ≥ u2} because the discriminant root is non-negative). SBW's
 * {@code getFlatTrajectory}/{@code getHighTrajectory} return them the other way round from their
 * names — never use those.
 */
public final class BallisticArc {

    private BallisticArc() {}

    /** A chosen launch: unit direction, its elevation (degrees, up-positive) and which root it is. */
    public record Aim(double x, double y, double z, double elevDeg, boolean high) {}

    private static final double[][] NONE = new double[0][];

    /**
     * Unit launch directions {@code {x, y, z}}, high root first. Empty when unreachable or for
     * degenerate input ({@code v ≤ 0}, {@code g ≤ 0}, non-finite) — SBW would produce NaN or a
     * zero vector there instead.
     */
    public static double[][] roots(double dx, double dy, double dz, double v, double g) {
        if (!(v > 0.0) || !(g > 0.0) || !Double.isFinite(v) || !Double.isFinite(g)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return NONE;
        }
        double dh2 = dx * dx + dz * dz;
        double pA = g * g;
        double pB = 4.0 * (dy * g - v * v);
        double pC = 4.0 * (dh2 + dy * dy);
        double discriminant = pB * pB - 4.0 * pA * pC;
        if (discriminant < 0) return NONE;

        double sqrtDisc = StrictMath.sqrt(discriminant);
        double u1 = (-pB + sqrtDisc) / (2.0 * pA);
        double u2 = (-pB - sqrtDisc) / (2.0 * pA);
        boolean has1 = u1 > 1e-9;
        boolean has2 = u2 > 1e-9 && StrictMath.abs(u2 - u1) > 1e-9;
        // u1 >= u2 always, so SBW's sortDescending is the identity here: high (longer flight) first.
        if (has1 && has2) return new double[][]{direction(dx, dy, dz, v, g, u1), direction(dx, dy, dz, v, g, u2)};
        if (has1) return new double[][]{direction(dx, dy, dz, v, g, u1)};
        if (has2) return new double[][]{direction(dx, dy, dz, v, g, u2)};
        return NONE;
    }

    private static double[] direction(double dx, double dy, double dz, double v, double g, double u) {
        double t = StrictMath.sqrt(u);
        double x = dx / (v * t);
        double z = dz / (v * t);
        double y = (dy + 0.5 * g * u) / (v * t);
        // Vec3.normalize
        double d = StrictMath.sqrt(x * x + y * y + z * z);
        return d < 1.0E-4 ? new double[]{0.0, 0.0, 0.0} : new double[]{x / d, y / d, z / d};
    }

    /** Elevation of a direction, degrees, up-positive. */
    public static double elevDeg(double x, double y, double z) {
        return StrictMath.toDegrees(StrictMath.atan2(y, StrictMath.sqrt(x * x + z * z)));
    }

    /**
     * The first root whose elevation lies in {@code [minElevDeg, maxElevDeg]}, high root first
     * (a lob over terrain beats a flat shot whenever the gun can elevate to it). Null when no
     * root fits — inside minimum range, past maximum, or both arcs outside the stops.
     */
    @Nullable
    public static Aim solve(double dx, double dy, double dz, double v, double g,
                            double minElevDeg, double maxElevDeg) {
        double[][] r = roots(dx, dy, dz, v, g);
        for (int i = 0; i < r.length; i++) {
            double e = elevDeg(r[i][0], r[i][1], r[i][2]);
            if (e >= minElevDeg && e <= maxElevDeg) {
                // With one root it is reported as high: there is nothing lower to fall back to.
                return new Aim(r[i][0], r[i][1], r[i][2], e, i == 0);
            }
        }
        return null;
    }

    /**
     * Where to hold the barrel when {@link #solve} answers null: the LOW root's elevation clamped
     * into the stops when any root exists (the target is in range but under the depression stop or
     * over the elevation one — hold as close to the real arc as the gun goes), else 45° clamped
     * (truly out of range: the maximum-range elevation).
     */
    public static double fallbackElevDeg(double dx, double dy, double dz, double v, double g,
                                         double minElevDeg, double maxElevDeg) {
        double[][] r = roots(dx, dy, dz, v, g);
        double e = r.length == 0 ? 45.0 : elevDeg(r[r.length - 1][0], r[r.length - 1][1], r[r.length - 1][2]);
        return StrictMath.max(minElevDeg, StrictMath.min(maxElevDeg, e));
    }
}
