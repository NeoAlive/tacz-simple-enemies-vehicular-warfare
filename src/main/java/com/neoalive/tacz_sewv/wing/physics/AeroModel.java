package com.neoalive.tacz_sewv.wing.physics;

/**
 * Static aerodynamic coefficients. Pure functions of the airframe and the flow angles, shared by
 * the physics, the trim solver, the controller's envelope protections and the load-time checks,
 * so all of them agree on what the wing does.
 *
 * <p>Lift is the attached linear curve blended into a flat plate past the stall by a symmetric
 * sigmoid (Beard and McLain, <i>Small Unmanned Aircraft</i>, section 4.4), with the flat plate
 * taken as {@code clPost * sin 2a} (default 0.7): a real wing keeps much of its lift past the break rather than
 * collapsing to the textbook {@code 2 sin^2 a cos a}. Drag is the parabolic polar attached and the
 * flat-plate {@code 2 sin^2 a} separated, through the same blend, so the break is smooth (no step
 * the controller would have to fight) but still sharp: width about {@code 1 / stallSharpness} rad.
 */
public final class AeroModel {

    private AeroModel() {}

    /** Stall blend in [0, 1]: 0 attached, 1 fully separated, symmetric in alpha. */
    public static double sigma(WingAirframe af, double alpha) {
        double m = af.stallSharpness, a0 = af.stallCentre;
        double em = StrictMath.exp(-m * (alpha - a0));
        double ep = StrictMath.exp(m * (alpha + a0));
        return (1.0 + em + ep) / ((1.0 + em) * (1.0 + ep));
    }

    /** The attached-flow lift coefficient (no stall), flaps included. */
    public static double clLinear(WingAirframe af, double alpha, double flap) {
        return af.cl0 + af.clAlpha * alpha + af.flapCl * flap;
    }

    public static double cl(WingAirframe af, double alpha, double flap) {
        double s = sigma(af, alpha);
        double plate = af.clPost * StrictMath.sin(2.0 * alpha);
        return (1.0 - s) * clLinear(af, alpha, flap) + s * plate;
    }

    /** Total drag coefficient, configuration drag included. */
    public static double cd(WingAirframe af, double alpha, double flap, double gear, double brake, boolean windmill) {
        double s = sigma(af, alpha);
        double cll = clLinear(af, alpha, flap);
        double sa = StrictMath.sin(alpha);
        double attached = af.cd0 + af.inducedK * cll * cll;
        double separated = af.cd0 + 2.0 * sa * sa;
        return (1.0 - s) * attached + s * separated
                + af.flapCd * flap + af.gearCd * gear + af.brakeCd * brake + (windmill ? af.windmillCd : 0.0);
    }

    /** Peak lift coefficient and the angle it occurs at, by a fine scan (rad). */
    public static double[] clMax(WingAirframe af, double flap) {
        double best = Double.NEGATIVE_INFINITY, at = 0.0;
        for (int i = 0; i <= 900; i++) {
            double a = StrictMath.toRadians(i * 0.05);
            double c = cl(af, a, flap);
            if (c > best) {
                best = c;
                at = a;
            }
        }
        return new double[] {best, at};
    }

    /** 1 g, wings-level stall speed (m/s) at gravity {@code g}, clean unless flapped. */
    public static double stallSpeed(WingAirframe af, double g, double rho, double flap) {
        return StrictMath.sqrt(2.0 * af.mass * g / (rho * af.area * clMax(af, flap)[0]));
    }

    /** Best lift-to-drag ratio in the attached range, clean, and the alpha that gives it. */
    public static double[] bestGlide(WingAirframe af) {
        double best = 0.0, at = 0.0;
        for (int i = -100; i <= 400; i++) {
            double a = StrictMath.toRadians(i * 0.05);
            if (a > af.alphaStall) break;
            double ld = cl(af, a, 0.0) / cd(af, a, 0.0, 0.0, 0.0, false);
            if (ld > best) {
                best = ld;
                at = a;
            }
        }
        return new double[] {best, at};
    }
}
