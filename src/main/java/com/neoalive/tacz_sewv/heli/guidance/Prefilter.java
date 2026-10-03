package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Critically damped second-order filter with rate and acceleration limits, for any snapshot
 * parameter that feeds a reference position (cruise altitude, hold point, target position):
 * y'' = w^2 (target - y) - 2 w y', the rate held to [rateMin, rateMax] and the rate's change to
 * aMax per second. The acceleration limit is what keeps the output C1 in every case: a rate that
 * starts outside its band (a hand-over from a faster reference) eases into it rather than being
 * clamped in one step, and a far target cannot command an unbounded acceleration.
 */
public final class Prefilter {

    private double y, yd, ydd, time = Double.NaN;
    private boolean primed;

    public void reset(double value) {
        reset(value, 0.0);
    }

    /** Start at {@code value} moving at {@code rate}: a hand-over that keeps the reference C1. */
    public void reset(double value, double rate) {
        y = value;
        yd = rate;
        ydd = 0.0;
        primed = true;
        time = Double.NaN;
    }

    /** Advance by {@code h} toward {@code target} with no acceleration limit. */
    public double step(double target, double omega, double h, double rateMin, double rateMax) {
        return step(target, omega, h, rateMin, rateMax, Double.POSITIVE_INFINITY);
    }

    /** Advance by {@code h} toward {@code target}. Returns the filtered value. */
    public double step(double target, double omega, double h, double rateMin, double rateMax, double aMax) {
        if (!primed) reset(target);
        double raw = omega * omega * (target - y) - 2.0 * omega * yd;
        double want = Math.max(rateMin, Math.min(rateMax, yd + h * raw));
        double ydNew = yd + Math.max(-aMax * h, Math.min(aMax * h, want - yd));
        ydd = (ydNew - yd) / h;
        yd = ydNew;
        y += h * yd;
        return y;
    }

    /**
     * Advance to absolute time {@code t} in steps of {@code h} (idempotent for a repeated {@code t},
     * so two readers sampling the same instant see the same value). The first call anchors the clock.
     */
    public double at(double t, double target, double omega, double h, double rateMin, double rateMax, double aMax) {
        if (Double.isNaN(time)) {
            if (!primed) reset(target);
            time = t;
            return y;
        }
        while (time + 0.5 * h < t) {
            step(target, omega, h, rateMin, rateMax, aMax);
            time += h;
        }
        return y;
    }

    public double value() { return y; }

    public double rate() { return yd; }

    public double accel() { return ydd; }
}
