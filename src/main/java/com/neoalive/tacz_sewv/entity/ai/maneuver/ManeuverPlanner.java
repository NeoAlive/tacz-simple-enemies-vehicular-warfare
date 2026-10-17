package com.neoalive.tacz_sewv.entity.ai.maneuver;

import java.util.ArrayList;
import java.util.List;

import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Start;
import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Table;

/**
 * How a ground hull physically gets to a point: the pose-space decision below
 * {@code DriveVehicleGoal}'s dispatch, which only ever answers <em>where</em>. Pure — no world
 * reads, {@link StrictMath} only, no randomness — so it runs headless in
 * {@code ./gradlew selfCheckManeuver} and every client and server agree.
 *
 * <p>Everything is in the hull's local frame: {@code a} forward, {@code l} LEFT (the sign
 * {@code VehicleTargeting.signedAngleTo} calls positive), angles left-positive. The pose
 * ({@code x, z, fx, fz}) is carried only to turn clearance samples back into world points.
 *
 * <p><b>The radius test is applied only at the final steer point.</b> At the wheeled path
 * lookahead (~10 blocks) R = d / (2 sin theta) drops below r_min for any bend sharper than ~39
 * degrees, so testing every steer point would reverse ordinary routing. An intermediate point
 * classifies exactly as the old 110-degree heuristic did.
 */
public final class ManeuverPlanner {

    /** A forward arc may still be tried up to here — today's roll-through band, restored. Not the
     * legacy reverse's own "lined" test, which is a separate constant in the driver. */
    public static final double ARC_MAX_BEARING_RAD = StrictMath.toRadians(110.0);
    /** ALIGN bands: stay aligning above 8; a wheeled hull starts only above 15 (a shuffle is
     * expensive), a tracked one at 8 (a pivot is cheap, and today's faceHeading pivots at 8). */
    public static final double ALIGN_STAY_RAD = StrictMath.toRadians(8.0);
    public static final double ALIGN_ENTER_WHEELED_RAD = StrictMath.toRadians(15.0);
    public static final double PLAN_SPEED_MIN = 0.2;
    public static final double PLAN_SPEED_MAX = 0.5;
    /** Same total the executor allows: 3 x WHEEL_REVERSE_MAX_TICKS. */
    public static final int MAX_MANEUVER_TICKS = 240;
    /** A shuffle never plans a sample past this multiple of the arrival radius (the ring's hysteresis band). */
    public static final double SHUFFLE_BOUND = 1.25;

    /** Grid floor: a secondary guard that keeps a segment out of the regime where the rudder
     * transient is the whole segment. The fix for short segments is the integrated model. */
    static final double SWEEP_MIN = StrictMath.PI / 6.0;
    static final double SWEEP_MAX = StrictMath.PI;
    static final int GRID = 12;
    private static final int GN_STEPS = 3;
    private static final double GN_H = 1.0E-4;
    static final double TIE_EPS = 1.0E-9;
    private static final int MAX_CLEARANCE_CANDIDATES = 3;
    private static final double[] SAMPLE_SPEEDS = {PLAN_SPEED_MIN, PLAN_SPEED_MAX};

    public enum State { HOLD, ALIGN, STRAIGHT, ARC, MULTI_POINT }

    /** {@code plan}: a multi-point (or shuffle) plan may be searched for. */
    public record Decision(State state, boolean plan) {}

    /**
     * @param ta,tl       target, local; {@code phi} target heading, local, NaN = none
     * @param rMin        settled full-lock radius; NaN = refuse to plan (legacy behaviour exactly)
     * @param planSpeed   current speed clamped to [{@link #PLAN_SPEED_MIN}, {@link #PLAN_SPEED_MAX}]
     * @param finalPoint  the steer point is the destination or the path's last node
     * @param wasAligning the driver's one bit of state, fed back in: classify is a pure function of
     *                    this whole record
     * @param preferLeft  {@code (id & 1) == 0}, the StalemateBreaker / scored-flank rule
     */
    public record Input(double x, double z, double fx, double fz,
                        double ta, double tl, double phi,
                        double arriveRadius, double rMin, double steeringSpeed, boolean fluid,
                        double planSpeed, boolean tracked, double straightThreshold,
                        boolean finalPoint, boolean wasAligning, boolean preferLeft) {

        public boolean hasHeading() {
            return !Double.isNaN(this.phi);
        }

        /** Reflected across the hull's own axis, parity bit flipped with it. */
        public Input mirrored() {
            return new Input(this.x, this.z, this.fx, this.fz, this.ta, -this.tl, -this.phi,
                    this.arriveRadius, this.rMin, this.steeringSpeed, this.fluid, this.planSpeed,
                    this.tracked, this.straightThreshold, this.finalPoint, this.wasAligning, !this.preferLeft);
        }
    }

    private ManeuverPlanner() {}

    // ---------------------------------------------------------------- classification

    public static Decision classify(Input in) {
        // NaN r_min: decided here, on the first call — behave as the old heuristic, never search.
        boolean canPlan = !Double.isNaN(in.rMin);
        boolean finalPoint = in.finalPoint && canPlan;
        double d = StrictMath.sqrt(in.ta * in.ta + in.tl * in.tl);

        if (d <= in.arriveRadius) {
            if (!in.hasHeading()) return new Decision(State.HOLD, false);
            double enter = in.tracked ? ALIGN_STAY_RAD : ALIGN_ENTER_WHEELED_RAD;
            boolean aligning = Math.abs(in.phi) > (in.wasAligning ? ALIGN_STAY_RAD : enter);
            if (!aligning) return new Decision(State.HOLD, false);
            if (in.tracked) return new Decision(State.ALIGN, false);
            return canPlan ? new Decision(State.ALIGN, true) : new Decision(State.HOLD, false);
        }

        double theta = StrictMath.atan2(in.tl, in.ta);
        if (Math.abs(theta) < in.straightThreshold) return new Decision(State.STRAIGHT, false);
        boolean reachable = !finalPoint || in.tracked || arcReachable(d, in.tl, in.rMin);
        if (Math.abs(theta) <= ARC_MAX_BEARING_RAD && reachable) return new Decision(State.ARC, false);
        return new Decision(State.MULTI_POINT, !in.tracked && finalPoint);
    }

    /** One forward circle tangent to the heading through the target: R = d^2 / (2|l|). The
     * {@code l == 0} test comes first, so a point dead ahead or dead astern never divides. */
    static boolean arcReachable(double d, double l, double rMin) {
        if (l == 0.0) return false; // dead ahead is STRAIGHT already; dead astern has no arc
        return d * d / (2.0 * Math.abs(l)) >= rMin;
    }

    // ---------------------------------------------------------------- multi-point search

    /** One evaluated candidate, canonical frame. */
    private record Cand(int order, int[] dirs, boolean[] left, double a1, double a2, double a3,
                        double score, double ticks, double ea, double el) {}

    private static final class Tables {
        final Table[] byStart = new Table[3];

        Tables(Input in, double v) {
            for (Start s : Start.values()) this.byStart[s.ordinal()] = WheelTurnModel.table(in.steeringSpeed, v, in.fluid, s);
        }

        Table forSegment(int[] dirs, boolean[] left, int i) {
            if (i == 0) return this.byStart[Start.REST.ordinal()];
            return this.byStart[(keyLeft(dirs, left, i) == keyLeft(dirs, left, i - 1) ? Start.SAME : Start.OPPOSITE).ordinal()];
        }
    }

    /** The key held: astern a left bow swing is the right key. */
    static boolean keyLeft(int[] dirs, boolean[] left, int i) {
        return left[i] == (dirs[i] > 0);
    }

    /**
     * The best clear multi-point maneuver, or null. Two families (F-R-F, R-F-R) x two bow patterns
     * (three-point: one swing direction, keys alternate; wiggle: swing alternates, key held — the
     * only way a net correction under ~60 degrees exists with 30-degree segments) x both first
     * sides, on a 12x12 grid of the first two sweeps, the third solved. 8 x 144 = 1152 cells.
     *
     * <p>Searched and scored in the CANONICAL frame (target on the left, or for a dead-centre input
     * the {@code preferLeft} side) and mirrored back on the way out, so an input and its mirror get
     * exact mirror answers — rounding cannot pick a different side for {@code l = +-1e-12}.
     */
    public static Maneuver planMultiPoint(Input in, SweepCheck check) {
        int checked = 0;
        for (Maneuver m : ranked(in)) {
            if (checked++ >= MAX_CLEARANCE_CANDIDATES) break;
            if (clear(in, m, check)) return m;
        }
        return null;
    }

    /** Every class's best candidate, best first, already mirrored back out of the canonical frame. */
    static List<Maneuver> ranked(Input in) {
        if (!(in.rMin > 0.0) || Double.isInfinite(in.rMin)) {
            throw new IllegalArgumentException("planMultiPoint needs a finite rMin; classify must gate NaN");
        }
        boolean pose = in.hasHeading();
        double key = pose ? in.phi : in.tl;
        boolean mirror = key < 0.0 || (key == 0.0 && !in.preferLeft);
        double ta = in.ta + 0.0;
        double tl = (mirror ? -in.tl : in.tl) + 0.0;
        double phi = pose ? (mirror ? -in.phi : in.phi) + 0.0 : Double.NaN;

        Tables tables = new Tables(in, in.planSpeed);
        List<Cand> ranked = new ArrayList<>();
        int order = 0;
        for (int family = 0; family < 2; family++) {
            int[] dirs = family == 0 ? new int[] {1, -1, 1} : new int[] {-1, 1, -1};
            for (int pattern = 0; pattern < 2; pattern++) {
                for (int side = 0; side < 2; side++) {
                    boolean first = side == 0; // canonical side (left) first
                    boolean[] left = pattern == 0
                            ? new boolean[] {first, first, first}
                            : new boolean[] {first, !first, first};
                    Cand best = null;
                    for (int i = 0; i < GRID; i++) {
                        for (int j = 0; j < GRID; j++) {
                            Cand c = eval(in, tables, order, dirs, left, gridSweep(i), gridSweep(j), ta, tl, phi);
                            if (c != null && (best == null || c.score < best.score - TIE_EPS)) best = c;
                        }
                    }
                    if (best != null && pose) best = refine(in, tables, best, ta, tl, phi);
                    if (best != null) insertRanked(ranked, best);
                    order++;
                }
            }
        }

        List<Maneuver> out = new ArrayList<>(ranked.size());
        for (Cand c : ranked) {
            Maneuver m = new Maneuver(c.dirs.clone(), c.left.clone(), new double[] {c.a1, c.a2, c.a3}, c.score, c.ticks);
            out.add(mirror ? m.mirrored() : m);
        }
        return out;
    }

    static double gridSweep(int i) {
        return SWEEP_MIN + (SWEEP_MAX - SWEEP_MIN) * i / (GRID - 1);
    }

    /** Stable: a tie keeps search order (family, pattern, canonical side first). */
    private static void insertRanked(List<Cand> ranked, Cand c) {
        int at = ranked.size();
        for (int i = 0; i < ranked.size(); i++) {
            if (c.score < ranked.get(i).score - TIE_EPS) { at = i; break; }
        }
        ranked.add(at, c);
    }

    private static Cand eval(Input in, Tables t, int order, int[] dirs, boolean[] left,
                             double a1, double a2, double ta, double tl, double phi) {
        double[] p = {0.0, 0.0, 0.0, 0.0}; // a, l, psi, ticks
        if (!advance(p, t.forSegment(dirs, left, 0), a1, dirs[0], left[0])) return null;
        if (!advance(p, t.forSegment(dirs, left, 1), a2, dirs[1], left[1])) return null;
        if (in.hasHeading() && !withinShuffle(in, p)) return null;
        double sigma = left[2] ? 1.0 : -1.0;
        double a3;
        Table t3 = t.forSegment(dirs, left, 2);
        if (in.hasHeading()) {
            a3 = sigma * wrap(phi - p[2]);
        } else {
            // aim: end facing the target; the third segment moves the hull, so iterate twice
            double ca = p[0];
            double cl = p[1];
            a3 = 0.0;
            for (int it = 0; it < 2; it++) {
                a3 = sigma * wrap(StrictMath.atan2(tl - cl, ta - ca) - p[2]);
                if (a3 < 0.0) break;
                double[] e = WheelTurnModel.segmentEnd(t3, a3, dirs[2] < 0, left[2]);
                if (e == null) return null;
                ca = p[0] + StrictMath.cos(p[2]) * e[0] - StrictMath.sin(p[2]) * e[1];
                cl = p[1] + StrictMath.sin(p[2]) * e[0] + StrictMath.cos(p[2]) * e[1];
            }
        }
        if (a3 < 0.0 && a3 > -1.0E-12) a3 = 0.0;
        if (a3 < 0.0 || a3 > SWEEP_MAX) return null; // never sign-flipped into the other side
        if (!advance(p, t3, a3, dirs[2], left[2])) return null;
        if (p[3] > MAX_MANEUVER_TICKS) return null;
        if (in.hasHeading() && !withinShuffle(in, p)) return null;

        double length = in.planSpeed * p[3];
        double ea = p[0] - ta;
        double el = p[1] - tl;
        double score;
        if (in.hasHeading()) {
            double eh = wrap(p[2] - phi);
            score = ea * ea + el * el + 4.0 * eh * eh + 0.05 * length;
        } else {
            double dx = ta - p[0];
            double dl = tl - p[1];
            double c = StrictMath.cos(p[2]);
            double s = StrictMath.sin(p[2]);
            double fa = c * dx + s * dl;
            double fl = -s * dx + c * dl;
            double dEnd = StrictMath.sqrt(dx * dx + dl * dl);
            double thetaEnd = StrictMath.atan2(fl, fa);
            boolean reach = dEnd <= in.arriveRadius
                    || (Math.abs(thetaEnd) <= ARC_MAX_BEARING_RAD && (fl == 0.0 ? fa > 0.0 : arcReachable(dEnd, fl, in.rMin)));
            score = 10.0 * thetaEnd * thetaEnd + (reach ? 0.0 : 5.0) + 0.05 * length;
        }
        return new Cand(order, dirs, left, a1, a2, a3, score, p[3], ea, el);
    }

    /** Compose one segment onto pose {@code p}. */
    private static boolean advance(double[] p, Table t, double sweep, int dir, boolean bowLeft) {
        double[] e = WheelTurnModel.segmentEnd(t, sweep, dir < 0, bowLeft);
        if (e == null) return false;
        double c = StrictMath.cos(p[2]);
        double s = StrictMath.sin(p[2]);
        p[0] += c * e[0] - s * e[1];
        p[1] += s * e[0] + c * e[1];
        p[2] += e[2];
        p[3] += e[3];
        return true;
    }

    private static boolean withinShuffle(Input in, double[] p) {
        double bound = SHUFFLE_BOUND * in.arriveRadius;
        return p[0] * p[0] + p[1] * p[1] <= bound * bound;
    }

    /** Pose mode only: 3 Gauss-Newton steps on (a1, a2) against the position residual, the third
     * sweep re-solved each time. Every step is clamped into the grid's range, so a sweep can never
     * go negative and carry the side out of {@code left[]}. */
    private static Cand refine(Input in, Tables t, Cand c, double ta, double tl, double phi) {
        Cand cur = c;
        for (int step = 0; step < GN_STEPS; step++) {
            double h1 = cur.a1 + GN_H > SWEEP_MAX ? -GN_H : GN_H;
            double h2 = cur.a2 + GN_H > SWEEP_MAX ? -GN_H : GN_H;
            Cand c1 = eval(in, t, cur.order, cur.dirs, cur.left, cur.a1 + h1, cur.a2, ta, tl, phi);
            Cand c2 = eval(in, t, cur.order, cur.dirs, cur.left, cur.a1, cur.a2 + h2, ta, tl, phi);
            if (c1 == null || c2 == null) break;
            double j00 = (c1.ea - cur.ea) / h1;
            double j01 = (c2.ea - cur.ea) / h2;
            double j10 = (c1.el - cur.el) / h1;
            double j11 = (c2.el - cur.el) / h2;
            double det = j00 * j11 - j01 * j10;
            if (Math.abs(det) < 1.0E-12) break;
            double d1 = (-cur.ea * j11 + cur.el * j01) / det;
            double d2 = (-cur.el * j00 + cur.ea * j10) / det;
            double n1 = clamp(cur.a1 + d1);
            double n2 = clamp(cur.a2 + d2);
            Cand next = eval(in, t, cur.order, cur.dirs, cur.left, n1, n2, ta, tl, phi);
            if (next == null || !(next.score < cur.score - 1.0E-12)) break;
            cur = next;
        }
        return cur;
    }

    private static double clamp(double sweep) {
        return Math.max(SWEEP_MIN, Math.min(SWEEP_MAX, sweep));
    }

    static double wrap(double angle) {
        return StrictMath.IEEEremainder(angle, 2.0 * StrictMath.PI);
    }

    // ---------------------------------------------------------------- clearance

    /**
     * Every segment, at both ends of the speed band (transient geometry depends on v), through the
     * check. One false discards the candidate. This is not a complete guarantee — a hull outside
     * [0.2, 0.5] sweeps outside it — the executor's live probes are the net there.
     */
    static boolean clear(Input in, Maneuver m, SweepCheck check) {
        double[][][] all = worldSamples(in, m);
        if (all == null) return false;
        for (int s = 0; s < all.length; s++) {
            double[][] seg = all[s];
            if (seg[0].length == 0) continue;
            if (in.hasHeading()) {
                double bound = SHUFFLE_BOUND * in.arriveRadius;
                for (int i = 0; i < seg[0].length; i++) {
                    double dx = seg[0][i] - in.x;
                    double dz = seg[1][i] - in.z;
                    if (dx * dx + dz * dz > bound * bound) return false;
                }
            }
            if (!check.clear(seg[0], seg[1], seg[2], seg[3], m.dirs()[s % m.dirs().length] < 0)) return false;
        }
        return true;
    }

    /**
     * World samples per segment per speed: index {@code speed * 3 + segment}, each
     * {@code {x[], z[], hx[], hz[]}}. Null if a segment is unreachable at some speed.
     */
    public static double[][][] worldSamples(Input in, Maneuver m) {
        int segs = m.dirs().length;
        double[][][] out = new double[SAMPLE_SPEEDS.length * segs][][];
        // left = (fz, -fx): positive l is the side signedAngleTo calls positive
        double lx = in.fz;
        double lz = -in.fx;
        for (int sp = 0; sp < SAMPLE_SPEEDS.length; sp++) {
            Tables t = new Tables(in, SAMPLE_SPEEDS[sp]);
            double[] p = {0.0, 0.0, 0.0, 0.0};
            for (int i = 0; i < segs; i++) {
                Table table = t.forSegment(m.dirs(), m.left(), i);
                boolean reverse = m.dirs()[i] < 0;
                double[][] local = WheelTurnModel.segmentSamples(table, m.sweep()[i], reverse, m.left()[i]);
                if (local == null) return null;
                double c = StrictMath.cos(p[2]);
                double s = StrictMath.sin(p[2]);
                int n = m.sweep()[i] == 0.0 ? 0 : local[0].length;
                double[] wx = new double[n];
                double[] wz = new double[n];
                double[] hx = new double[n];
                double[] hz = new double[n];
                for (int k = 0; k < n; k++) {
                    double a = p[0] + c * local[0][k] - s * local[1][k];
                    double l = p[1] + s * local[0][k] + c * local[1][k];
                    double psi = p[2] + local[2][k];
                    wx[k] = in.x + a * in.fx + l * lx;
                    wz[k] = in.z + a * in.fz + l * lz;
                    double ch = StrictMath.cos(psi);
                    double sh = StrictMath.sin(psi);
                    hx[k] = ch * in.fx + sh * lx;
                    hz[k] = ch * in.fz + sh * lz;
                }
                out[sp * segs + i] = new double[][] {wx, wz, hx, hz};
                if (!advance(p, table, m.sweep()[i], m.dirs()[i], m.left()[i])) return null;
            }
        }
        return out;
    }
}
