package com.neoalive.tacz_sewv.entity.ai.maneuver;

/**
 * SuperbWarfare's wheel steering ({@code VehicleEngineUtils.wheelEngine}) as a pure model.
 *
 * <p>Per tick with a key held: {@code deltaRot += s*0.12*min(hold,10)} (minus for left),
 * {@code deltaRot *= max(0.78 - 0.25v, 0.1)}, {@code rudder = clamp(rudder - deltaRot, +-0.8)*0.75},
 * {@code yRot -= gain*v*rudder*sign(power)} with gain 12 on ground, 6 afloat. Yaw rate scales with
 * speed, so once the rudder has settled the turn radius does not depend on speed.
 *
 * <p><b>The settling is slow, and that is why this class integrates rather than drawing circles.</b>
 * At the default steering speed and v = 0.5 the rudder needs ~13 ticks and ~29 degrees of yaw to
 * saturate, and a switch to the opposite key takes as long again — so a short segment, and every
 * segment boundary of a three-point turn, follows a wider and straighter curve than r_min. A
 * {@link Table} is the recurrence stepped from one start state until it settles; past its end the
 * hull really is at constant curvature, so the remainder is summed in closed form tick by tick.
 * The composition equals naive stepping, which {@code ManeuverSelfCheck} asserts.
 *
 * <p>Local frame throughout: {@code a} forward, {@code l} LEFT, {@code psi} left-positive. A
 * table is built for a bow swinging left while driving forward; right is the exact mirror and
 * reverse negates the translation (SBW flips the yaw sense with power sign, which the mirrored keys
 * cancel), so symmetry holds by construction rather than by matching arithmetic.
 */
public final class WheelTurnModel {

    /** SBW's own default when a hull's data has no {@code SteeringSpeed}. */
    public static final float SBW_DEFAULT_STEERING_SPEED = 0.1F;
    /** Steady-state |rudder| at full lock: the clamp, then the 0.75 factor. */
    static final double RUDDER_LIMIT = 0.8 * 0.75;
    /** r_min is quoted at the top of the planning speed band — the conservative (widest) end. */
    static final double RMIN_SPEED = 0.5;
    /** r_min for a hull whose data carries no steering speed: SBW runs it at its default, so this
     * is that hull's real radius, not a borrowed one. */
    public static final double FALLBACK_R_MIN = rMin(SBW_DEFAULT_STEERING_SPEED, false);

    private static final int MAX_TABLE_TICKS = 400;
    private static final double SETTLE_EPS = 1.0E-12;

    /** Rudder state a segment starts from. */
    public enum Start {
        /** No key held (first segment). */
        REST,
        /** Settled on the opposite key (a key switch: every boundary of a three-point turn). */
        OPPOSITE,
        /** Settled on the same key (direction flips, key held: a wiggle). */
        SAME
    }

    private WheelTurnModel() {}

    static double gainDeg(boolean fluid) {
        return fluid ? 6.0 : 12.0;
    }

    static double decay(double v) {
        return Math.max(0.78 - 0.25 * v, 0.1);
    }

    /**
     * Settled full-lock radius: {@code 180 / (pi * gain * |rudder|)}, with
     * {@code |rudder| = min(0.6, 3 * delta_ss)} and {@code delta_ss = 1.2 s k / (1 - k)} at
     * {@link #RMIN_SPEED}. NaN for a NaN or non-positive steering speed (refuse to plan).
     */
    public static double rMin(double steeringSpeed, boolean fluid) {
        if (!(steeringSpeed > 0.0) || Double.isInfinite(steeringSpeed)) return Double.NaN;
        double k = decay(RMIN_SPEED);
        double delta = 1.2 * steeringSpeed * k / (1.0 - k);
        double rudder = Math.min(RUDDER_LIMIT, 3.0 * delta);
        return 180.0 / (StrictMath.PI * gainDeg(fluid) * rudder);
    }

    /** The recurrence stepped from one start state, bow swinging left, driving forward. */
    public static final class Table {
        final double v;
        final double[] a;
        final double[] l;
        final double[] psi;
        /** Settled yaw per tick (rad, left-positive) after the last entry. */
        final double omega;
        final int n;

        private Table(double v, double[] a, double[] l, double[] psi, int n, double omega) {
            this.v = v;
            this.a = a;
            this.l = l;
            this.psi = psi;
            this.n = n;
            this.omega = omega;
        }
    }

    /** One SBW steering tick with the LEFT key held. {@code st} = {rudder, deltaRot, hold}. */
    private static double stepLeft(double[] st, double s, double v, boolean fluid) {
        st[2] += 1.0;
        st[1] -= s * 0.12 * Math.min(st[2], 10.0);
        st[1] *= decay(v);
        st[0] = Math.max(-0.8, Math.min(0.8, st[0] - st[1])) * 0.75;
        // left key -> rudder > 0 -> SBW yRot decreases -> bow swings left (psi grows)
        return StrictMath.toRadians(gainDeg(fluid) * v * st[0]);
    }

    public static Table table(double s, double v, boolean fluid, Start start) {
        double[] st = new double[3];
        if (start != Start.REST) {
            // settle on the key the previous segment held, then hand over
            for (int i = 0; i < MAX_TABLE_TICKS; i++) {
                if (start == Start.SAME) stepLeft(st, s, v, fluid);
                else stepRightInPlace(st, s, v);
            }
        }
        double[] a = new double[MAX_TABLE_TICKS + 1];
        double[] l = new double[MAX_TABLE_TICKS + 1];
        double[] psi = new double[MAX_TABLE_TICKS + 1];
        int n = 0;
        double omega = 0.0;
        for (int k = 1; k <= MAX_TABLE_TICKS; k++) {
            double r0 = st[0];
            double d0 = st[1];
            omega = stepLeft(st, s, v, fluid);
            psi[k] = psi[k - 1] + omega;
            a[k] = a[k - 1] + v * StrictMath.cos(psi[k]);
            l[k] = l[k - 1] + v * StrictMath.sin(psi[k]);
            n = k;
            if (st[2] >= 10.0 && Math.abs(st[0] - r0) < SETTLE_EPS && Math.abs(st[1] - d0) < SETTLE_EPS) break;
        }
        return new Table(v, a, l, psi, n, omega);
    }

    /** Right key, used only to settle an OPPOSITE start; the motion it produces is discarded. */
    private static void stepRightInPlace(double[] st, double s, double v) {
        st[2] += 1.0;
        st[1] += s * 0.12 * Math.min(st[2], 10.0);
        st[1] *= decay(v);
        st[0] = Math.max(-0.8, Math.min(0.8, st[0] - st[1])) * 0.75;
    }

    /**
     * End of one segment, relative to its own start pose: {@code {a, l, psi, ticks}}, or null when
     * the sweep cannot be reached (a settled yaw rate of ~0). {@code sweep} is the net yaw change
     * in the bow-swing direction, a magnitude; the segment ends on the first (fractional) tick
     * where that is reached — the same test the executor applies to the measured yaw.
     */
    public static double[] segmentEnd(Table t, double sweep, boolean reverse, boolean bowLeft) {
        double a;
        double l;
        double ticks;
        int k = -1;
        for (int i = 0; i <= t.n; i++) {
            if (t.psi[i] >= sweep) { k = i; break; }
        }
        if (k == 0) {
            a = 0.0;
            l = 0.0;
            ticks = 0.0;
        } else if (k > 0) {
            double f = (sweep - t.psi[k - 1]) / (t.psi[k] - t.psi[k - 1]);
            a = t.a[k - 1] + f * (t.a[k] - t.a[k - 1]);
            l = t.l[k - 1] + f * (t.l[k] - t.l[k - 1]);
            ticks = k - 1 + f;
        } else {
            if (!(t.omega > 1.0E-6)) return null;
            double psiN = t.psi[t.n];
            double rest = sweep - psiN;
            int m = (int) StrictMath.floor(rest / t.omega);
            // sum_{j=1..m} v e^{i(psiN + j w)} = v e^{i psiN} e^{iw} (1 - e^{imw}) / (1 - e^{iw})
            double w = t.omega;
            double nr = 1.0 - StrictMath.cos(m * w);
            double ni = -StrictMath.sin(m * w);
            double dr = 1.0 - StrictMath.cos(w);
            double di = -StrictMath.sin(w);
            double den = dr * dr + di * di;
            double qr = (nr * dr + ni * di) / den;
            double qi = (ni * dr - nr * di) / den;
            double c = StrictMath.cos(psiN + w);
            double s = StrictMath.sin(psiN + w);
            double sumA = t.v * (c * qr - s * qi);
            double sumL = t.v * (c * qi + s * qr);
            double f = (rest - m * w) / w;
            double last = psiN + (m + 1) * w;
            a = t.a[t.n] + sumA + f * t.v * StrictMath.cos(last);
            l = t.l[t.n] + sumL + f * t.v * StrictMath.sin(last);
            ticks = t.n + m + f;
        }
        if (reverse) {
            a = -a;
            l = -l;
        }
        double psi = sweep;
        if (!bowLeft) {
            l = -l;
            psi = -psi;
        }
        return new double[] {a + 0.0, l + 0.0, psi + 0.0, ticks};
    }

    /**
     * The segment stepped tick by tick, as {@code {a[], l[], psi[]}} relative to its start pose,
     * thinned to roughly one point per block plus the end point. For the clearance check only.
     */
    public static double[][] segmentSamples(Table t, double sweep, boolean reverse, boolean bowLeft) {
        double[] end = segmentEnd(t, sweep, reverse, bowLeft);
        if (end == null) return null;
        int total = (int) StrictMath.ceil(end[3]);
        int every = Math.max(1, (int) StrictMath.ceil(1.0 / t.v));
        int cap = total / every + 2;
        double[] sa = new double[cap];
        double[] sl = new double[cap];
        double[] sp = new double[cap];
        int count = 0;
        double sign = reverse ? -1.0 : 1.0;
        double mir = bowLeft ? 1.0 : -1.0;
        double pa = 0.0;
        double pl = 0.0;
        double pp = 0.0;
        for (int k = 1; k < total; k++) {
            if (k <= t.n) {
                pa = t.a[k];
                pl = t.l[k];
                pp = t.psi[k];
            } else {
                // settled: constant curvature, stepped (only the few candidates being cleared get here)
                pp += t.omega;
                pa += t.v * StrictMath.cos(pp);
                pl += t.v * StrictMath.sin(pp);
            }
            if (k % every != 0) continue;
            sa[count] = sign * pa + 0.0;
            sl[count] = mir * sign * pl + 0.0;
            sp[count] = mir * pp + 0.0;
            count++;
        }
        sa[count] = end[0];
        sl[count] = end[1];
        sp[count] = end[2];
        count++;
        return new double[][] {
                java.util.Arrays.copyOf(sa, count), java.util.Arrays.copyOf(sl, count), java.util.Arrays.copyOf(sp, count)};
    }
}
