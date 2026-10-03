package com.neoalive.tacz_sewv.heli.avoid;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.HeliReference;

/**
 * Tactical avoidance (plan section 4.9): a smooth altitude and lateral offset applied to the
 * reference after guidance and before control. Procedures never see it. There is deliberately no
 * yaw term: a helicopter's path does not depend on its heading, so yawing would not move the swept
 * volume, and yaw belongs to aiming.
 *
 * <p>Demand (20 Hz), from the path probe:
 * <pre>
 *   t_c = (r_o - r_s) / max(closure, 0.5)
 *   k   = 1 - s((t_c - T_near) / (T_far - T_near))        0 far ... 1 near
 *   b_y*   = k max(0, y_top + c_v - y_ref(r_o))
 *   b_lat* = k side d_lat     only if the climb cannot be made: dy / t_c > 0.8 v_climb
 * </pre>
 * The bias b = (b_y, b_lat) follows b* through a critically damped second-order filter
 * (b'' = w^2 (b* - b) - 2 w b'), with b_y >= 0 and b_y' <= v_climb, so b and b' are continuous and
 * the biased reference stays C1 whatever the probe does between ticks.
 */
public final class AvoidBias {

    public static final double T_NEAR = 1.5, T_FAR = 4.0, CLIMB_MARGIN = 4.0, OMEGA = 1.5;

    private final double vClimb;
    private double targetY, targetLat;
    private double by, byd, bydd, bl, bld, bldd;
    /** Last lateral axis used, kept when the reference is too slow to define one. */
    private double nx = 1.0, nz = 0.0;

    public AvoidBias(double vClimb) {
        this.vClimb = vClimb;
    }

    /**
     * Set the demand from the probe. Pass {@code Double.POSITIVE_INFINITY} for {@code range} when
     * the path ahead is clear; the bias then decays through the same filter.
     *
     * @param range     arc length to the first blocking sample along the reference path
     * @param yTop      top of the blocking obstacle
     * @param yRef      reference altitude at that range
     * @param closure   closing speed along the path
     * @param side      +1 left, -1 right (the fan's clear side)
     * @param lateral   lateral offset needed to clear
     * @param standoff  r_s, the safe distance kept from the obstacle
     */
    public void demand(double range, double yTop, double yRef, double closure, int side, double lateral,
                       double standoff) {
        if (!(range < Double.POSITIVE_INFINITY)) {
            targetY = 0.0;
            targetLat = 0.0;
            return;
        }
        double tc = Math.max(0.0, range - standoff) / Math.max(closure, 0.5);
        double x = Math.max(0.0, Math.min(1.0, (tc - T_NEAR) / (T_FAR - T_NEAR)));
        double k = 1.0 - x * x * (3.0 - 2.0 * x);
        double dy = Math.max(0.0, yTop + CLIMB_MARGIN - yRef);
        targetY = k * dy;
        targetLat = dy / Math.max(tc, 1.0E-3) > 0.8 * vClimb ? k * side * lateral : 0.0;
    }

    /** Advance the filter by one sub-step. */
    public void advance(double h) {
        double w2 = OMEGA * OMEGA, w = 2.0 * OMEGA;
        double ny = Math.min(vClimb, byd + h * (w2 * (targetY - by) - w * byd));
        bydd = (ny - byd) / h;
        byd = ny;
        by += h * byd;
        if (by < 0.0) {
            by = 0.0;
            byd = Math.max(0.0, byd);
        }
        double nl = bld + h * (w2 * (targetLat - bl) - w * bld);
        bldd = (nl - bld) / h;
        bld = nl;
        bl += h * bld;
    }

    public double altitude() {
        return by;
    }

    public double lateral() {
        return bl;
    }

    /**
     * Apply the bias to {@code r}. Lateral axis n = n_L(v_ref horizontal), left of the reference
     * track; n' = -kappa |v_h| v_hat, so the position, velocity and acceleration offsets are
     * b_y y + b_l n, b_y' y + b_l' n + b_l n', b_y'' y + b_l'' n + 2 b_l' n'. The b_l n'' term is
     * O(b kappa^2 V^2), bounded and continuous within a path segment, and is dropped.
     */
    public HeliReference apply(HeliReference r) {
        double vx = r.v().x(), vz = r.v().z(), sp = Math.sqrt(vx * vx + vz * vz);
        double ndx = 0.0, ndz = 0.0;
        if (sp > 0.5) {
            double ux = vx / sp, uz = vz / sp;
            nx = uz;
            nz = -ux;
            // Turn rate of the track, left positive: (v.z a.x - v.x a.z) / |v|^2.
            double chiDot = (vz * r.a().x() - vx * r.a().z()) / (sp * sp);
            ndx = -chiDot * ux;
            ndz = -chiDot * uz;
        }
        Vector3d p = new Vector3d(r.p()).add(bl * nx, by, bl * nz);
        Vector3d v = new Vector3d(r.v()).add(bld * nx + bl * ndx, byd, bld * nz + bl * ndz);
        Vector3d a = new Vector3d(r.a()).add(bldd * nx + 2.0 * bld * ndx, bydd, bldd * nz + 2.0 * bld * ndz);
        return new HeliReference(r.t(), p, v, a, r.yaw(), r.yawRate());
    }
}
