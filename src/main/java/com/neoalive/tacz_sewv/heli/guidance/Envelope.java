package com.neoalive.tacz_sewv.heli.guidance;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/**
 * The attack envelopes of plan section 4.10 as pure functions: the wind ceiling, the yaw-authority
 * and yaw-lag standoffs, the elevation ring and the orbit speed. The pilot goal uses them to fill
 * the selector's summary flags; the procedures use the same functions in {@code canBegin}, so the
 * two can never disagree.
 */
public final class Envelope {

    /** Climb above the cruise altitude a station may ask for, m. */
    public static final double STATION_CLIMB = 20.0;
    /** Station floor above groundRef, m. */
    public static final double STATION_CLEARANCE = 15.0;
    /** Smallest standoff any attack geometry uses, m. */
    public static final double STANDOFF_MIN = 20.0;
    /**
     * The elevation ring aims this far inside the cone's steep edge. On the edge itself the
     * hover's station-keeping error (centimetres) flickers the fire window open and shut.
     */
    public static final double CONE_MARGIN = StrictMath.toRadians(1.5);

    private Envelope() {}

    /** Main-rotor torque the tail must cancel in a hover; zero for a coaxial (no reaction torque). */
    public static double hoverTorque(Airframe af, double g, double rho) {
        return af.coaxial ? 0.0 : RotorModel.hoverPower(af, g, rho) / af.omegaN;
    }

    /**
     * The tail thrust the pedal can command: full pedal gives T_trN, and the T_tr,max clamp only
     * matters when the inflow term adds to it. (Plan 4.10 wrote T_tr,max; with T_trN < T_tr,max in
     * every row that overstated the adverse-side authority.)
     */
    public static double tailAuthority(Airframe af) {
        return Math.min(af.tailNominal, af.tailMax);
    }

    /** tau_av = l_tr min(T_trN, T_tr,max) - Q_hover: yaw torque left in the adverse direction. */
    public static double yawTorqueAvailable(Airframe af, double g, double rho) {
        return af.tailArm * tailAuthority(af) - hoverTorque(af, g, rho);
    }

    /** r_max = min(yawRateMax, tau_av / (c_trd l_tr^2)): the steady yaw rate against tail damping. */
    public static double yawRateMax(Airframe af, double g, double rho) {
        double tau = Math.max(0.0, yawTorqueAvailable(af, g, rho));
        return Math.min(af.yawRateMax, tau / (af.tailDamping * af.tailArm * af.tailArm));
    }

    /**
     * Sideways airspeed the tail can hold the nose against: the tail rotor's inflow term
     * c_trd v_tail eats the anti-torque margin on the adverse side, so
     * v_side,max = (min(T_trN, T_tr,max) - Q_hover / l_tr) / c_trd. A coaxial has no tail rotor in the
     * sideslip stream (its yaw channel is differential torque), so it has no such limit.
     */
    public static double sideslipMax(Airframe af, double g, double rho) {
        if (af.coaxial) return Double.POSITIVE_INFINITY;
        return Math.max(0.0, tailAuthority(af) - hoverTorque(af, g, rho) / af.tailArm) / af.tailDamping;
    }

    /** psi''_max = tau_av / I_y. */
    public static double yawAccelMax(Airframe af, double g, double rho) {
        return Math.max(0.0, yawTorqueAvailable(af, g, rho)) / af.iy;
    }

    /**
     * Wind the hull can hold position against, out of ground effect with no ETL credit:
     * w_ceil = sqrt(2 m g min(sqrt((T_max/mg)^2 - 1), tan gamma_max) / (rho C_DA_w)).
     */
    public static double windCeiling(Airframe af, double g, double rho) {
        double w = af.mass * g;
        double tMax = rho * af.area * af.tipSpeed * af.tipSpeed * RotorModel.maxCt(af);
        double ratio = Math.max(0.0, (tMax / w) * (tMax / w) - 1.0);
        double tan = Math.min(StrictMath.sqrt(ratio), StrictMath.tan(af.tiltMax));
        return StrictMath.sqrt(2.0 * w * tan / (rho * Math.max(af.cdaX, af.cdaZ)));
    }

    /** d_yaw = max(v_perp / (0.8 r_max), a_perp / (0.8 psi''_max)). */
    public static double yawStandoff(Airframe af, double g, double rho, double vPerp, double aPerp) {
        double r = yawRateMax(af, g, rho), acc = yawAccelMax(af, g, rho);
        if (!(r > 0.0) || !(acc > 0.0)) return Double.POSITIVE_INFINITY;
        return Math.max(vPerp / (0.8 * r), aPerp / (0.8 * acc));
    }

    /**
     * d_lag = v_perp (yawLag + 1/omega_yaw) / (0.5 alpha): the steady yaw error of a line-of-sight
     * ramp through the actuator and the yaw loop, kept within half the fire cone. A conservative
     * over-estimate (it ignores the yaw-rate feed-forward); valid because load-time validation keeps
     * omega_yaw <= 1/(3 yawLag).
     */
    public static double lagStandoff(Airframe af, double vPerp, double fireCone) {
        return vPerp * (af.yawLag + 1.0 / af.bwYaw) / (0.5 * fireCone);
    }

    /**
     * Horizontal standoff so a nose depression of {@code maxDepression} (radians) points at a target
     * {@code heightAboveTarget} below, floored at {@code minStandoff}. Target at or above: the floor.
     */
    public static double standoffRing(double heightAboveTarget, double maxDepression, double minStandoff) {
        if (!(minStandoff > 0.0)) minStandoff = 0.0;
        if (!(heightAboveTarget > 0.0)) return minStandoff;
        double tan = StrictMath.tan(maxDepression);
        if (!(tan > 1.0E-6)) return minStandoff;
        return Math.max(minStandoff, heightAboveTarget / tan);
    }

    /** Lateral acceleration a target of this category can pull (data). */
    public static double targetLatAccel(Airframe af, Situation.TargetCategory c) {
        return switch (c) {
            case INFANTRY -> af.latAccInfantry;
            case AIR -> af.latAccAir;
            default -> af.latAccVehicle;
        };
    }

    /** v_perp: the target's horizontal speed relative to the hull, across the line of sight. */
    public static double lateralSpeed(Situation sit, double hullX, double hullZ) {
        return lateralSpeed(sit, hullX, hullZ, true);
    }

    /**
     * v_perp with or without the hull's own motion. An orbit leaves it out: the nose must turn at
     * the orbit's own rate whatever the radius, and the orbit speed's lag term already bounds that;
     * counting it here would size each loop from the previous loop's speed.
     */
    public static double lateralSpeed(Situation sit, double hullX, double hullZ, boolean ownMotion) {
        double lx = sit.targetX - hullX, lz = sit.targetZ - hullZ, l = Math.sqrt(lx * lx + lz * lz);
        if (l < 1.0E-6) return 0.0;
        double rx = sit.targetVx - (ownMotion ? sit.hullVx : 0.0), rz = sit.targetVz - (ownMotion ? sit.hullVz : 0.0);
        return Math.abs(rx * lz - rz * lx) / l;
    }

    /** Standoff before the elevation solve: max(d_yaw, d_lag, floor), clamped to [STANDOFF_MIN, range]. */
    public static double baseStandoff(Airframe af, double g, double rho, Situation sit, double hullX, double hullZ) {
        return baseStandoff(af, g, rho, sit, hullX, hullZ, true);
    }

    public static double baseStandoff(Airframe af, double g, double rho, Situation sit, double hullX, double hullZ,
                                      boolean ownMotion) {
        double vPerp = lateralSpeed(sit, hullX, hullZ, ownMotion);
        double d = Math.max(yawStandoff(af, g, rho, vPerp, targetLatAccel(af, sit.targetCategory)),
                Math.max(lagStandoff(af, vPerp, sit.fireCone), sit.minStandoff));
        return Math.max(STANDOFF_MIN, Math.min(sit.weaponRange, d));
    }

    /**
     * Station altitude and standoff (plan 4.10 iii). Altitude first: aim the middle of the
     * pitch-free cone at the target, y_s = clamp(y_t + d tan(eps_aim), groundRef + 15, y_c + 20).
     * Only when a clamp binds does the elevation ring join the standoff, pushing the hull out until
     * the target sits inside the cone ({@link #CONE_MARGIN} inside its steep edge, never past the aim
     * point). Returns {standoff, altitude}.
     */
    public static double[] station(Airframe af, Situation sit, double standoff) {
        double lo = af.coneLo - sit.fireCone, hi = af.coneHi + sit.fireCone;
        double aim = 0.5 * (lo + hi);
        double floor = sit.groundRef + STATION_CLEARANCE, ceiling = Math.max(floor, sit.cruiseY + STATION_CLIMB);
        double want = sit.targetY + standoff * StrictMath.tan(aim);
        double y = Math.max(floor, Math.min(ceiling, want));
        if (y != want) {
            double ring = standoffRing(y - sit.targetY, Math.max(aim, hi - CONE_MARGIN), 0.0);
            standoff = Math.max(standoff, Math.min(sit.weaponRange, ring));
            y = Math.max(floor, Math.min(ceiling, sit.targetY + standoff * StrictMath.tan(aim)));
        }
        return new double[] {standoff, y};
    }

    /** True when a target at this geometry lies inside the pitch-free cone widened by the fire cone. */
    public static boolean inCone(Airframe af, double hullY, double targetY, double horizontal, double fireCone) {
        double eps = StrictMath.atan2(hullY - targetY, Math.max(horizontal, 1.0E-6));
        return eps >= af.coneLo - fireCone && eps <= af.coneHi + fireCone;
    }

    /**
     * Orbit speed V_o = min(V_orbit, 0.8 r_max R, sqrt(a_lat R), 0.5 alpha R / (yawLag + 1/omega_yaw),
     * 0.8 v_side,max - |v_c|). The fourth term is the lag-aware nose-tracking envelope, the same as
     * d_lag. The last is the sideslip envelope: the orbit is flown sideways (nose on the centre), so
     * its tangential speed, plus whatever the moving centre adds, is sideslip the tail must hold the
     * nose against. Floored at 1 m/s so a hull at the edge of its envelope still goes round.
     */
    public static double orbitSpeed(Airframe af, double g, double rho, double radius, double fireCone, double centreSpeed) {
        double v = Math.min(Math.min(af.orbitSpeed, 0.8 * yawRateMax(af, g, rho) * radius),
                Math.min(StrictMath.sqrt(af.aLatMax * radius),
                        0.5 * fireCone * radius / (af.yawLag + 1.0 / af.bwYaw)));
        return Math.max(1.0, Math.min(v, 0.8 * sideslipMax(af, g, rho) - centreSpeed));
    }

    /** FireStill feasibility: the wind ceiling (|w| <= 0.8 w_ceil) and both yaw standoffs within weapon range. */
    public static boolean windOk(Airframe af, double g, double rho, double wind) {
        return wind <= 0.8 * windCeiling(af, g, rho);
    }

    public static boolean yawOk(Airframe af, double g, double rho, Situation sit, double hullX, double hullZ) {
        double vPerp = lateralSpeed(sit, hullX, hullZ);
        return yawStandoff(af, g, rho, vPerp, targetLatAccel(af, sit.targetCategory)) <= sit.weaponRange
                && lagStandoff(af, vPerp, sit.fireCone) <= sit.weaponRange;
    }
}
