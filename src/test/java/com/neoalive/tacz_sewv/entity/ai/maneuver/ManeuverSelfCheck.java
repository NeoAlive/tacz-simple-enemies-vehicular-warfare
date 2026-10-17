package com.neoalive.tacz_sewv.entity.ai.maneuver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.neoalive.tacz_sewv.entity.ai.maneuver.ManeuverPlanner.Decision;
import com.neoalive.tacz_sewv.entity.ai.maneuver.ManeuverPlanner.Input;
import com.neoalive.tacz_sewv.entity.ai.maneuver.ManeuverPlanner.State;
import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Start;
import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Table;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;

/**
 * Headless checks for the wheeled maneuver planner. Run via {@code ./gradlew selfCheckManeuver}.
 * Numbered after the plan's verification list. Randomness here is test-only (seeded); the planner
 * itself has none.
 */
public final class ManeuverSelfCheck {

    private static final double DEG = StrictMath.PI / 180.0;
    private static final double R_MIN = WheelTurnModel.FALLBACK_R_MIN;
    private static final float S = WheelTurnModel.SBW_DEFAULT_STEERING_SPEED;
    private static final SweepCheck ALL_CLEAR = (x, z, hx, hz, reverse) -> true;

    /** Mirrored maneuver families, by (direction pattern, first bow side). Drift between the two
     * halves of a pair makes every hull in the game turn the same way. */
    private static final String[][] MIRRORED_MANEUVERS = {{"FRF_L", "FRF_R"}, {"RFR_L", "RFR_R"}};

    private static final Path PURE_ROOT = Path.of("src/main/java/com/neoalive/tacz_sewv/entity/ai/maneuver");
    private static final Pattern NON_STRICT_TRIG = Pattern.compile(
            "(?<!Strict)\\bMath\\.(sin|cos|tan|asin|acos|atan2?|exp|log|log10|pow|cbrt|hypot|expm1|log1p|sqrt|toRadians|toDegrees)\\(");

    public static void main(String[] args) throws IOException {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        checkCircleVsIntegration();      // 1
        checkShortHold();                // 2
        checkReachability();             // 3
        checkFinalPointScoping();        // 4
        checkDirectlyBehind();           // 5
        checkArrivalBands();             // 6
        checkSensorVeto();               // 7
        checkNanRefusal();               // 8
        checkDeterminism();              // 9
        checkMirror();                   // 10
        checkPurity();                   // 11
        System.out.println("ManeuverSelfCheck OK — r_min=" + R_MIN);
    }

    // ------------------------------------------------------------------ helpers

    private static Input route(double ta, double tl, boolean finalPoint, boolean tracked, boolean preferLeft) {
        return new Input(0.0, 0.0, 0.0, 1.0, ta, tl, Double.NaN, -1.0, R_MIN, S, false, 0.5,
                tracked, 3.0 * DEG, finalPoint, false, preferLeft);
    }

    private static Input arrival(double phi, boolean tracked, boolean wasAligning, boolean preferLeft) {
        return new Input(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, phi, 10.0, tracked ? Double.NaN : R_MIN, S, false, 0.2,
                tracked, 0.0, true, wasAligning, preferLeft);
    }

    private static Input withRMin(Input in, double rMin) {
        return new Input(in.x(), in.z(), in.fx(), in.fz(), in.ta(), in.tl(), in.phi(), in.arriveRadius(), rMin,
                in.steeringSpeed(), in.fluid(), in.planSpeed(), in.tracked(), in.straightThreshold(),
                in.finalPoint(), in.wasAligning(), in.preferLeft());
    }

    private static boolean bitEqual(Maneuver x, Maneuver y) {
        if (x == null || y == null) return x == y;
        for (int i = 0; i < x.dirs().length; i++) {
            if (x.dirs()[i] != y.dirs()[i] || x.left()[i] != y.left()[i]) return false;
            if (Double.doubleToRawLongBits(x.sweep()[i]) != Double.doubleToRawLongBits(y.sweep()[i])) return false;
        }
        return Double.doubleToRawLongBits(x.score()) == Double.doubleToRawLongBits(y.score())
                && Double.doubleToRawLongBits(x.ticks()) == Double.doubleToRawLongBits(y.ticks());
    }

    private static String family(Maneuver m) {
        return (m.dirs()[0] > 0 ? "FRF_" : "RFR_") + (m.left()[0] ? "L" : "R");
    }

    // ------------------------------------------------------------------ 1

    /** The classification circle R = d^2/(2l): a unicycle at curvature 1/R, integrated (midpoint,
     * h = 1e-3) over arc length R * 2 theta, reaches the target and ends at heading 2 theta. */
    private static void checkCircleVsIntegration() {
        SplittableRandom rnd = new SplittableRandom(1);
        int tested = 0;
        while (tested < 1000) {
            double d = 1.0 + rnd.nextDouble() * 29.0;
            double theta = (rnd.nextDouble() * 2.0 - 1.0) * 110.0 * DEG;
            double a = d * StrictMath.cos(theta);
            double l = d * StrictMath.sin(theta);
            if (Math.abs(l) < 1.0E-3) continue;
            double r = d * d / (2.0 * l);
            if (Math.abs(r) > 100.0) continue;
            double arc = Math.abs(r * 2.0 * theta);
            double h = 1.0E-3;
            int steps = (int) StrictMath.ceil(arc / h);
            h = arc / steps;
            double pa = 0.0;
            double pl = 0.0;
            double psi = 0.0;
            double k = 1.0 / r;
            for (int i = 0; i < steps; i++) {
                double mid = psi + 0.5 * h * k;
                pa += h * StrictMath.cos(mid);
                pl += h * StrictMath.sin(mid);
                psi += h * k;
            }
            assert Math.hypot(pa - a, pl - l) < 1.0E-3 : "circle misses target d=" + d + " theta=" + theta;
            assert Math.abs(ManeuverPlanner.wrap(psi - 2.0 * theta)) < 1.0E-6 : "arrival heading != 2 theta";
            tested++;
        }
    }

    // ------------------------------------------------------------------ 2

    /** Independent copy of SBW's recurrence, stepped naively: left key, forward. */
    private static double[][] naive(double s, double v, Start start, int ticks) {
        double r = 0.0;
        double d = 0.0;
        double hold = 0.0;
        double k = Math.max(0.78 - 0.25 * v, 0.1);
        if (start != Start.REST) {
            double sign = start == Start.SAME ? -1.0 : 1.0; // SAME settles on left (-), OPPOSITE on right (+)
            for (int i = 0; i < 400; i++) {
                hold++;
                d += sign * s * 0.12 * Math.min(hold, 10.0);
                d *= k;
                r = Math.max(-0.8, Math.min(0.8, r - d)) * 0.75;
            }
        }
        double[][] out = new double[3][ticks + 1];
        for (int t = 1; t <= ticks; t++) {
            hold++;
            d -= s * 0.12 * Math.min(hold, 10.0);
            d *= k;
            r = Math.max(-0.8, Math.min(0.8, r - d)) * 0.75;
            out[2][t] = out[2][t - 1] + StrictMath.toRadians(12.0 * v * r);
            out[0][t] = out[0][t - 1] + v * StrictMath.cos(out[2][t]);
            out[1][t] = out[1][t - 1] + v * StrictMath.sin(out[2][t]);
        }
        return out;
    }

    private static double distToPolyline(double pa, double pl, double[] xa, double[] xl) {
        double best = Math.hypot(pa - xa[0], pl - xl[0]);
        for (int i = 1; i < xa.length; i++) {
            double ax = xa[i - 1];
            double az = xl[i - 1];
            double bx = xa[i] - ax;
            double bz = xl[i] - az;
            double len2 = bx * bx + bz * bz;
            double t = len2 < 1.0E-18 ? 0.0 : Math.max(0.0, Math.min(1.0, ((pa - ax) * bx + (pl - az) * bz) / len2));
            best = Math.min(best, Math.hypot(pa - (ax + t * bx), pl - (az + t * bz)));
        }
        return best;
    }

    private static double[][] withOrigin(double[][] local) {
        int n = local[0].length;
        double[] a = new double[n + 1];
        double[] l = new double[n + 1];
        System.arraycopy(local[0], 0, a, 1, n);
        System.arraycopy(local[1], 0, l, 1, n);
        return new double[][] {a, l};
    }

    /**
     * Short holds, from rest and from opposite lock: the composed model equals naive stepping, the
     * naive footprint stays within 0.5 block of the planner's samples, and an ideal r_min circle
     * does NOT — so this check would catch a regression to closed-form samples.
     */
    private static void checkShortHold() {
        boolean circleFailedSomewhere = false;
        int compared = 0;
        for (double s : new double[] {0.03, 0.1}) {
            for (double v : new double[] {0.2, 0.5}) {
                for (Start start : new Start[] {Start.REST, Start.OPPOSITE}) {
                    Table t = WheelTurnModel.table(s, v, false, start);
                    for (int n = 1; n <= 15; n++) {
                        double[][] nv = naive(s, v, start, n);
                        double sweep = nv[2][n];
                        boolean firstReach = sweep > 0.0;
                        for (int k = 0; k < n && firstReach; k++) firstReach = nv[2][k] < sweep;
                        if (!firstReach) continue; // still unwinding: not yet a net swing this way
                        double[] end = WheelTurnModel.segmentEnd(t, sweep, false, true);
                        assert end != null;
                        assert Math.abs(end[0] - nv[0][n]) < 1.0E-6 && Math.abs(end[1] - nv[1][n]) < 1.0E-6
                                : "composition != naive s=" + s + " v=" + v + " " + start + " n=" + n;
                        assert Math.abs(end[3] - n) < 1.0E-6 : "tick count";
                        // reverse / mirror identities
                        double[] rr = WheelTurnModel.segmentEnd(t, sweep, true, false);
                        assert rr[0] == -end[0] + 0.0 && rr[1] == end[1] && rr[2] == -end[2] : "reverse/mirror identity";
                        compared++;

                        double[][] poly = withOrigin(WheelTurnModel.segmentSamples(t, sweep, false, true));
                        double[][] circle = circleSamples(WheelTurnModel.rMin(s, false), sweep);
                        double worstModel = 0.0;
                        double worstCircle = 0.0;
                        for (int k = 1; k <= n; k++) {
                            worstModel = Math.max(worstModel, distToPolyline(nv[0][k], nv[1][k], poly[0], poly[1]));
                            worstCircle = Math.max(worstCircle, distToPolyline(nv[0][k], nv[1][k], circle[0], circle[1]));
                        }
                        assert worstModel <= 0.5 : "footprint outside cleared samples by " + worstModel;
                        if (worstCircle > 0.5) circleFailedSomewhere = true;
                    }
                }
            }
        }
        assert compared > 20 : "short-hold compared too few cases: " + compared;
        assert circleFailedSomewhere : "an ideal-circle sample set passed every short hold — the check is blind";
    }

    private static double[][] circleSamples(double r, double sweep) {
        int n = Math.max(2, (int) StrictMath.ceil(r * sweep) + 1);
        double[] a = new double[n + 1];
        double[] l = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double p = sweep * i / n;
            a[i] = r * StrictMath.sin(p);
            l[i] = r * (1.0 - StrictMath.cos(p));
        }
        return new double[][] {a, l};
    }

    // ------------------------------------------------------------------ 3

    private static void checkReachability() {
        SplittableRandom rnd = new SplittableRandom(3);
        for (int i = 0; i < 5000; i++) {
            double d = 1.0 + rnd.nextDouble() * 40.0;
            double theta = (4.0 + rnd.nextDouble() * 106.0) * DEG * (rnd.nextBoolean() ? 1 : -1);
            double l = d * StrictMath.sin(theta);
            Decision dec = ManeuverPlanner.classify(route(d * StrictMath.cos(theta), l, true, false, true));
            boolean reach = d * d / (2.0 * Math.abs(l)) >= R_MIN;
            if (reach) assert dec.state() == State.ARC : "reachable final point not ARC " + dec;
            else assert dec.state() == State.MULTI_POINT && dec.plan() : "unreachable final point not planned " + dec;
        }
    }

    // ------------------------------------------------------------------ 4

    private static void checkFinalPointScoping() {
        double d = 10.0;
        double th = 45.0 * DEG;
        assert d / (2.0 * StrictMath.sin(th)) < R_MIN : "test geometry must be inside r_min";
        Decision mid = ManeuverPlanner.classify(route(d * StrictMath.cos(th), d * StrictMath.sin(th), false, false, true));
        assert mid.state() == State.ARC && !mid.plan() : "intermediate bend must roll through: " + mid;
        Decision fin = ManeuverPlanner.classify(route(d * StrictMath.cos(th), d * StrictMath.sin(th), true, false, true));
        assert fin.state() == State.MULTI_POINT && fin.plan() : "final point inside r_min must plan: " + fin;
        double behind = 150.0 * DEG;
        Decision legacy = ManeuverPlanner.classify(route(d * StrictMath.cos(behind), d * StrictMath.sin(behind), false, false, true));
        assert legacy.state() == State.MULTI_POINT && !legacy.plan() : "intermediate behind-beam must be legacy: " + legacy;
        // 90-110 at large R on an intermediate point: today's roll-through band
        double wide = 100.0 * DEG;
        Decision band = ManeuverPlanner.classify(route(60 * StrictMath.cos(wide), 60 * StrictMath.sin(wide), false, false, true));
        assert band.state() == State.ARC : "90-110 band must stay ARC";
    }

    // ------------------------------------------------------------------ 5

    private static void checkDirectlyBehind() {
        for (double l : new double[] {0.0, -0.0}) {
            Input in = route(-10.0, l, true, false, true);
            Decision dec = ManeuverPlanner.classify(in);
            assert dec.state() == State.MULTI_POINT && dec.plan() : "dead astern must be MULTI_POINT";
            Maneuver m = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
            assert m != null : "no plan for a target dead astern";
            for (double s : m.sweep()) assert Double.isFinite(s) && s >= 0.0 && s <= StrictMath.PI : "bad sweep " + m;
            assert Double.isFinite(m.score()) && Double.isFinite(m.ticks());
        }
    }

    // ------------------------------------------------------------------ 6

    private static void checkArrivalBands() {
        assert ManeuverPlanner.classify(arrival(10 * DEG, false, false, true)).state() == State.HOLD : "wheeled 10 enters HOLD";
        Decision twenty = ManeuverPlanner.classify(arrival(20 * DEG, false, false, true));
        assert twenty.state() == State.ALIGN && twenty.plan() : "wheeled 20 must shuffle";
        assert ManeuverPlanner.classify(arrival(10 * DEG, false, true, true)).state() == State.ALIGN : "wheeled 10 stays aligning";
        assert ManeuverPlanner.classify(arrival(7 * DEG, false, true, true)).state() == State.HOLD : "wheeled 7 holds";
        Decision tracked = ManeuverPlanner.classify(arrival(10 * DEG, true, false, true));
        assert tracked.state() == State.ALIGN && !tracked.plan() : "tracked 10 must pivot, never hold";
        assert ManeuverPlanner.classify(arrival(Double.NaN, false, true, true)).state() == State.HOLD : "no heading holds";

        // no gap, no flicker: 0 -> 30 -> 0 with the bit fed back transitions exactly twice
        boolean bit = false;
        int transitions = 0;
        for (int step = 0; step <= 120; step++) {
            double deg = step <= 60 ? step * 0.5 : (120 - step) * 0.5;
            Decision d = ManeuverPlanner.classify(arrival(deg * DEG, false, bit, true));
            assert d.state() == State.HOLD || d.state() == State.ALIGN : "arrival must be HOLD or ALIGN";
            boolean now = d.state() == State.ALIGN;
            if (now != bit) transitions++;
            bit = now;
        }
        assert transitions == 2 : "hysteresis transitions=" + transitions;

        // a shuffle stays inside the ring's hysteresis band
        Input in = arrival(20 * DEG, false, false, true);
        Maneuver m = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
        assert m != null : "no shuffle for a 20-degree correction";
        double bound = ManeuverPlanner.SHUFFLE_BOUND * in.arriveRadius();
        for (double[][] seg : ManeuverPlanner.worldSamples(in, m)) {
            for (int i = 0; i < seg[0].length; i++) {
                assert Math.hypot(seg[0][i], seg[1][i]) <= bound + 1.0E-9 : "shuffle sample beyond the band";
            }
        }
    }

    // ------------------------------------------------------------------ 7

    /** Fake sensor over a height field: a 3-block wall at one point, judged by the real step rule. */
    private static SweepCheck wallAt(double wx, double wz, List<Integer> calls) {
        return (x, z, hx, hz, reverse) -> {
            calls.add(x.length);
            double prev = 0.0;
            for (int i = 0; i < x.length; i++) {
                double h = Math.hypot(x[i] - wx, z[i] - wz) < 0.6 ? 3.0 : 0.0;
                if (GroundMobility.stepDanger(h - prev, 1.0F) >= GroundMobility.HARD_CAP) return false;
                prev = h;
            }
            return true;
        };
    }

    private static boolean passesNear(Input in, Maneuver m, double wx, double wz) {
        for (double[][] seg : ManeuverPlanner.worldSamples(in, m)) {
            for (int i = 0; i < seg[0].length; i++) {
                if (Math.hypot(seg[0][i] - wx, seg[1][i] - wz) < 0.6) return true;
            }
        }
        return false;
    }

    private static void checkSensorVeto() {
        Input in = route(-8.0, 3.0, true, false, true);
        List<Maneuver> ranked = ManeuverPlanner.ranked(in);
        Maneuver best = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
        assert best != null && bitEqual(best, ranked.get(0)) : "clear world returns the top candidate";
        double[][] seg0 = ManeuverPlanner.worldSamples(in, best)[0];
        int mid = seg0[0].length / 2;
        double wx = seg0[0][mid];
        double wz = seg0[1][mid];
        Maneuver expected = null;
        for (int i = 1; i < Math.min(3, ranked.size()); i++) {
            if (!passesNear(in, ranked.get(i), wx, wz)) { expected = ranked.get(i); break; }
        }
        List<Integer> calls = new ArrayList<>();
        Maneuver vetoed = ManeuverPlanner.planMultiPoint(in, wallAt(wx, wz, calls));
        assert !bitEqual(vetoed, best) : "a fouled candidate was returned";
        assert bitEqual(vetoed, expected) : "veto must return the next clear candidate unchanged";
        SweepCheck fouled = (x, z, hx, hz, reverse) -> false;
        assert ManeuverPlanner.planMultiPoint(in, fouled) == null : "all fouled must return null";
    }

    // ------------------------------------------------------------------ 8

    private static void checkNanRefusal() {
        Input fin = withRMin(route(5.0, 5.0, true, false, true), Double.NaN);
        Decision d = ManeuverPlanner.classify(fin);
        assert !d.plan() : "NaN rMin must not plan";
        assert d.state() == State.ARC : "NaN rMin classifies like the old heuristic (45 deg -> ARC), got " + d;
        Decision a = ManeuverPlanner.classify(withRMin(arrival(30 * DEG, false, false, true), Double.NaN));
        assert a.state() == State.HOLD && !a.plan() : "NaN rMin wheeled arrival holds";
        int[] counter = {0};
        SweepCheck counting = (x, z, hx, hz, reverse) -> { counter[0]++; return true; };
        boolean threw = false;
        try {
            ManeuverPlanner.planMultiPoint(fin, counting);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assert threw : "planMultiPoint must refuse NaN rMin";
        assert counter[0] == 0 : "no sweep may be checked for a NaN rMin";
    }

    // ------------------------------------------------------------------ 9

    private static void checkDeterminism() {
        Input[] inputs = {route(-10.0, 2.0, true, false, true), route(4.0, 6.0, true, false, false),
                arrival(25 * DEG, false, false, true), arrival(-40 * DEG, false, true, false)};
        for (Input in : inputs) {
            Maneuver x = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
            Maneuver y = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
            assert bitEqual(x, y) : "planner is not deterministic";
            assert ManeuverPlanner.classify(in).equals(ManeuverPlanner.classify(in));
        }
    }

    // ------------------------------------------------------------------ 10

    private static void checkMirror() {
        // the table is closed under mirroring
        for (String[] pair : MIRRORED_MANEUVERS) {
            assert pair[0].substring(0, 4).equals(pair[1].substring(0, 4)) && !pair[0].equals(pair[1]);
        }
        List<Input> grid = new ArrayList<>();
        for (double a : new double[] {-12.0, -6.0, 3.0, 7.0}) {
            for (double l : new double[] {-5.0, -1.0E-12, 0.0, 1.0E-12, 2.0, 6.0}) {
                grid.add(route(a, l, true, false, true));
                grid.add(route(a, l, true, false, false));
            }
        }
        for (double phi : new double[] {-60, -20, 20, 45, 90}) {
            grid.add(arrival(phi * DEG, false, false, true));
            grid.add(arrival(phi * DEG, false, false, false));
        }
        int compared = 0;
        for (Input in : grid) {
            Maneuver p = ManeuverPlanner.planMultiPoint(in, ALL_CLEAR);
            Maneuver q = ManeuverPlanner.planMultiPoint(in.mirrored(), ALL_CLEAR);
            if (p == null) {
                assert q == null : "mirror lost a plan";
                continue;
            }
            assert bitEqual(q, p.mirrored()) : "plan(mirror(in), !preferLeft) != mirror(plan(in)) for " + in;
            compared++;
        }
        assert compared > 20 : "mirror compared too few plans";

        // self-symmetric input: the parity bit alone picks the side, and it picks mirror images
        Maneuver left = ManeuverPlanner.planMultiPoint(route(-10.0, 0.0, true, false, true), ALL_CLEAR);
        Maneuver right = ManeuverPlanner.planMultiPoint(route(-10.0, 0.0, true, false, false), ALL_CLEAR);
        assert left != null && bitEqual(right, left.mirrored()) : "biased tie-break on a symmetric input";
        assert !family(left).equals(family(right)) : "both parities chose the same side";
    }

    // ------------------------------------------------------------------ 11

    private static void checkPurity() throws IOException {
        try (Stream<Path> files = Files.walk(PURE_ROOT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p);
                assert !src.contains("import net.minecraft") && !src.contains("import com.atsuishio")
                        : p + " imports Minecraft/SBW";
                assert !NON_STRICT_TRIG.matcher(src).find() : p + " uses non-strict Math";
            }
        }
    }
}
