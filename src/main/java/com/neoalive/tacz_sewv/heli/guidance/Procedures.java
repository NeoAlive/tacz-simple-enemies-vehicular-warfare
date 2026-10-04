package com.neoalive.tacz_sewv.heli.guidance;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Every procedure (plan sections 4.8 and 4.10). FreeNav: hover/rappel hold, transit, patrol,
 * evade, takeoff, land, park and the safety hold used when a reference goes stale. DetNav (attack):
 * FireStill, FireLoop (with its out-of-band approach) and FireRun with its break/reposition exit.
 *
 * <p>Every position a procedure takes from the {@link Situation} passes through a filter
 * ({@link Prefilter} for parameters, {@link TargetTrack} for the target), so a 20 Hz step in a
 * parameter (a refreshed terrain altitude, a moved hold point, a new target snapshot) never steps
 * the reference.
 */
public final class Procedures {

    private static final double H = HeliPhysics.H;
    /** Prefilter corner for hold points, altitudes and station errors, rad/s. */
    private static final double FILTER_W = 0.8;
    /** Path-speed acceleration and braking, m/s^2. */
    private static final double PATH_ACCEL = 3.0;
    /** Horizontal arrival radius and speed for a transit to count as complete. */
    public static final double ARRIVE_RADIUS = 4.0;
    /** Below this height above touchdown the landing sink halves. */
    private static final double FINAL_FLARE = 3.0;
    /** Landing sink, m/s: SBW's 0.12 blocks/tick capture speed. */
    private static final double LAND_SINK = 2.4;
    /** Inside this horizontal range a landing stops transiting and descends. */
    public static final double LAND_DESCEND_RADIUS = 24.0;
    private static final double ALT_DEADBAND = 2.5;
    /** FireStill: line of sight lost for this long ends the station (an aspect change is FireLoop's job). */
    private static final double LOS_LOST_LIMIT = 2.0;
    private static final double RHO = Airframe.RHO0;

    private Procedures() {}

    public static HeliProcedure create(ProcedureId id, Airframe af, double g) {
        return switch (id) {
            case SAFETY_HOLD -> new Hold(af, Hold.Mode.SAFETY);
            case HOVER_HOLD -> new Hold(af, Hold.Mode.HOLD);
            case RAPPEL_HOLD -> new Hold(af, Hold.Mode.RAPPEL);
            case TRANSIT -> new Transit(af);
            case TAKEOFF -> new Takeoff(af);
            case LAND -> new Land(af);
            case PARK -> new Park();
            case PATROL -> new Patrol(af);
            case EVADE -> new Evade(af);
            case FIRE_STILL -> new FireStill(af, g);
            case FIRE_LOOP -> new FireLoop(af, g);
            case FIRE_RUN -> new FireRun(af, g);
            case AUTOROTATE -> new Autorotate(af, g);
        };
    }

    /**
     * A rotorcraft's planning radius (Phase 6). A plane turns at its cruise speed because it cannot
     * slow below stall, so its radius is V^2 / a_lat at cruise; a helicopter slows into the turn, so
     * its radius is set at {@code turnSpeed} and the path follower brakes for it (the speed profile).
     */
    static double turnRadius(Airframe af) {
        // The nose must follow the track too: a turn at speed V on radius R needs yaw rate V / R.
        return Math.max(Math.max(af.minTurnRadius, af.turnSpeed * af.turnSpeed / af.aLatMax),
                af.turnSpeed / (YAW_MARGIN * af.yawRateMax));
    }

    /** Fraction of the yaw-rate and lateral-acceleration limits a planned path may use. */
    static final double YAW_MARGIN = 0.8, LAT_MARGIN = 0.85;

    /** Nose heading of {@code s} as a vanilla yaw in radians. */
    static double heading(HeliState s) {
        Vector3d f = s.q.transform(new Vector3d(0, 0, 1));
        return StrictMath.atan2(-f.x, f.z);
    }

    /** Horizontal travel direction: the velocity when moving, else the nose. {x, z}, unit. */
    static double[] travelDir(HeliState s) {
        double sp = Math.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
        if (sp > 2.0) return new double[] {s.v.x / sp, s.v.z / sp};
        double yaw = heading(s);
        return new double[] {-StrictMath.sin(yaw), StrictMath.cos(yaw)};
    }

    static double wrapPi(double a) {
        double r = StrictMath.IEEEremainder(a, 2.0 * Math.PI);
        return r <= -Math.PI ? r + 2.0 * Math.PI : r;
    }

    /** Yaw and yaw rate of the line of sight from {@code p} (moving at {@code v}) to {@code tp} (moving at {@code tv}). */
    static double[] lineOfSight(Vector3d p, Vector3d v, Vector3d tp, Vector3d tv, double fallbackYaw) {
        double dx = tp.x - p.x, dz = tp.z - p.z, r2 = dx * dx + dz * dz;
        if (r2 < 1.0) return new double[] {fallbackYaw, 0.0};
        double ddx = tv.x - v.x, ddz = tv.z - v.z;
        return new double[] {StrictMath.atan2(-dx, dz), (-dz * ddx + dx * ddz) / r2};
    }

    /** Three prefilters following a point, with the hull's limits as rate bounds. */
    static final class PointFilter {
        final Prefilter x = new Prefilter(), y = new Prefilter(), z = new Prefilter();
        private final Airframe af;

        PointFilter(Airframe af) {
            this.af = af;
        }

        void reset(Vector3d p, Vector3d v) {
            x.reset(p.x, v.x);
            y.reset(p.y, v.y);
            z.reset(p.z, v.z);
        }

        HeliReference at(double t, double tx, double ty, double tz, double yaw, double yawRate) {
            x.at(t, tx, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            y.at(t, ty, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            z.at(t, tz, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            return new HeliReference(t, new Vector3d(x.value(), y.value(), z.value()),
                    new Vector3d(x.rate(), y.rate(), z.rate()), new Vector3d(x.accel(), y.accel(), z.accel()),
                    yaw, yawRate);
        }
    }

    /**
     * Arc-length follower on a Dubins path: a 1-D speed profile (s'' limited, s' continuous, so the
     * reference is C1). With no {@link #next} it brakes to arrive at rest at the end; with one it
     * carries the overshoot onto the next path, which must start on this one's end pose.
     */
    static final class PathFollower {
        /** Jerk limit on a commanded path acceleration, m/s^3. */
        static final double JERK = 8.0;
        /**
         * Lateral acceleration the speed profile respects (Phase 6): on an arc of radius R the path
         * speed is held to sqrt(a_lat R), and ahead of one it brakes along sqrt(v_arc^2 + 2 a_lat d).
         * Infinite = no profile (constant-radius planning). {@code endLimit} is the speed the NEXT path
         * may be entered at (it starts with an arc of the planning radius).
         */
        double aLat = Double.POSITIVE_INFINITY, endLimit = Double.POSITIVE_INFINITY;
        /** Yaw rate the nose can follow on an arc (v = r R), rad/s; infinite = no limit. */
        double yawRate = Double.POSITIVE_INFINITY;

        /** Speed-profile limits for this airframe: lateral acceleration and the nose's yaw rate, with margins. */
        void limits(Airframe af) {
            aLat = af.aLatMax;
            yawRate = YAW_MARGIN * af.yawRateMax;
        }
        DubinsPlanar.Path path;
        Supplier<DubinsPlanar.Path> next;
        double s, sd, sdd, time = Double.NaN;
        int legs;
        final double[] out = new double[5];

        void start(DubinsPlanar.Path p, double speed, double t) {
            path = p;
            next = null;
            s = 0.0;
            sd = Math.max(0.0, speed);
            sdd = 0.0;
            time = t;
            legs = 0;
        }

        /** Continue on a new path from its start, keeping speed, acceleration and clock. */
        void swap(DubinsPlanar.Path p) {
            path = p;
            next = null;
            s = 0.0;
            legs = 0;
        }

        void advance(double t, double cruise) {
            advance(t, cruise, Double.NaN, Double.POSITIVE_INFINITY);
        }

        /**
         * The path speed allowed at arc length {@code at}: the current arc's sqrt(a_lat R), every later
         * arc's braking envelope, and the path end (rest, or the next path's entry speed).
         */
        double speedLimit(double at) {
            double lim = next == null ? Math.sqrt(2.0 * PATH_ACCEL * Math.max(0.0, path.length() - at))
                    : Math.sqrt(endLimit * endLimit + 2.0 * aLat * Math.max(0.0, path.length() - at));
            if (aLat == Double.POSITIVE_INFINITY) return lim;
            double start = 0.0;
            for (DubinsPlanar.Segment seg : path.segments()) {
                double end = start + seg.length();
                if (seg instanceof DubinsPlanar.Arc arc && end > at && seg.length() > 1e-9) {
                    double v = Math.min(Math.sqrt(aLat * arc.r()), yawRate * arc.r());
                    lim = Math.min(lim, start <= at ? v : Math.sqrt(v * v + 2.0 * aLat * (start - at)));
                }
                start = end;
            }
            return lim;
        }

        /** As {@link #advance(double, double)}, but with path acceleration {@code accel} (when not NaN) up to speed {@code cap}. */
        void advance(double t, double cruise, double accel, double cap) {
            while (time + 0.5 * H < t) {
                double want = Math.min(cruise, speedLimit(s));
                double brake = aLat == Double.POSITIVE_INFINITY ? PATH_ACCEL : aLat;
                sdd = Double.isNaN(accel) ? Math.max(-brake, Math.min(PATH_ACCEL, (want - sd) / 0.5))
                        : sdd + Math.max(-JERK * H, Math.min(JERK * H, accel - sdd)); // a commanded a: jerk-limited, or the nose overshoots
                sd = Math.min(cap, Math.max(0.0, sd + H * sdd));
                s = s + H * sd;
                if (s >= path.length()) {
                    DubinsPlanar.Path n = next == null ? null : next.get();
                    if (n != null) {
                        s -= path.length();
                        path = n;
                        legs++;
                    } else {
                        s = path.length();
                    }
                }
                time += H;
            }
        }

        /** The reference at the follower's current point, with altitude from {@code alt}. */
        HeliReference sample(double t, Prefilter alt) {
            path.sample(s, out);
            double dx = out[2], dz = out[3], k = out[4];
            Vector3d p = new Vector3d(out[0], alt.value(), out[1]);
            Vector3d v = new Vector3d(dx * sd, alt.rate(), dz * sd);
            // a = d s'' + kappa s'^2 n_L(d), n_L(d) = (d.z, -d.x)
            Vector3d a = new Vector3d(dx * sdd + k * sd * sd * dz, alt.accel(), dz * sdd - k * sd * sd * dx);
            return new HeliReference(t, p, v, a, StrictMath.atan2(-dx, dz), -k * sd);
        }
    }

    static DubinsPlanar.Path line(double x, double z, double dx, double dz, double length) {
        return new DubinsPlanar.Path(List.of(new DubinsPlanar.Line(x, z, dx, dz, length)), length, "S");
    }

    // --- Hold: hover / rappel station / safety ------------------------------------------------

    /**
     * Hover at a point: a stationary (or slowly moving, for a followed leader) trajectory the
     * controller tracks, its integrator absorbing wind and drag. This replaces SBW's hoverMode: it
     * is a controller mode, not a damping term. Nose on the target when there is one, else held.
     */
    static final class Hold implements HeliProcedure {
        enum Mode { HOLD, RAPPEL, SAFETY }

        private final Mode mode;
        private final PointFilter f;
        private double yawHold;
        private final Vector3d fixed = new Vector3d();

        Hold(Airframe af, Mode mode) {
            this.mode = mode;
            this.f = new PointFilter(af);
        }

        @Override
        public ProcedureId id() {
            return switch (mode) {
                case HOLD -> ProcedureId.HOVER_HOLD;
                case RAPPEL -> ProcedureId.RAPPEL_HOLD;
                case SAFETY -> ProcedureId.SAFETY_HOLD;
            };
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            f.reset(s.p, s.v);
            yawHold = heading(s);
            fixed.set(s.p);
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            double tx, ty, tz;
            switch (mode) {
                case RAPPEL -> {
                    tx = sit.lockX;
                    ty = sit.rappelY;
                    tz = sit.lockZ;
                }
                case SAFETY -> {
                    tx = fixed.x;
                    ty = fixed.y;
                    tz = fixed.z;
                }
                default -> {
                    tx = sit.holdX;
                    ty = sit.holdY;
                    tz = sit.holdZ;
                }
            }
            double yaw = yawHold;
            if (mode != Mode.SAFETY && sit.targetValid) {
                double dx = sit.targetX - f.x.value(), dz = sit.targetZ - f.z.value();
                if (dx * dx + dz * dz > 1.0) yaw = StrictMath.atan2(-dx, dz);
            }
            return f.at(t, tx, ty, tz, yaw, 0.0);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return false;
        }
    }

    // --- Transit ---------------------------------------------------------------------------------

    /**
     * Fly to the destination along a Dubins path at cruise speed, at the leg altitude the goal
     * supplies (terrain-relative, prefiltered), braking to arrive at rest. A moving destination is
     * re-planned from the current reference sample, at most once a second.
     */
    static class Transit implements HeliProcedure {
        final Airframe af;
        private final Prefilter alt = new Prefilter();
        private final PathFollower f = new PathFollower();
        private double planTime, planDestX, planDestZ;

        Transit(Airframe af) {
            this.af = af;
        }

        double cruise() {
            return af.cruiseSpeed;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.TRANSIT;
        }

        @Override
        public void begin(HeliState st, Situation sit, double t) {
            plan(st.p.x, st.p.z, st.v.x, st.v.z, heading(st), destX(sit), destZ(sit), t);
            alt.reset(st.p.y, st.v.y);
        }

        private void plan(double x, double z, double vx, double vz, double yaw, double dx, double dz, double t) {
            double speed = Math.sqrt(vx * vx + vz * vz);
            double hx, hz;
            if (speed > 2.0) {
                hx = vx / speed;
                hz = vz / speed;
            } else {
                hx = -StrictMath.sin(yaw);
                hz = StrictMath.cos(yaw);
            }
            double bx = dx - x, bz = dz - z, bl = Math.sqrt(bx * bx + bz * bz);
            double ex = bl > 1e-6 ? bx / bl : hx, ez = bl > 1e-6 ? bz / bl : hz;
            f.limits(af);
            f.start(DubinsPlanar.shortest(x, z, hx, hz, dx, dz, ex, ez, turnRadius(af)), Math.max(0.0, vx * hx + vz * hz), t);
            this.planTime = t;
            this.planDestX = dx;
            this.planDestZ = dz;
        }

        @Override
        public HeliReference refAt(double t, HeliState st, Situation sit) {
            double moved = StrictMath.hypot(destX(sit) - planDestX, destZ(sit) - planDestZ);
            double dist = StrictMath.hypot(destX(sit) - st.p.x, destZ(sit) - st.p.z);
            if (moved > Math.max(4.0, 0.1 * dist) && t - planTime >= 1.0) {
                HeliReference r = sample(t, sit);
                plan(r.p().x(), r.p().z(), r.v().x(), r.v().z(), r.yaw(), destX(sit), destZ(sit), t);
            }
            return sample(t, sit);
        }

        private HeliReference sample(double t, Situation sit) {
            f.advance(t, cruise());
            alt.at(t, legAltitude(sit), FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            return f.sample(t, alt);
        }

        double legAltitude(Situation sit) {
            return sit.destY;
        }

        double destX(Situation sit) {
            return sit.destX;
        }

        double destZ(Situation sit) {
            return sit.destZ;
        }

        @Override
        public double[] lookahead() {
            return new double[] {planDestX, planDestZ};
        }

        @Override
        public boolean isComplete(HeliState st, Situation sit, double t) {
            double dx = destX(sit) - st.p.x, dz = destZ(sit) - st.p.z;
            return dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS && st.v.x * st.v.x + st.v.z * st.v.z < 1.0;
        }
    }

    /** Evade (plan O7): a transit to {@code evadeDistance} straight away from the target, at cruise altitude. */
    static final class Evade extends Transit {
        private double ex, ez;

        Evade(Airframe af) {
            super(af);
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.EVADE;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            return sit.targetValid;
        }

        @Override
        public void begin(HeliState st, Situation sit, double t) {
            double ax = st.p.x - sit.targetX, az = st.p.z - sit.targetZ, l = Math.sqrt(ax * ax + az * az);
            if (l < 1.0) {
                double[] d = travelDir(st);
                ax = -d[0];
                az = -d[1];
                l = 1.0;
            }
            ex = st.p.x + af.evadeDistance * ax / l;
            ez = st.p.z + af.evadeDistance * az / l;
            super.begin(st, sit, t);
        }

        @Override
        double destX(Situation sit) {
            return ex;
        }

        @Override
        double destZ(Situation sit) {
            return ez;
        }

        @Override
        double legAltitude(Situation sit) {
            return sit.cruiseY;
        }
    }

    // --- Patrol ----------------------------------------------------------------------------------

    /**
     * RU/US free patrol: a closed loop of four nodes round the anchor, drawn from the hull's
     * persistent seed (theta_0 first, then r_k and the jitter j_k per node, in that order), each
     * flown with the bisector heading, joined by Dubins legs at R_p = max(R_min, V^2/a_lat). Entry is a
     * Dubins leg from the current pose to the nearest node ahead. Terrain-relative cruise altitude.
     * Never completes.
     */
    static final class Patrol implements HeliProcedure {
        static final int NODES = 4;
        private final Airframe af;
        private final PathFollower f = new PathFollower();
        private final Prefilter alt = new Prefilter();
        final double[][] node = new double[NODES][2], head = new double[NODES][2];
        final DubinsPlanar.Path[] legs = new DubinsPlanar.Path[NODES];
        private int at;

        Patrol(Airframe af) {
            this.af = af;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.PATROL;
        }

        /** Build the loop round (ax, az) from {@code seed}; pure, so D4 can compare two builds. */
        void build(double ax, double az, long seed) {
            SplittableRandom rng = new SplittableRandom(seed);
            double theta0 = rng.nextDouble() * 2.0 * Math.PI;
            for (int k = 0; k < NODES; k++) {
                double r = af.patrolRMin + rng.nextDouble() * (af.patrolRMax - af.patrolRMin);
                double j = (2.0 * rng.nextDouble() - 1.0) * Math.PI / (2.0 * NODES);
                double th = theta0 + 2.0 * Math.PI * k / NODES + j;
                node[k][0] = ax + r * StrictMath.sin(th);
                node[k][1] = az + r * StrictMath.cos(th);
            }
            for (int k = 0; k < NODES; k++) {
                double[] a = node[(k + NODES - 1) % NODES], b = node[k], c = node[(k + 1) % NODES];
                double ix = b[0] - a[0], iz = b[1] - a[1], il = Math.sqrt(ix * ix + iz * iz);
                double ox = c[0] - b[0], oz = c[1] - b[1], ol = Math.sqrt(ox * ox + oz * oz);
                double hx = ix / il + ox / ol, hz = iz / il + oz / ol, hl = Math.sqrt(hx * hx + hz * hz);
                head[k][0] = hx / hl;
                head[k][1] = hz / hl;
            }
            double r = radius();
            for (int k = 0; k < NODES; k++) {
                int n = (k + 1) % NODES;
                legs[k] = DubinsPlanar.shortest(node[k][0], node[k][1], head[k][0], head[k][1],
                        node[n][0], node[n][1], head[n][0], head[n][1], r);
            }
        }

        double radius() {
            return turnRadius(af);
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            f.limits(af);
            f.endLimit = af.turnSpeed;
            build(Double.isNaN(sit.anchorX) ? s.p.x : sit.anchorX, Double.isNaN(sit.anchorZ) ? s.p.z : sit.anchorZ, sit.seed);
            double[] d = travelDir(s);
            int best = -1, nearest = 0;
            double bestD = Double.MAX_VALUE, nearD = Double.MAX_VALUE;
            for (int k = 0; k < NODES; k++) {
                double dx = node[k][0] - s.p.x, dz = node[k][1] - s.p.z, dd = dx * dx + dz * dz;
                if (dd < nearD) {
                    nearD = dd;
                    nearest = k;
                }
                if (dx * d[0] + dz * d[1] > 0.0 && dd < bestD) {
                    bestD = dd;
                    best = k;
                }
            }
            at = best >= 0 ? best : nearest;
            f.start(DubinsPlanar.shortest(s.p.x, s.p.z, d[0], d[1], node[at][0], node[at][1], head[at][0], head[at][1],
                    radius()), Math.max(0.0, s.v.x * d[0] + s.v.z * d[1]), t);
            f.next = () -> {
                DubinsPlanar.Path leg = legs[at];
                at = (at + 1) % NODES;
                return leg;
            };
            alt.reset(s.p.y, s.v.y);
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            f.advance(t, af.cruiseSpeed);
            alt.at(t, sit.cruiseY, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            return f.sample(t, alt);
        }

        @Override
        public double[] lookahead() {
            return node[at].clone();
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return false;
        }
    }

    // --- Takeoff ---------------------------------------------------------------------------------

    /**
     * Start the engine, wait on the ground until it runs, then climb straight up to
     * {@code climbTo} along a quintic in height (rest to rest). The ground is not a barrier
     * obstacle until the hull is clear of it.
     */
    static final class Takeoff implements HeliProcedure {
        private final Airframe af;
        private double x0, y0, z0, yaw0, tClimb = Double.NaN, target;
        private boolean clear;

        Takeoff(Airframe af) {
            this.af = af;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.TAKEOFF;
        }

        @Override
        public HeliControl.EngineCmd engine() {
            return HeliControl.EngineCmd.START;
        }

        @Override
        public boolean groundBarrier(double t) {
            return clear;
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            x0 = s.p.x;
            y0 = s.p.y;
            z0 = s.p.z;
            yaw0 = heading(s);
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            Vector3d zero = new Vector3d();
            if (Double.isNaN(tClimb)) {
                if (s.engine != HeliState.Engine.RUN) {
                    return new HeliReference(t, new Vector3d(x0, y0, z0), zero, zero, yaw0, 0.0);
                }
                tClimb = t;
                target = Math.max(sit.climbTo, y0 + 5.0);
            }
            double dy = target - y0;
            double dur = Math.max(1.875 * dy / af.vClimb, Math.sqrt(5.77 * dy / PATH_ACCEL));
            double u = Math.min(1.0, (t - tClimb) / dur);
            double w = u * u * u * (10 - 15 * u + 6 * u * u);
            double wd = 30 * u * u * (1 - u) * (1 - u) / dur, wdd = 60 * u * (1 - u) * (1 - 2 * u) / (dur * dur);
            if (y0 + dy * w > y0 + 6.0) clear = true;
            return new HeliReference(t, new Vector3d(x0, y0 + dy * w, z0), new Vector3d(0, dy * wd, 0),
                    new Vector3d(0, dy * wdd, 0), yaw0, 0.0);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return !Double.isNaN(tClimb) && s.p.y >= target - ALT_DEADBAND;
        }
    }

    // --- Land ------------------------------------------------------------------------------------

    /**
     * Transit to the pad at the run-in altitude, then descend over its centre at the capture sink,
     * halved over the last few metres. The settle decision (grounded within the settle radius) stays
     * with the pilot goal, which then orders PARK. The ground stops being a barrier obstacle once the
     * descent begins.
     */
    static final class Land implements HeliProcedure {
        private final Airframe af;
        private final Transit leg;
        private final PointFilter f;
        private boolean descending;
        private double yaw;

        Land(Airframe af) {
            this.af = af;
            this.f = new PointFilter(af);
            this.leg = new Transit(af) {
                @Override
                double legAltitude(Situation sit) {
                    return sit.transitY;
                }

                @Override
                double destX(Situation sit) {
                    return sit.padX;
                }

                @Override
                double destZ(Situation sit) {
                    return sit.padZ;
                }
            };
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.LAND;
        }

        @Override
        public boolean groundBarrier(double t) {
            return !descending;
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            descending = StrictMath.hypot(sit.padX - s.p.x, sit.padZ - s.p.z) <= LAND_DESCEND_RADIUS;
            if (descending) {
                f.reset(s.p, s.v);
                yaw = heading(s);
            } else {
                leg.begin(s, sit, t);
            }
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            if (!descending) {
                HeliReference r = leg.refAt(t, s, sit);
                if (StrictMath.hypot(sit.padX - r.p().x(), sit.padZ - r.p().z()) > LAND_DESCEND_RADIUS) return r;
                descending = true;
                f.reset(new Vector3d(r.p()), new Vector3d(r.v()));
                yaw = r.yaw();
            }
            double above = f.y.value() - sit.touchdownY;
            double sink = above > FINAL_FLARE ? LAND_SINK : 0.5 * LAND_SINK;
            f.x.at(t, sit.padX, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            f.y.at(t, sit.touchdownY - 0.5, FILTER_W, H, -sink, af.vClimb, af.aLatMax);
            f.z.at(t, sit.padZ, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            return new HeliReference(t, new Vector3d(f.x.value(), f.y.value(), f.z.value()),
                    new Vector3d(f.x.rate(), f.y.rate(), f.z.rate()),
                    new Vector3d(f.x.accel(), f.y.accel(), f.z.accel()), yaw, 0.0);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return false;
        }
    }

    // --- Park ------------------------------------------------------------------------------------

    /** On the ground, engine off, controller let go. The hull spins down and rests. */
    static final class Park implements HeliProcedure {
        private final Vector3d at = new Vector3d();
        private double yaw;

        @Override
        public ProcedureId id() {
            return ProcedureId.PARK;
        }

        @Override
        public HeliControl.EngineCmd engine() {
            return HeliControl.EngineCmd.STOP;
        }

        @Override
        public boolean groundBarrier(double t) {
            return false;
        }

        @Override
        public boolean flying() {
            return false;
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            at.set(s.p);
            yaw = heading(s);
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            Vector3d zero = new Vector3d();
            return new HeliReference(t, new Vector3d(at), zero, zero, yaw, 0.0);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return false;
        }
    }

    // --- Autorotate -------------------------------------------------------------------------------

    /** Minimum-jerk quintic from (p0, v0, a0) to (p1, v1, a1) over T, sampled at tau: {p, v, a}. */
    static double[] quintic(double p0, double v0, double a0, double p1, double v1, double a1, double T, double tau) {
        double h = p1 - p0, T2 = T * T, T3 = T2 * T;
        double c3 = (20 * h - (8 * v1 + 12 * v0) * T - (3 * a0 - a1) * T2) / (2 * T3);
        double c4 = (-30 * h + (14 * v1 + 16 * v0) * T + (3 * a0 - 2 * a1) * T2) / (2 * T3 * T);
        double c5 = (12 * h - 6 * (v1 + v0) * T + (a1 - a0) * T2) / (2 * T3 * T2);
        double t2 = tau * tau, t3 = t2 * tau, t4 = t3 * tau, t5 = t4 * tau;
        return new double[] {
                p0 + v0 * tau + 0.5 * a0 * t2 + c3 * t3 + c4 * t4 + c5 * t5,
                v0 + a0 * tau + 3 * c3 * t2 + 4 * c4 * t3 + 5 * c5 * t4,
                a0 + 6 * c3 * tau + 12 * c4 * t2 + 20 * c5 * t3};
    }

    /**
     * Engine out in the air (fuel exhausted, engine failed; plan 4.6 item 11 and O4). Above the
     * flare height ({@link #flareHeight}: the table's, raised when the sink outruns it): an autorotative glide straight ahead at full forward speed, with no vertical
     * demand (the controller's autorotation law owns collective there and holds rotor speed). At
     * the flare height: a boundary-value flare, height along the minimum-jerk quintic from the
     * current (y, v_y) to (ground, -1.5 m/s) over T_f = 2 h / (|v_y| + 1.5), at least 2 s, forward
     * speed held through the first half and bled to half in the second (a run-on touchdown), tracked
     * by the normal collective inversion, spending rotor energy. The same profile P11 proves.
     *
     * <p>Asks for an engine start the whole way down: the physics refuses one with no fuel or a
     * failed engine, and if one is possible (an airborne hull whose engine was simply stopped) the
     * selector hands back to normal flight as soon as it runs. Never completes.
     */
    static final class Autorotate implements HeliProcedure {
        private static final double TOUCH_SINK = 1.5, FLARE_MARGIN = 2.0;
        private final Airframe af;
        private final double g;
        private double dx, dz, t0 = Double.NaN, tf, y0, vy0, ground, x0, z0, v0;

        Autorotate(Airframe af, double g) {
            this.af = af;
            this.g = g;
        }

        /**
         * Height the flare needs: the table's flare height, or more when the hull is sinking faster than
         * that height can arrest. An engine cut low down (cruise is 30-50 m above ground) reaches the
         * table height still accelerating, well above the steady autorotative sink the table assumes:
         * h = 2 v^2 / (2 a), a = T_max(Omega) / m - g, the deceleration the rotor can give at its
         * present speed; the factor 2 covers the quintic flare's peak deceleration, 1.875 x its mean.
         */
        double flareHeight(HeliState s) {
            double omegaR = s.omega * af.radius;
            double tMax = Airframe.RHO0 * af.area * omegaR * omegaR * com.neoalive.tacz_sewv.heli.physics.RotorModel.maxCt(af);
            double a = Math.max(0.5, tMax / af.mass - g), sink = Math.max(0.0, -s.v.y);
            return Math.max(af.flareHeight, FLARE_MARGIN * sink * sink / (2.0 * a));
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.AUTOROTATE;
        }

        @Override
        public HeliControl.EngineCmd engine() {
            return HeliControl.EngineCmd.START;
        }

        @Override
        public boolean groundBarrier(double t) {
            return false; // the ground is where this ends
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            double[] d = travelDir(s);
            dx = d[0];
            dz = d[1];
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            Vector3d zero = new Vector3d();
            if (Double.isNaN(t0)) {
                double hr = s.p.y + af.cgHeight + af.hubHeight - sit.groundBelow;
                if (hr > flareHeight(s)) {
                    return new HeliReference(t, new Vector3d(s.p).add(dx, 0, dz),
                            new Vector3d(dx * af.vMaxH, s.v.y, dz * af.vMaxH), zero, StrictMath.atan2(-dx, dz), 0.0);
                }
                t0 = t;
                y0 = s.p.y;
                vy0 = s.v.y;
                x0 = s.p.x;
                z0 = s.p.z;
                v0 = Math.max(0.0, s.v.x * dx + s.v.z * dz);
                ground = sit.groundBelow;
                tf = Math.max(2.0, 2.0 * (y0 - ground) / (Math.abs(vy0) + TOUCH_SINK));
            }
            double yaw = StrictMath.atan2(-dx, dz), tau = t - t0;
            if (tau > tf) {
                double extra = tau - tf, along = runOn(tf) + 0.5 * v0 * extra;
                return new HeliReference(t, new Vector3d(x0 + dx * along, ground - TOUCH_SINK * extra, z0 + dz * along),
                        new Vector3d(dx * 0.5 * v0, -TOUCH_SINK, dz * 0.5 * v0), zero, yaw, 0.0);
            }
            double[] y = quintic(y0, vy0, 0, ground, -TOUCH_SINK, 0, tf, tau);
            double half = tf / 2, along = runOn(tau), sp = v0, ac = 0.0;
            if (tau > half) {
                double[] q = quintic(0, v0, 0, 0, 0.5 * v0, 0, half, tau - half);
                sp = q[1];
                ac = q[2];
            }
            return new HeliReference(t, new Vector3d(x0 + dx * along, y[0], z0 + dz * along),
                    new Vector3d(dx * sp, y[1], dz * sp), new Vector3d(dx * ac, y[2], dz * ac), yaw, 0.0);
        }

        /** Distance along the glide after tau s of flare: constant speed, then the quintic bleed to half. */
        private double runOn(double tau) {
            double half = tf / 2;
            if (tau <= half) return v0 * tau;
            double s = Math.min(1.0, (tau - half) / half), s4 = s * s * s * s;
            double iw = half * (s4 * 2.5 - 3 * s4 * s + s4 * s * s);
            return v0 * half + v0 * Math.min(tau - half, half) - 0.5 * v0 * iw;
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return false;
        }
    }

    // --- FireStill (DetNav) --------------------------------------------------------------------

    /**
     * Hover at a standoff station and hold the nose on the target (plan 4.10): S = T_h + d_s u, u the
     * bearing from target to hull at begin, d_s from the yaw and lag envelopes and the standoff
     * floor, the altitude from the elevation solve. All three are solved once, at begin, and held
     * as an offset from the target: the station moves with the (tracked) target and with nothing
     * else, so the snapshot's per-tick terrain reads (cruise altitude under the hull, groundRef)
     * cannot walk it. The hull's offset from it at begin decays through rate- and
     * acceleration-limited filters (the capture), after which the reference is still. Complete on
     * target loss past the ghost, line of sight lost for two seconds, or the dwell.
     */
    static final class FireStill implements HeliProcedure {
        private final Airframe af;
        private final double g;
        private final TargetTrack track = new TargetTrack();
        private final Prefilter ex = new Prefilter(), ez = new Prefilter(), alt = new Prefilter();
        private double ux, uz, standoff, rise, t0, yaw, losLostAt = Double.NaN;
        private double[] held;

        FireStill(Airframe af, double g) {
            this.af = af;
            this.g = g;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.FIRE_STILL;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            return sit.targetValid && Envelope.windOk(af, g, RHO, 0.0) && Envelope.yawOk(af, g, RHO, sit, s.p.x, s.p.z);
        }

        /** The station (bearing, standoff, rise) the previous FireStill held against this target. */
        void hint(double[] station) {
            held = station;
        }

        double[] station() {
            return new double[] {ux, uz, standoff, rise};
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            track.update(sit);
            Vector3d tp = track.p(t), tv = track.v(t);
            double ax = s.p.x - tp.x, az = s.p.z - tp.z, l = Math.sqrt(ax * ax + az * az);
            if (l < 1.0) {
                double[] d = travelDir(s);
                ax = -d[0];
                az = -d[1];
                l = 1.0;
            }
            if (held != null) {
                // A re-begin after the dwell against the same target keeps the station it held: a fresh
                // solve from wherever the hull stands (within its hold error) would nudge it each time.
                ux = held[0];
                uz = held[1];
                standoff = held[2];
                rise = held[3];
            } else {
                ux = ax / l;
                uz = az / l;
                double[] st = Envelope.station(af, sit, Envelope.baseStandoff(af, g, RHO, sit, s.p.x, s.p.z));
                rise = st[1] - tp.y;
                // d_s = max(d_yaw, d_lag, heliMinStandoff, 2 |y_s - y_t|): at most 1:2 drop to range
                // (26.6 deg below the nose), so the boresight sits inside the fire cone on station.
                standoff = Math.max(st[0], 2.0 * Math.abs(rise));
            }
            if (held != null) {
                // Continue the held station exactly: the previous FireStill's reference was already on
                // it, so starting from the hull (within its hold error) would only start a new capture.
                ex.reset(0.0, 0.0);
                ez.reset(0.0, 0.0);
                alt.reset(tp.y + rise, tv.y);
            } else {
                ex.reset(s.p.x - (tp.x + standoff * ux), s.v.x - tv.x);
                ez.reset(s.p.z - (tp.z + standoff * uz), s.v.z - tv.z);
                alt.reset(s.p.y, s.v.y);
            }
            yaw = heading(s);
            t0 = t;
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            track.update(sit);
            if (sit.targetValid && !sit.targetLos) {
                if (Double.isNaN(losLostAt)) losLostAt = sit.time;
            } else if (sit.targetLos) {
                losLostAt = Double.NaN;
            }
            Vector3d tp = track.p(t), tv = track.v(t), ta = track.a(t);
            ex.at(t, 0.0, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            ez.at(t, 0.0, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            alt.at(t, tp.y + rise, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            Vector3d p = new Vector3d(tp.x + standoff * ux + ex.value(), alt.value(), tp.z + standoff * uz + ez.value());
            Vector3d v = new Vector3d(tv.x + ex.rate(), alt.rate(), tv.z + ez.rate());
            Vector3d a = new Vector3d(ta.x + ex.accel(), alt.accel(), ta.z + ez.accel());
            double[] los = lineOfSight(p, v, tp, tv, yaw);
            yaw = los[0];
            return new HeliReference(t, p, v, a, los[0], los[1]);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return track.lost() || (!Double.isNaN(losLostAt) && sit.time - losLostAt > LOS_LOST_LIMIT)
                    || t - t0 >= af.stillDwell;
        }

        @Override
        public boolean fireWindow(HeliState s, Situation sit) {
            return Envelope.inCone(af, s.p.y, sit.targetY, StrictMath.hypot(sit.targetX - s.p.x, sit.targetZ - s.p.z), sit.fireCone);
        }

        @Override
        public boolean retarget(Situation sit, double t) {
            return true;
        }
    }

    // --- FireLoop (DetNav) ---------------------------------------------------------------------

    /** Orbit radius: the FireStill standoff without the wind rule, from the target's own crossing motion. */
    public static double loopRadius(Airframe af, double g, Situation sit, double hullX, double hullZ) {
        return Envelope.station(af, sit, Envelope.baseStandoff(af, g, RHO, sit, hullX, hullZ, false))[0];
    }

    /**
     * Orbit the target at R_o in the pilot's parity sense, nose on the centre (a pedal turn flown
     * sideways), at the orbit speed V_o of {@link Envelope#orbitSpeed} (plan 4.10, plus its sideslip term):
     * <pre>
     *   theta(tau) = theta_0 + s V_o tau / R_o,   e = (sin theta, 0, cos theta),  e' = (cos theta, 0, -sin theta)
     *   p = c + r e + y,   v = c' + r' e + r theta' e',   a = c'' + r'' e + 2 r' theta' e' - r theta'^2 e
     *   psi = pi - theta,  psi' = -theta'
     * </pre>
     * c is the tracked target. The radial error at begin (inside the half-radius entry band) decays
     * through a limited filter, r = R_o + rho(t), so the entry is a C1 spiral rather than a blend
     * absorbing a step. Complete after a full lap, {@code loopTime}, or target loss.
     */
    static final class FireLoop implements HeliProcedure {
        private final Airframe af;
        private final double g;
        private final TargetTrack track = new TargetTrack();
        private final Prefilter rho = new Prefilter(), alt = new Prefilter();
        private double radius, rise, thDot, theta0, t0;
        int sense;

        FireLoop(Airframe af, double g) {
            this.af = af;
            this.g = g;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.FIRE_LOOP;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            if (!sit.targetValid) return false;
            double r = loopRadius(af, g, sit, s.p.x, s.p.z);
            double d = StrictMath.hypot(s.p.x - sit.targetX, s.p.z - sit.targetZ);
            return Math.abs(d - r) <= 0.5 * r;
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            track.update(sit);
            Vector3d c = track.p(t), cv = track.v(t);
            sense = Parity.side(sit.pilotId);
            radius = loopRadius(af, g, sit, s.p.x, s.p.z);
            rise = Envelope.station(af, sit, radius)[1] - c.y;
            thDot = sense * Envelope.orbitSpeed(af, g, RHO, radius, sit.fireCone, StrictMath.hypot(cv.x, cv.z)) / radius;
            double rx = s.p.x - c.x, rz = s.p.z - c.z, d = Math.sqrt(rx * rx + rz * rz);
            theta0 = StrictMath.atan2(rx, rz);
            double vr = d > 1e-6 ? ((s.v.x - cv.x) * rx + (s.v.z - cv.z) * rz) / d : 0.0;
            rho.reset(d - radius, vr);
            alt.reset(s.p.y, s.v.y);
            t0 = t;
        }

        double radius() {
            return radius;
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            track.update(sit);
            Vector3d c = track.p(t), cv = track.v(t), ca = track.a(t);
            rho.at(t, 0.0, FILTER_W, H, -af.vMaxH, af.vMaxH, af.aLatMax);
            alt.at(t, c.y + rise, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            double th = theta0 + thDot * (t - t0);
            double sin = StrictMath.sin(th), cos = StrictMath.cos(th);
            double r = radius + rho.value(), rd = rho.rate(), rdd = rho.accel();
            Vector3d p = new Vector3d(c.x + r * sin, alt.value(), c.z + r * cos);
            Vector3d v = new Vector3d(cv.x + rd * sin + r * thDot * cos, alt.rate(), cv.z + rd * cos - r * thDot * sin);
            double rad = rdd - r * thDot * thDot, tan = 2.0 * rd * thDot;
            Vector3d a = new Vector3d(ca.x + rad * sin + tan * cos, alt.accel(), ca.z + rad * cos - tan * sin);
            return new HeliReference(t, p, v, a, wrapPi(Math.PI - th), -thDot);
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return Math.abs(thDot * (t - t0)) >= 2.0 * Math.PI || t - t0 >= af.loopTime || track.lost();
        }

        @Override
        public boolean fireWindow(HeliState s, Situation sit) {
            return Envelope.inCone(af, s.p.y, sit.targetY, StrictMath.hypot(sit.targetX - s.p.x, sit.targetZ - s.p.z), sit.fireCone);
        }

        @Override
        public boolean retarget(Situation sit, double t) {
            return true;
        }
    }

    /**
     * FireLoop's fallback outside its entry band (plan 4.11): a transit to the orbit's tangent point
     * in the pilot's sense, theta_Q = phi + s acos(R_o/d) (radial when inside the circle), so the hull
     * arrives already flying the orbit's direction. Carries the attack precedence; completes on
     * entering the band, which hands over to FireLoop. Never counts as an engagement.
     */
    static final class LoopApproach extends Transit {
        private final double g;
        private double radius, offX, offZ;

        LoopApproach(Airframe af, double g) {
            super(af);
            this.g = g;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            return sit.targetValid;
        }

        @Override
        public void begin(HeliState st, Situation sit, double t) {
            radius = loopRadius(af, g, sit, st.p.x, st.p.z);
            double rx = st.p.x - sit.targetX, rz = st.p.z - sit.targetZ, d = Math.sqrt(rx * rx + rz * rz);
            double phi = StrictMath.atan2(rx, rz);
            double q = d > radius ? phi + Parity.side(sit.pilotId) * StrictMath.acos(radius / d) : phi;
            offX = radius * StrictMath.sin(q);
            offZ = radius * StrictMath.cos(q);
            super.begin(st, sit, t);
        }

        @Override
        double destX(Situation sit) {
            return sit.targetX + offX;
        }

        @Override
        double destZ(Situation sit) {
            return sit.targetZ + offZ;
        }

        @Override
        double legAltitude(Situation sit) {
            return Envelope.station(af, sit, radius)[1];
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            double d = StrictMath.hypot(s.p.x - sit.targetX, s.p.z - sit.targetZ);
            return !sit.targetValid || Math.abs(d - radius) <= 0.5 * radius;
        }

        @Override
        public boolean retarget(Situation sit, double t) {
            return true; // the destination is read off the live target; the transit re-plans itself
        }
    }

    // --- FireRun (DetNav) ----------------------------------------------------------------------

    /**
     * A strafing pass (plan 4.10). Axis e = the bearing to the target at begin, or the axis the last
     * pass's exit handed down. Run start P_s = T_h - L_in e. If the hull is off the axis (heading > 15
     * deg or cross-track > 3 m) a Dubins ingress to (P_s, e) is prepended. Then the run leg along e at
     * the run speed and the run altitude max(groundRef + 34, y_t + 12).
     *
     * <p><b>Target frame</b> (Phase 6). The whole path lives in the target's moving frame, as
     * FireLoop's orbit does: the follower's coordinates are offsets from the tracked target, and the
     * reference is p = p_t + P(s), v = v_t + P'(s) s', a = a_t + P''s'^2 + P's''. Every clearance in
     * this class (break range, escape arc, overfly) is therefore a clearance from the target itself,
     * moving or not, and a moving target never drives the hull into it. The path speed is capped so
     * the ground speed |v_t + P' s'| stays within vMaxH.
     *
     * <p>The fire window is placed by the fire cone ({@link #closeRange}, {@link #openRange}): the
     * run is level, so the target's depression below the nose grows as the range closes, and the
     * window must end where it leaves the cone. It also closes on overfly past T + 8, a depleted
     * weapon, the pull-up floor, or the target lost past the ghost. Closing it flies the exit: a Dubins break/reposition to
     * (P_s', e'), e' = R_Y(s reattack) e, BREAK on its first arc and REPOSITION after, at cruise
     * altitude; the procedure completes at the end of it and hands e' to the next pass. A pre-empted
     * run never flies the exit.
     */
    static final class FireRun implements HeliProcedure {
        static final double BREAK_RANGE = 14.0, OVERFLY_MARGIN = 8.0, PULLUP_FLOOR = 18.0, PULLUP_LEAD = 0.5;
        /** Seconds the fire window should stay open on a pass. */
        static final double WINDOW_TARGET = 3.0;
        /** The window ends this far inside the fire cone's edge (nose trim and tracking error). */
        static final double CONE_MARGIN = StrictMath.toRadians(5.0);
        static final double RUN_AGL = 34.0, MIN_OVER_TARGET = 12.0, T_ALIGN = 3.0, ALIGN_XTRACK = 10.0;
        /** A bunt's alignment time on the run line, and how far above its run altitude it flies between passes. */
        static final double T_ALIGN_BUNT = 1.5, BUNT_EXIT_CLIMB = 5.0;
        /** Off the run axis by more than this, the pass is no longer a run at this target: break and re-plan. */
        static final double AXIS_LOST = StrictMath.toRadians(30.0);
        /** Seconds before the window the bunt starts pitching, so the nose is on the target as it opens. */
        static final double PRE_PITCH = 1.0;
        /** The window opens only with the nose within this of the target's bearing. */
        static final double ALIGN_YAW = StrictMath.toRadians(5.0);
        static final double ALIGN_YAW_RATE = 0.1;
        // Already on the run axis within ALIGN_XTRACK / 25 deg: start on the line (the live line re-aims through the target).
        static final double ALIGN_COS = StrictMath.cos(StrictMath.toRadians(25.0));
        /** Bunt run: height over the ground round the target and over the target, ingress speed below vMaxH, and the depression it opens at. */
        static final double BUNT_AGL = 15.0, BUNT_OVER_TARGET = 8.0, BUNT_HEADROOM = 10.0;
        static final double BUNT_OPEN = StrictMath.toRadians(6.0);
        /** Closest a bunt's break turn may carry the hull to the target, m. */
        static final double BREAK_CLEAR = 30.0;
        /** A bunt's pull-up floor above the ground under the hull. */
        static final double BUNT_PULLUP = 9.0;
        /** Straight tail appended to every path, so the reference never runs out of road. */
        private static final double TAIL = 1000.0;

        private final Airframe af;
        private final double g;
        private final TargetTrack track = new TargetTrack();
        private final PathFollower f = new PathFollower();
        private final Prefilter alt = new Prefilter();
        /** A nose-aimed weapon: the run is a low accelerating "bunt" that pitches the nose onto the target. */
        private boolean bunt;
        private double ex, ez, nextEx = Double.NaN, nextEz = Double.NaN, hintEx = Double.NaN, hintEz;
        private double lin, breakArc;
        /** Run altitude and target height from the last snapshot with a live target (latched across a loss). */
        private double runAlt = Double.NaN, targetY = Double.NaN;
        private boolean onLine, done;
        private int sense;
        private double lastSteer = Double.NaN;
        /** The bunt pitch has started ahead of the window (the nose takes ~a second to come down). */
        private boolean prePitch;
        /** This sub-step's cap on the path (target-frame) speed from vMaxH on the ground. */
        private double relCap = Double.POSITIVE_INFINITY;
        /** Weapon ballistics from the snapshot, for the lead point. */
        private double projSpeed, projGravity, aimDy;
        FirePhase phase = FirePhase.INGRESS;

        FireRun(Airframe af, double g) {
            this.af = af;
            this.g = g;
        }

        @Override
        public ProcedureId id() {
            return ProcedureId.FIRE_RUN;
        }

        /** The axis the previous pass's exit handed down. */
        void hint(double[] axis) {
            if (axis != null && !Double.isNaN(axis[0])) {
                hintEx = axis[0];
                hintEz = axis[1];
            }
        }

        double[] exitAxis() {
            return new double[] {nextEx, nextEz};
        }

        /**
         * max(groundRef + 34, y_t + 12), latched while the target is live. A lost target's snapshot
         * fields are zero, and reading them made the run climb to absolute Y 34 mid-pass (on a world
         * whose ground is far from Y 0 that was tens of metres; seen in a live trace).
         */
        double runAltitude(Situation sit) {
            if (sit.targetValid) {
                runAlt = bunt ? Math.max(sit.groundRef + BUNT_AGL, sit.targetY + BUNT_OVER_TARGET)
                        : Math.max(sit.groundRef + RUN_AGL, sit.targetY + MIN_OVER_TARGET);
                targetY = sit.targetY;
            }
            return runAlt;
        }

        double radius() {
            return turnRadius(af);
        }

        /**
         * The break turn's radius. A bunt breaks at up to vMaxH and turns at that speed: braking first
         * to the rotorcraft turn speed costs ~a straight braking run TOWARD the target, which for the
         * 30 m clearance gives the same break range (worked through in the Phase 6 plan's review), so
         * the break keeps its speed and its radius vMaxH^2 / a_lat.
         */
        double breakRadius() {
            // Flown at LAT_MARGIN of a_lat, so the hull's lag does not eat the clearance.
            return bunt ? Math.max(af.minTurnRadius, af.vMaxH * af.vMaxH / (LAT_MARGIN * af.aLatMax)) : radius();
        }

        /** The lead point for the held weapon, seen from {@code from}. */
        Vector3d aim(Vector3d from, double t) {
            Vector3d tp = track.p(t).add(0.0, aimDy, 0.0);
            return Envelope.aimPoint(from, tp, track.v(t), projSpeed, projGravity);
        }

        /**
         * The run line follows the target (Phase 6): on the line it is re-anchored at the current
         * reference point toward the lead point, the heading turning no faster than a_lat / s', so the
         * path stays one a helicopter can fly and the reference stays continuous. Once per sub-step.
         */
        private void steer(double t) {
            if (!Double.isNaN(lastSteer) && t < lastSteer + 0.5 * H) return;
            double dt = Double.isNaN(lastSteer) ? H : t - lastSteer;
            lastSteer = t;
            f.path.sample(f.s, f.out);
            Vector3d tp = track.p(t);
            // The lead point relative to the target (the frame origin) and the reference point in that frame.
            Vector3d l = aim(new Vector3d(tp.x + f.out[0], alt.value(), tp.z + f.out[1]), t).sub(tp);
            double dx = l.x - f.out[0], dz = l.z - f.out[1], d = Math.sqrt(dx * dx + dz * dz);
            if (d < 1.0) return;
            double want = StrictMath.atan2(ex * dz - ez * dx, ex * dx + ez * dz); // signed, left positive
            double max = Math.min(af.aLatMax / Math.max(f.sd, af.turnSpeed), YAW_MARGIN * af.yawRateMax) * dt;
            double turn = Math.max(-max, Math.min(max, want)), c = StrictMath.cos(turn), sn = StrictMath.sin(turn);
            double nx = ex * c - ez * sn;
            ez = ex * sn + ez * c;
            ex = nx;
            double sd = f.sd;
            f.swap(line(f.out[0], f.out[1], ex, ez, TAIL));
            f.legs = 1; // still on the run line
            f.sd = sd;
        }

        /**
         * Where the window ends: the range at which the target sits at the fire cone's edge below a
         * level nose, h / tan(alpha - 5 deg), never inside the break range. Only alpha: the fire assist
         * judges the PILOT's weapons, which are fixed to the nose (SBW's HUD shoot direction); the
         * airframe's pitch-free cone belongs to a chin turret, which SBW's own crew loop aims and fires. (The plan sized
         * the window from T_w, the time a forward acceleration a_w can hold the nose down; the run leg
         * is flown level and never commands a_w, so a live trace showed the whole window outside the
         * cone and not a single shot in 98 s.)
         */
        double closeRange(Situation sit) {
            // A bunt aims the nose itself, so its limit is the tilt the airframe can hold; and it breaks far
            // enough out that the break turn stays BREAK_CLEAR from the target (a live trace: shot down
            // over a BMP while turning away inside 30 m).
            double eps = bunt ? af.tiltMax : Math.min(StrictMath.toRadians(85.0), sit.fireCone - CONE_MARGIN);
            // Turning at radius R from range d while heading at the target passes it at sqrt(d^2 + R^2) - R.
            double r = breakRadius(), floor = bunt ? StrictMath.sqrt((BREAK_CLEAR + r) * (BREAK_CLEAR + r) - r * r) : BREAK_RANGE;
            return Math.max(floor, (runAltitude(sit) - targetY) / StrictMath.tan(Math.max(eps, 0.05)));
        }

        /**
         * Where the window opens: {@link #WINDOW_TARGET} s of run before it ends, at least
         * heliEngageRadius, within weapon range. A bunt opens where the target is {@link #BUNT_OPEN}
         * below the horizon: shallow enough that the forward acceleration that pitches the nose onto
         * it can be held for a few seconds.
         */
        double openRange(Situation sit) {
            double open = bunt ? (runAltitude(sit) - targetY) / StrictMath.tan(BUNT_OPEN)
                    : closeRange(sit) + af.runSpeed * WINDOW_TARGET;
            return Math.min(sit.weaponRange, Math.max(sit.engageRadius, open));
        }

        @Override
        public boolean retarget(Situation sit, double t) {
            // The run follows the new target (Phase 6): its track absorbs the jump, the live line turns
            // toward it, and a target the pass can no longer serve ends the pass (AXIS_LOST, a break),
            // never a re-planned ingress mid-air. A live trace re-began the run ten times in 70 s on
            // target churn alone and never reached a window.
            readWeapon(sit);
            return true;
        }

        private void readWeapon(Situation sit) {
            projSpeed = sit.projSpeed;
            projGravity = sit.projGravity;
            aimDy = sit.aimDy;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            bunt = sit.noseAim;
            return sit.targetValid && openRange(sit) - closeRange(sit) >= af.runSpeed;
        }

        /** Run-leg speed: a bunt comes in slower, leaving room to accelerate through the window. */
        double runSpeed() {
            return bunt ? Math.min(af.runSpeed, af.vMaxH - BUNT_HEADROOM) : af.runSpeed;
        }

        /**
         * The bunt's path acceleration (plan 4.10's a_w, now commanded): with heading on the target,
         * the nose pitches with the thrust tilt, atan((m a + D) / m g), so a = g tan(eps) - D/m puts
         * it at the target's depression eps. Read off the reference itself, never the hull, so it is
         * a function of time like any other reference.
         */
        double buntAccel(double refY, double refX, double refZ, Vector3d tp, double speed) {
            double r = Math.max(1.0, StrictMath.hypot(tp.x - refX, tp.z - refZ));
            double eps = StrictMath.atan2(Math.max(0.0, refY - tp.y), r);
            double drag = 0.5 * RHO * af.cdaZ * speed * speed / af.mass;
            return g * StrictMath.tan(eps) - drag;
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
            track.update(sit);
            sense = Parity.side(sit.pilotId);
            Vector3d tp = track.p(t);
            if (!Double.isNaN(hintEx)) {
                ex = hintEx;
                ez = hintEz;
            } else {
                double dx = tp.x - s.p.x, dz = tp.z - s.p.z, l = Math.sqrt(dx * dx + dz * dz);
                double[] d = travelDir(s);
                ex = l > 1.0 ? dx / l : d[0];
                ez = l > 1.0 ? dz / l : d[1];
            }
            bunt = sit.noseAim;
            readWeapon(sit);
            f.limits(af);
            Vector3d tv = track.v(t);
            // Run-in: what physics needs and no more (Phase 6; it was ~235 m, the source of the 300 m
            // excursions). A bunt aligns in T_ALIGN_BUNT, and adds time only if it must still descend.
            // Descent time includes the altitude prefilter's settling (critically damped, ~2/w to within 2 m).
            double drop = Math.max(0.0, s.p.y - runAltitude(sit));
            double align = bunt ? Math.max(T_ALIGN_BUNT, drop > 0.5 ? drop / af.vDescent + 2.0 / FILTER_W : 0.0) : T_ALIGN;
            lin = Math.max(openRange(sit) + runSpeed() * align, 2.0 * radius());
            // Everything below is in the target frame: offsets from the target, velocity relative to it.
            double psx = -lin * ex, psz = -lin * ez;
            double rx = s.p.x - tp.x, rz = s.p.z - tp.z, rvx = s.v.x - tv.x, rvz = s.v.z - tv.z;
            double rsp = Math.sqrt(rvx * rvx + rvz * rvz);
            double[] d = rsp > 2.0 ? new double[] {rvx / rsp, rvz / rsp} : travelDir(s);
            double speed = Math.max(0.0, rvx * d[0] + rvz * d[1]);
            double along = rx * ex + rz * ez, cross = Math.abs(rx * ez - rz * ex);
            boolean aligned = d[0] * ex + d[1] * ez >= ALIGN_COS && cross <= ALIGN_XTRACK && along <= -openRange(sit);
            if (aligned) {
                f.start(line(rx, rz, ex, ez, -along + TAIL), speed, t);
                onLine = true;
            } else {
                f.start(DubinsPlanar.shortest(rx, rz, d[0], d[1], psx, psz, ex, ez, radius()), speed, t);
                f.next = () -> line(psx, psz, ex, ez, lin + TAIL);
                onLine = false;
            }
            alt.reset(s.p.y, s.v.y);
            phase = FirePhase.INGRESS;
        }

        @Override
        public HeliReference refAt(double t, HeliState s, Situation sit) {
            track.update(sit);
            if (!done) advancePhase(t, s, sit);
            boolean attacking = phase == FirePhase.INGRESS || phase == FirePhase.ATTACK;
            double speed = attacking ? (onLine ? runSpeed() : af.cruiseSpeed)
                    : (phase == FirePhase.BREAK ? af.runSpeed : af.cruiseSpeed);
            Vector3d tp = track.p(t), tv = track.v(t), ta = track.a(t);
            f.path.sample(f.s, f.out);
            // Ground speed |v_t + P' s'| <= vMaxH: the largest s' along the current direction P'.
            double td = tv.x * f.out[2] + tv.z * f.out[3], t2 = tv.x * tv.x + tv.z * tv.z;
            relCap = Math.max(0.0, -td + Math.sqrt(Math.max(0.0, td * td - t2 + af.vMaxH * af.vMaxH)));
            speed = Math.min(speed, relCap);
            if (bunt && (phase == FirePhase.ATTACK || prePitch)) {
                Vector3d from = new Vector3d(tp.x + f.out[0], alt.value(), tp.z + f.out[1]);
                // The nose pitches with the GROUND acceleration a_t + P' s'', so the path term is what remains
                // after the target's own acceleration along the path.
                double along = ta.x * f.out[2] + ta.z * f.out[3];
                f.advance(t, speed, buntAccel(alt.value(), from.x, from.z, aim(from, t), f.sd) - along, relCap);
            } else {
                f.advance(t, speed);
            }
            if (!onLine && f.legs > 0) onLine = true;
            if (attacking && onLine) steer(t);
            double exitY = bunt ? runAltitude(sit) + BUNT_EXIT_CLIMB : sit.cruiseY; // a bunt stays low between passes
            alt.at(t, attacking ? runAltitude(sit) : exitY, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            HeliReference rel = f.sample(t, alt);
            HeliReference r = new HeliReference(t, new Vector3d(rel.p()).add(tp.x, 0.0, tp.z),
                    new Vector3d(rel.v()).add(tv.x, 0.0, tv.z), new Vector3d(rel.a()).add(ta.x, 0.0, ta.z), rel.yaw(), rel.yawRate());
            if (!(attacking && onLine)) return r;
            // On the run the nose is aimed, not slaved to the track: yaw = line of sight to the lead
            // point (yaw is an independent degree of freedom, plan 4.0/4.6, as FireStill uses it).
            Vector3d p = new Vector3d(r.p()), v = new Vector3d(r.v());
            double[] los = lineOfSight(p, v, aim(p, t), track.v(t), r.yaw());
            return new HeliReference(t, p, v, new Vector3d(r.a()), los[0], los[1]);
        }

        private void advancePhase(double t, HeliState s, Situation sit) {
            switch (phase) {
                case INGRESS, ATTACK -> {
                    Vector3d tp = track.p(t);
                    double rx = s.p.x - tp.x, rz = s.p.z - tp.z, range = Math.sqrt(rx * rx + rz * rz);
                    double along = rx * ex + rz * ez;
                    // A bunt opens only at its run altitude: a hull still descending onto it sees the target too
                    // steep, and the acceleration that would pitch the nose there runs out in a second or two.
                    boolean atHeight = !bunt || Math.abs(alt.value() - runAltitude(sit)) < 3.0;
                    // ...and only with the nose settled on the target: a hull rolling out of the reposition turn
                    // still swings its nose (the plan's alignment rule, now applied to the window itself).
                    boolean nosed = Math.abs(wrapPi(heading(s) - StrictMath.atan2(-(tp.x - s.p.x), tp.z - s.p.z))) < ALIGN_YAW
                            && Math.abs(s.w.y) < ALIGN_YAW_RATE; // settled, not swinging through
                    if (phase == FirePhase.INGRESS && onLine && atHeight && nosed && range <= openRange(sit)) phase = FirePhase.ATTACK;
                    prePitch = bunt && onLine && atHeight && range <= openRange(sit) + f.sd * PRE_PITCH;
                    double floor = bunt ? BUNT_PULLUP : PULLUP_FLOOR; // a bunt flies below the strafe floor by design
                    boolean pullUp = s.p.y - sit.groundBelow <= floor + Math.max(0.0, -s.v.y) * PULLUP_LEAD;
                    // Survivability floor on the whole run, not only the window, and a target the line can no
                    // longer serve (a mover that outran it, a hand-off far off the axis) ends the pass.
                    double cosOff = range > 1.0 ? -(rx * ex + rz * ez) / range : 1.0;
                    boolean tooClose = onLine && range < (bunt ? BREAK_CLEAR : BREAK_RANGE);
                    boolean axisLost = onLine && range > (bunt ? BREAK_CLEAR : BREAK_RANGE) && cosOff < StrictMath.cos(AXIS_LOST);
                    boolean close = track.lost() || (onLine && along > OVERFLY_MARGIN) || (onLine && pullUp) || tooClose || axisLost
                            || (phase == FirePhase.ATTACK && (range <= closeRange(sit) || sit.ammoFrac <= 0.0
                                    || (bunt && f.sd >= relCap - 0.5))); // a bunt can no longer accelerate: the nose comes up
                    if (close) startBreak(t, tp);
                }
                case BREAK -> {
                    if (f.legs > 0 || f.s >= breakArc) phase = FirePhase.REPOSITION;
                }
                case REPOSITION -> {
                    if (f.legs > 1) done = true; // legs: escape arc (0), reposition Dubins (1), run line (2)
                }
                default -> {
                }
            }
        }

        /**
         * The exit (Phase 6). BREAK: a 90 deg escape arc in the pilot's parity sense at the break
         * radius, which by construction of {@link #closeRange} passes the target BREAK_CLEAR off and
         * ends heading across the line of sight. REPOSITION: a Dubins at the rotorcraft turn radius
         * from there to the next run-in point, then its run line. (The first version was one shortest
         * Dubins straight to the next run-in point; with a short run-in that point lies across the
         * target and the path went over it.)
         */
        private void startBreak(double t, Vector3d tp) {
            prePitch = false;
            f.path.sample(f.s, f.out);
            // The next axis turns AGAINST the escape side, so the next run-in point lies on the side the
            // hull escaped to (rotating it the other way put it across the target).
            double a = -sense * af.reattack, cos = StrictMath.cos(a), sin = StrictMath.sin(a);
            nextEx = ex * cos + ez * sin;
            nextEz = -ex * sin + ez * cos;
            double psx = -lin * nextEx, psz = -lin * nextEz; // target frame: the target is the origin
            double px = f.out[0], pz = f.out[1], dx = f.out[2], dz = f.out[3], rb = breakRadius();
            boolean left = sense > 0;
            // Arc centre on the turn side: n_L(d) = (d.z, -d.x) for a left turn.
            double cx = px + (left ? rb : -rb) * dz, cz = pz + (left ? -rb : rb) * dx;
            DubinsPlanar.Arc escape = new DubinsPlanar.Arc(cx, cz, rb, StrictMath.atan2(px - cx, pz - cz), 0.5 * Math.PI, left);
            DubinsPlanar.Path breakPath = new DubinsPlanar.Path(java.util.List.of(escape), escape.length(), left ? "L" : "R");
            double[] end = new double[5];
            escape.sample(escape.length(), end);
            double ax = nextEx, az = nextEz;
            f.swap(breakPath);
            f.endLimit = af.turnSpeed;
            f.next = () -> {
                f.endLimit = Double.POSITIVE_INFINITY;
                f.next = () -> line(psx, psz, ax, az, TAIL);
                return DubinsPlanar.shortest(end[0], end[1], end[2], end[3], psx, psz, ax, az, radius());
            };
            breakArc = escape.length();
            phase = FirePhase.BREAK;
        }

        @Override
        public boolean isComplete(HeliState s, Situation sit, double t) {
            return done;
        }

        @Override
        public FirePhase firePhase() {
            return phase;
        }

        @Override
        public boolean fireWindow(HeliState s, Situation sit) {
            return phase == FirePhase.ATTACK;
        }
    }
}
