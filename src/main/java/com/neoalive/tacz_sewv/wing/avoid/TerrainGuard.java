package com.neoalive.tacz_sewv.wing.avoid;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.wing.guidance.WingReference;
import com.neoalive.tacz_sewv.wing.guidance.WingSituation;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.Propulsion;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * Terrain protection between guidance and control, in two layers that fail differently:
 *
 * <ul>
 * <li><b>Floor bias</b> (climb gradient): for every ground sample ahead at distance {@code d}, the
 *     altitude needed <i>now</i> is {@code ground + CLEARANCE - d tan(gammaC)}, where gammaC is a
 *     conservative climb angle this airframe can hold at cruise (half its excess thrust). So the
 *     climb starts exactly as early as the airframe needs for that ridge, no earlier and no later; a
 *     fixed "clear the next N seconds" floor is either too late for a weak climber or needlessly
 *     high for a strong one. It is what normally keeps the aircraft off ridges.</li>
 * <li><b>Recovery</b> (predictive, Auto-GCAS style): when the current trajectory, or the pull-up
 *     out of it, would meet the ground within the reaction window, the reference is replaced by a
 *     wings-level maximum climb along the current track until the ground ahead is clear again. This
 *     is the layer that saves a dive gone deep or a climb that cannot out-climb a cliff.</li>
 * </ul>
 * A plane cannot stop, so there is no "hold off the obstacle" here as in the helicopter barrier:
 * up is the only safe direction, and it is a cost-free one away from a ceiling. Pure; ground
 * heights come from the snapshot.
 */
public final class TerrainGuard {

    /** Height kept above the highest ground on the look-ahead, m. */
    public static final double CLEARANCE = 20.0;
    /** Recovery margin above the ground at the bottom of the predicted pull-up, m. */
    private static final double MARGIN = 6.0;
    /**
     * Reaction before the pull is effective, s, and the fraction of the sustainable load factor the
     * prediction assumes. Honest rather than heroic: the controller builds load through the short
     * period inside its rate limits, so an instant full-g pull is not what happens.
     */
    private static final double REACTION = 1.0, PULL_FRACTION = 0.6;
    /** Recovery climb angle, rad. */
    private static final double RECOVERY_GAMMA = StrictMath.toRadians(20.0);

    private final WingAirframe af;
    private final double g, vStall;
    /** tan of the climb angle the floor bias plans with. */
    private final double climbGradient;
    private boolean recovering;

    public TerrainGuard(WingAirframe af, double g, double rho) {
        this.af = af;
        this.g = g;
        this.vStall = AeroModel.stallSpeed(af, g, rho, 0.0);
        double v = af.cruiseSpeed, qs = 0.5 * rho * v * v * af.area, weight = af.mass * g;
        double alpha = (weight / qs - af.cl0) / af.clAlpha;
        double drag = qs * AeroModel.cd(af, alpha, 0.0, 0.0, 0.0, false);
        double sinClimb = Math.max(0.05, Math.min(0.35, 0.5 * (Propulsion.maxThrust(af, v) - drag) / weight));
        this.climbGradient = StrictMath.tan(StrictMath.asin(sinClimb));
    }

    public boolean recovering() {
        return recovering;
    }

    /**
     * Height lost pulling out of the current descent: the sink over the reaction time plus the
     * circular-arc loss at a sustainable recovery load factor. Shared with the attack runs, whose
     * exit test adds it to their clearance floor, so a run always ends on its own before the guard
     * has to take over.
     */
    public static double pullOutDrop(WingAirframe af, double g, double speed, double vy) {
        double v = Math.max(speed, 1.0);
        double gamma = StrictMath.asin(Math.max(-1.0, Math.min(1.0, vy / v)));
        double n = Math.max(1.5, PULL_FRACTION * Math.min(af.nMax, 4.0));
        double arc = v * v / (g * (n - 1.0));
        return Math.max(0.0, -vy) * REACTION + (gamma < 0.0 ? arc * (1.0 - StrictMath.cos(gamma)) : 0.0);
    }

    /** Highest ground within {@code range} metres ahead, or -infinity if no sample covers it. */
    static double floorWithin(WingSituation sit, double range) {
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < sit.floor.length; i++) {
            if ((i + 1) * sit.floorStep > range + sit.floorStep) break;
            best = Math.max(best, sit.floor[i]);
        }
        return best;
    }

    /** The reference the controller should actually fly. Null passes through. */
    public WingReference apply(WingReference r, WingState s, WingSituation sit) {
        if (r == null || sit == null || sit.floor.length == 0) {
            recovering = false;
            return r;
        }
        double v = Math.max(s.v.length(), 1.0);
        double vh = Math.max(StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z), 1.0);
        double gamma = StrictMath.asin(Math.max(-1.0, Math.min(1.0, s.v.y / v)));

        // One prediction for both failure modes: pull out after the reaction time along a circular
        // arc at a sustainable recovery load factor, then fly level (or keep climbing, if climbing).
        // Danger is ground ahead coming within MARGIN of that path. A straight-line projection of a
        // dive is wrong: every ordinary gun dive is "on a collision course" four seconds out, and the
        // guard yanked the nose up the moment the boresight closed on the target.
        double n = Math.max(1.5, PULL_FRACTION * Math.min(af.nMax, 4.0));
        double arc = v * v / (g * (n - 1.0));
        double drop = pullOutDrop(af, g, v, s.v.y);
        double pullRange = vh * REACTION + (gamma < 0.0 ? arc * StrictMath.sin(-gamma) : 0.0);
        double look = Math.max(pullRange, vh * af.tRecover);
        boolean danger = false;
        for (int i = 0; i < sit.floor.length; i++) {
            double d = (i + 1) * sit.floorStep;
            if (d > look + sit.floorStep) break;
            double yAt = s.p.y - drop + Math.max(0.0, s.v.y) * (d / vh);
            if (yAt < sit.floor[i] + MARGIN) {
                danger = true;
                break;
            }
        }
        if (!danger && !Double.isInfinite(sit.groundBelow) && s.p.y - drop < sit.groundBelow + MARGIN) danger = true;
        double clearAhead = floorWithin(sit, look) + CLEARANCE;
        if (danger) {
            recovering = true;
        } else if (recovering && gamma >= 0.0 && s.p.y > clearAhead) {
            recovering = false;
        }

        if (recovering) {
            double ux = s.v.x / vh, uz = s.v.z / vh;
            double speed = Math.max(v, 1.3 * vStall);
            double ch = speed * StrictMath.cos(RECOVERY_GAMMA);
            return WingReference.flypath(r.t(), new Vector3d(s.p.x, Math.max(s.p.y, clearAhead) + 200.0, s.p.z),
                    new Vector3d(ux * ch, speed * StrictMath.sin(RECOVERY_GAMMA), uz * ch), new Vector3d());
        }

        double floor = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < sit.floor.length; i++) {
            floor = Math.max(floor, sit.floor[i] + CLEARANCE - (i + 1) * sit.floorStep * climbGradient);
        }
        if (r.mode() == WingReference.Mode.FLYPATH && r.p().y() < floor) {
            return new WingReference(r.t(), new Vector3d(r.p().x(), floor, r.p().z()), r.v(), r.a(), r.mode(), r.aim());
        }
        return r;
    }
}
