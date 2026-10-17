package com.neoalive.tacz_sewv.heli.guidance;

import org.joml.Vector3d;

/**
 * C2 hand-over between two references (plan section 4.7).
 *
 * <p>With sigma = (t - t_s)/T_b and the quintic w = 10 sigma^3 - 15 sigma^4 + 6 sigma^5:
 * <pre>
 *   p_b = p_o + w dp
 *   v_b = v_o + w dv + w' dp
 *   a_b = a_o + w (a_i - a_o) + 2 w' dv + w'' dp
 * </pre>
 * where dp = p_i(t) - p_o(t) and dv = v_i(t) - v_o(t) are both references sampled at the SAME
 * current t. These are the product rule applied to p_o + w (p_i - p_o); endpoint constants would
 * give a wrong a_b whenever either reference accelerates. Because w, w' and w'' vanish at
 * sigma = 0 and w = 1, w' = w'' = 0 at sigma = 1, position, velocity and acceleration match the
 * outgoing reference at the start and the incoming one at the end, for any pair.
 */
public final class ReferenceBlend {

    /** max |w''| T_b^2 = 10/sqrt(3), max |w'| T_b = 15/8. */
    public static final double MAX_W2 = 10.0 / Math.sqrt(3.0);
    public static final double MAX_W1 = 15.0 / 8.0;

    private ReferenceBlend() {}

    /**
     * Window length for a switch whose references differ by {@code dp}, {@code dv} at the switch
     * time: long enough that the blend adds at most {@code vBlend} velocity and {@code aBlend}
     * acceleration, clamped to [tbMin, tbMax].
     */
    public static double window(double dp, double dv, double tbMin, double tbMax, double vBlend, double aBlend) {
        double tb = Math.max(tbMin, Math.max(StrictMath.sqrt(MAX_W2 * dp / aBlend),
                Math.max(MAX_W1 * dp / vBlend, MAX_W1 * dv / aBlend)));
        return Math.min(tb, tbMax);
    }

    /** Blend of {@code out} and {@code in} (both sampled at {@code t}) for a switch at {@code tStart}. */
    public static HeliReference blend(HeliReference out, HeliReference in, double tStart, double tb, double t) {
        double s = Math.max(0.0, Math.min(1.0, (t - tStart) / tb));
        if (s >= 1.0) return in;
        double s2 = s * s;
        double w = s2 * s * (10.0 - 15.0 * s + 6.0 * s2);
        double w1 = 30.0 * s2 * (1.0 - s) * (1.0 - s) / tb;
        double w2 = 60.0 * s * (1.0 - s) * (1.0 - 2.0 * s) / (tb * tb);

        Vector3d dp = new Vector3d(in.p()).sub(out.p());
        Vector3d dv = new Vector3d(in.v()).sub(out.v());
        Vector3d da = new Vector3d(in.a()).sub(out.a());
        Vector3d p = new Vector3d(out.p()).fma(w, dp);
        Vector3d v = new Vector3d(out.v()).fma(w, dv).fma(w1, dp);
        Vector3d a = new Vector3d(out.a()).fma(w, da).fma(2.0 * w1, dv).fma(w2, dp);

        double dyaw = wrapRad(in.yaw() - out.yaw());
        double yaw = out.yaw() + w * dyaw;
        double yawRate = out.yawRate() + w * (in.yawRate() - out.yawRate()) + w1 * dyaw;
        return new HeliReference(t, p, v, a, yaw, yawRate);
    }

    /** Wrap into (-pi, pi]. */
    public static double wrapRad(double a) {
        double r = a % (2.0 * Math.PI);
        if (r > Math.PI) r -= 2.0 * Math.PI;
        else if (r <= -Math.PI) r += 2.0 * Math.PI;
        return r;
    }
}
