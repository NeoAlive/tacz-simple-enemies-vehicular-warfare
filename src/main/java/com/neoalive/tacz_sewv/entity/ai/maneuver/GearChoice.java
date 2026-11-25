package com.neoalive.tacz_sewv.entity.ai.maneuver;

import java.util.concurrent.ConcurrentHashMap;

import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Start;
import com.neoalive.tacz_sewv.entity.ai.maneuver.WheelTurnModel.Table;

/**
 * Forward or astern to a point behind the beam: whichever arrives first. Pure, {@link StrictMath}
 * only, no randomness, so it runs headless in {@code ./gradlew selfCheckManeuver}.
 *
 * <p>Both options are costed in ticks: turn round then drive the leg forward, or swing the stern
 * on then drive the leg astern. The gearbox ratio alone is the wrong test. FCP's wheeled hulls
 * reverse at about 0.3 of forward speed, worse than its tanks, yet a wheeled hull is the one
 * that should back up, because its turn costs a three-point turn and a tank's costs a pivot.
 *
 * <p>Reverse cannot run long: the leg is capped at {@code maxBlocks}, and along a reverse leg the
 * distance only shrinks, which only favours reverse further, so a latched choice never flips back
 * while the destination holds still. The latch widens the band (enter under 0.85 of the forward
 * cost, leave over 1.10) so a hull on the line between the two never hunts.
 */
public final class GearChoice {

    public static final double CONSULT_MIN_RAD = StrictMath.PI / 2.0;
    static final double ENTER_RATIO = 0.85;
    static final double LEAVE_RATIO = 1.10;

    // SBW trackEngine, read from the bytecode: per held tick deltaRot += s * 0.12 * min(hold, 10),
    // then deltaRot *= max(0.76 - 0.1 * speed, 0.3), then yaw -= 8 * deltaRot degrees.
    private static final double PIVOT_DECAY = 0.76;
    private static final double PIVOT_YAW_GAIN_DEG = 8.0;
    private static final int PIVOT_TABLE_TICKS = 200;

    private static final ConcurrentHashMap<Long, double[]> PIVOT_TABLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Table> WHEEL_TABLES = new ConcurrentHashMap<>();

    public enum Gear { FORWARD, REVERSE }

    /**
     * @param theta     bearing to the steer target, local, left-positive
     * @param d         remaining path length to the destination, blocks
     * @param vFwd      top speed ahead, blocks per tick; {@code vBack} astern; NaN = unknown
     * @param aFwd      acceleration from rest, blocks per tick squared; NaN or 0 = no ramp term
     * @param planSpeed wheeled only: the speed the turn tables are stepped at
     * @param latched   the hull is already reversing toward this destination
     * @param frontBias in [0, 1): fraction taken off the astern cost (keeping the bow to a threat)
     */
    public record Input(double theta, double d, double vFwd, double vBack, double aFwd, double aBack,
                        boolean tracked, double steeringSpeed, boolean fluid, double planSpeed,
                        double maxBlocks, boolean latched, double frontBias) {}

    /** Costs in ticks; NaN when the gear was decided without costing. */
    public record Result(Gear gear, double costForward, double costReverse, double breakEven) {

        static Result forward() {
            return new Result(Gear.FORWARD, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private GearChoice() {}

    public static Result decide(Input in) {
        double theta = Math.abs(in.theta);
        if (!(theta > CONSULT_MIN_RAD) || !(in.d > 0.0) || !(in.d <= in.maxBlocks)) return Result.forward();
        if (!(in.vFwd > 0.0) || !(in.vBack > 0.0) || Double.isInfinite(in.vFwd) || Double.isInfinite(in.vBack)) {
            return Result.forward();
        }
        double turn = turnTicks(in, Math.min(theta, StrictMath.PI));
        double sternOn = sternOnTicks(in, StrictMath.PI - Math.min(theta, StrictMath.PI));
        if (Double.isNaN(turn) || Double.isNaN(sternOn)) return Result.forward();

        double bias = Math.max(0.0, Math.min(0.9, in.frontBias));
        double costForward = turn + legTicks(in.d, in.vFwd, in.aFwd);
        double costReverse = (sternOn + legTicks(in.d, in.vBack, in.aBack)) * (1.0 - bias);
        double ratio = in.latched ? LEAVE_RATIO : ENTER_RATIO;
        Gear gear = costReverse < ratio * costForward ? Gear.REVERSE : Gear.FORWARD;
        return new Result(gear, costForward, costReverse, breakEven(turn, sternOn, in.vFwd, in.vBack));
    }

    /** Leg length at which both gears cost the same; +inf when astern is no slower. */
    public static double breakEven(double turn, double sternOn, double vFwd, double vBack) {
        double slope = 1.0 / vBack - 1.0 / vFwd;
        if (!(slope > 0.0)) return Double.POSITIVE_INFINITY;
        return Math.max(0.0, (turn - sternOn) / slope);
    }

    static double legTicks(double d, double v, double a) {
        double ramp = a > 0.0 && !Double.isInfinite(a) ? v / (2.0 * a) : 0.0;
        return d / v + ramp;
    }

    static double turnTicks(Input in, double angle) {
        return in.tracked ? pivotTicks(in.steeringSpeed, angle) : wheeledTurnTicks(in, angle);
    }

    static double sternOnTicks(Input in, double angle) {
        if (in.tracked) return pivotTicks(in.steeringSpeed, angle);
        if (angle <= 0.0) return 0.0;
        double[] e = WheelTurnModel.segmentEnd(wheelTable(in, Start.REST), angle, true, true);
        return e == null ? Double.NaN : e[3];
    }

    /**
     * A wheeled hull turning round: forward, astern, forward at full lock, keys alternating, the
     * bow swinging the same way throughout, each segment a third of the turn but never under the
     * planner's own segment floor. An estimate of what {@link ManeuverPlanner} would plan.
     */
    static double wheeledTurnTicks(Input in, double angle) {
        if (angle <= 0.0) return 0.0;
        double sweep = Math.max(ManeuverPlanner.SWEEP_MIN, angle / 3.0);
        double[] first = WheelTurnModel.segmentEnd(wheelTable(in, Start.REST), sweep, false, true);
        Table switched = wheelTable(in, Start.OPPOSITE);
        double[] second = WheelTurnModel.segmentEnd(switched, sweep, true, true);
        double[] third = WheelTurnModel.segmentEnd(switched, sweep, false, true);
        if (first == null || second == null || third == null) return Double.NaN;
        return first[3] + second[3] + third[3];
    }

    /** Ticks for a tracked hull pivoting in place through {@code angle} radians. */
    public static double pivotTicks(double s, double angle) {
        if (!(s > 0.0) || Double.isInfinite(s)) return Double.NaN;
        if (angle <= 0.0) return 0.0;
        double deg = StrictMath.toDegrees(angle);
        double[] cum = PIVOT_TABLES.computeIfAbsent(Double.doubleToLongBits(s), k -> pivotTable(s));
        int n = cum.length - 1;
        if (deg <= cum[n]) {
            for (int i = 1; i <= n; i++) {
                if (cum[i] >= deg) return i - 1 + (deg - cum[i - 1]) / (cum[i] - cum[i - 1]);
            }
        }
        double rate = cum[n] - cum[n - 1];
        return rate > 1.0E-9 ? n + (deg - cum[n]) / rate : Double.NaN;
    }

    /** Settled pivot rate the model predicts, degrees per tick; for comparing against a measurement. */
    public static double pivotSteadyDegPerTick(double s) {
        if (!(s > 0.0) || Double.isInfinite(s)) return Double.NaN;
        double[] cum = PIVOT_TABLES.computeIfAbsent(Double.doubleToLongBits(s), k -> pivotTable(s));
        return cum[cum.length - 1] - cum[cum.length - 2];
    }

    private static double[] pivotTable(double s) {
        double[] cum = new double[PIVOT_TABLE_TICKS + 1];
        double rot = 0.0;
        for (int k = 1; k <= PIVOT_TABLE_TICKS; k++) {
            rot = (rot + s * 0.12 * Math.min(k, 10)) * PIVOT_DECAY;
            cum[k] = cum[k - 1] + PIVOT_YAW_GAIN_DEG * rot;
        }
        return cum;
    }

    private static Table wheelTable(Input in, Start start) {
        double v = Math.max(ManeuverPlanner.PLAN_SPEED_MIN, Math.min(ManeuverPlanner.PLAN_SPEED_MAX, in.planSpeed));
        long sq = StrictMath.round(in.steeringSpeed * 1.0E4);
        long vq = StrictMath.round(v * 100.0);
        long key = (sq << 16) ^ (vq << 4) ^ (in.fluid ? 8L : 0L) ^ start.ordinal();
        double qs = sq / 1.0E4;
        double qv = vq / 100.0;
        return WHEEL_TABLES.computeIfAbsent(key, k -> WheelTurnModel.table(qs, qv, in.fluid, start));
    }
}
