package com.neoalive.tacz_sewv.wing.physics;

/**
 * Steady, wings-level, unaccelerated flight at a given airspeed and flight-path angle: the alpha,
 * elevator and throttle that hold it. Thrust is taken along the nose through the CG and the pitch
 * rate is zero, so {@code Cm = 0} fixes the elevator once alpha is known.
 *
 * <p>Used by the load-time row checks, the self-checks (to start a sim in trim) and, in the
 * controller, as feedforward. Searches only the attached branch, {@code alpha < alphaStall}: a
 * trim that needs more lift than that is reported infeasible, never solved post-stall.
 */
public final class WingTrim {

    /**
     * @param alpha rad; {@code liftOk} false when the attached wing cannot make the lift
     * @param throttle unclamped: above 1 means the engine cannot hold it, below 0 means it needs drag
     */
    public record Result(double alpha, double elevator, double throttle, boolean liftOk) {

        public boolean feasible() {
            return liftOk && Math.abs(elevator) <= 1.0 && throttle >= 0.0 && throttle <= 1.0;
        }
    }

    private WingTrim() {}

    /**
     * @param gamma flight-path angle, rad (positive climbing)
     */
    public static Result solve(WingAirframe af, double g, double rho, double v, double gamma,
                               double flap, double gear, double brake) {
        double qs = 0.5 * rho * v * v * af.area;
        double weight = af.mass * g;
        double tAvail = Propulsion.maxThrust(af, v);
        double alpha = 0.0, thrust = 0.0;
        boolean liftOk = true;
        double hi = af.alphaStall - StrictMath.toRadians(0.5), lo = StrictMath.toRadians(-15.0);
        for (int it = 0; it < 40; it++) {
            double clNeed = (weight * StrictMath.cos(gamma) - thrust * StrictMath.sin(alpha)) / qs;
            if (AeroModel.cl(af, hi, flap) < clNeed) {
                liftOk = false;
                alpha = hi;
            } else {
                double a = lo, b = hi;
                for (int k = 0; k < 60; k++) {
                    double m = 0.5 * (a + b);
                    if (AeroModel.cl(af, m, flap) < clNeed) a = m;
                    else b = m;
                }
                alpha = 0.5 * (a + b);
                liftOk = true;
            }
            double drag = qs * AeroModel.cd(af, alpha, flap, gear, brake, false);
            thrust = (drag + weight * StrictMath.sin(gamma)) / StrictMath.cos(alpha);
        }
        double alphaM = Math.max(-0.5 * Math.PI, Math.min(0.5 * Math.PI, alpha));
        double elevator = -(af.cm0 + af.cmAlpha * alphaM) / af.cmDe;
        double throttle = tAvail > 0.0 ? thrust / tAvail : Double.POSITIVE_INFINITY;
        return new Result(alpha, elevator, throttle, liftOk);
    }

    /** Level-flight top speed at full throttle, clean (bisection on thrust minus drag), m/s. */
    public static double topSpeed(WingAirframe af, double g, double rho) {
        double lo = AeroModel.stallSpeed(af, g, rho, 0.0) * 1.05, hi = 400.0;
        for (int k = 0; k < 80; k++) {
            double m = 0.5 * (lo + hi);
            Result r = solve(af, g, rho, m, 0.0, 0.0, 0.0, 0.0);
            if (r.liftOk() && r.throttle() <= 1.0) lo = m;
            else hi = m;
        }
        return lo;
    }
}
