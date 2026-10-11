package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

/**
 * Pure reduced-charge solver: the muzzle speed that puts a shell on a point at a CHOSEN launch
 * elevation, instead of the elevation that does it at the gun's full charge ({@link BallisticArc}).
 * That is how an AI howitzer lobs at ranges where its full-charge high root is past the elevation
 * stop. No Minecraft, StrictMath only, no randomness — {@code ./gradlew selfCheckReducedCharge}.
 *
 * <p>Exact for SBW's integrator, not just the textbook parabola. {@code FastThrowableProjectile}
 * moves by its velocity THEN subtracts gravity, so tick {@code n} sits at
 * {@code (n·vh, n·(vy + g/2) − g·n²/2)}: the continuous parabola with initial vertical speed
 * {@code vy + g/2}. The closed form is solved for that effective speed and the real launch is
 * {@code g/2} lower. Uncorrected that is ~4 blocks of overshoot on a 500-block 45° shot.
 */
public final class ReducedCharge {

    private ReducedCharge() {}

    /**
     * Unit launch direction, muzzle speed (blocks/tick), real launch elevation (degrees,
     * up-positive), the effective-parabola elevation the envelope was applied to, and the flight
     * time to the target in ticks.
     */
    public record Charge(double x, double y, double z, double velocity, double elevDeg,
                         double arcElevDeg, double flightTicks) {}

    /**
     * Charge that lands on offset {@code (dx, dy, dz)} with gravity {@code g} at
     * {@code preferredElevDeg}, clamped into {@code [minElevDeg, maxElevDeg]}. Null when the target
     * is at or above the apex any speed reaches at that elevation, when the speed is non-finite or
     * non-positive, or when it would exceed {@code vMax} (a reduced charge is never a bigger one).
     */
    @Nullable
    public static Charge solve(double dx, double dy, double dz, double g, double preferredElevDeg,
                               double minElevDeg, double maxElevDeg, double vMax) {
        if (!(g > 0.0) || !Double.isFinite(g) || !Double.isFinite(dx) || !Double.isFinite(dy)
                || !Double.isFinite(dz) || !Double.isFinite(preferredElevDeg)) {
            return null;
        }
        double dh = StrictMath.sqrt(dx * dx + dz * dz);
        if (!(dh > 1.0e-6)) return null; // straight up/down: no bearing, no lob
        double arcElev = StrictMath.max(minElevDeg, StrictMath.min(maxElevDeg, preferredElevDeg));
        double th = StrictMath.toRadians(arcElev);
        double cos = StrictMath.cos(th);
        double denom = 2.0 * (dh * StrictMath.tan(th) - dy) * cos * cos;
        if (!(denom > 0.0)) return null;
        double vEff = StrictMath.sqrt(g * dh * dh / denom);
        if (!(vEff > 0.0) || !Double.isFinite(vEff)) return null;

        double vh = vEff * cos;
        double vy = vEff * StrictMath.sin(th) - 0.5 * g; // undo the integrator's half-step
        double v = StrictMath.sqrt(vh * vh + vy * vy);
        if (!(v > 0.0) || !Double.isFinite(v) || v > vMax) return null;

        // Same bearing as BallisticArc: the horizontal launch component is (dx, dz) scaled.
        double hx = dx / dh * vh / v, hz = dz / dh * vh / v;
        return new Charge(hx, vy / v, hz, v, BallisticArc.elevDeg(hx, vy / v, hz), arcElev, dh / vh);
    }
}
