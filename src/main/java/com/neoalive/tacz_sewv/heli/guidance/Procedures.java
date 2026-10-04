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
            while (time + 0.5 * H < t) {
                double want = cruise;
                if (next == null) want = Math.min(cruise, Math.sqrt(2.0 * PATH_ACCEL * Math.max(0.0, path.length() - s)));
                sdd = Math.max(-PATH_ACCEL, Math.min(PATH_ACCEL, (want - sd) / 0.5));
                sd = Math.max(0.0, sd + H * sdd);
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
            double v = cruise();
            double r = Math.max(af.minTurnRadius, v * v / af.aLatMax);
            f.start(DubinsPlanar.shortest(x, z, hx, hz, dx, dz, ex, ez, r), Math.max(0.0, vx * hx + vz * hz), t);
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
            return Math.max(af.minTurnRadius, af.cruiseSpeed * af.cruiseSpeed / af.aLatMax);
        }

        @Override
        public void begin(HeliState s, Situation sit, double t) {
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
     * <p>The fire window opens at {@code engageRadius} and closes at the break range, overfly past
     * T + 8, the window time T_w = (V_max - V_run)/a_w, a depleted weapon, the pull-up floor, or the
     * target lost past the ghost. Closing it flies the exit: a Dubins break/reposition to
     * (P_s', e'), e' = R_Y(s reattack) e, BREAK on its first arc and REPOSITION after, at cruise
     * altitude; the procedure completes at the end of it and hands e' to the next pass. A pre-empted
     * run never flies the exit.
     */
    static final class FireRun implements HeliProcedure {
        static final double BREAK_RANGE = 14.0, OVERFLY_MARGIN = 8.0, PULLUP_FLOOR = 18.0, PULLUP_LEAD = 0.5;
        static final double RUN_AGL = 34.0, MIN_OVER_TARGET = 12.0, T_ALIGN = 3.0, ALIGN_XTRACK = 3.0;
        static final double ALIGN_COS = StrictMath.cos(StrictMath.toRadians(15.0));
        /** Straight tail appended to every path, so the reference never runs out of road. */
        private static final double TAIL = 1000.0;

        private final Airframe af;
        private final double g;
        private final TargetTrack track = new TargetTrack();
        private final PathFollower f = new PathFollower();
        private final Prefilter alt = new Prefilter();
        private double ex, ez, nextEx = Double.NaN, nextEz = Double.NaN, hintEx = Double.NaN, hintEz;
        private double lin, windowOpen = Double.NaN, breakArc;
        private boolean onLine, done;
        private int sense;
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

        double runAltitude(Situation sit) {
            return Math.max(sit.groundRef + RUN_AGL, sit.targetY + MIN_OVER_TARGET);
        }

        double radius() {
            return Math.max(af.minTurnRadius, af.runSpeed * af.runSpeed / af.aLatMax);
        }

        /**
         * T_w = (V_max - V_run) / a_w with a_w = g tan(clamp(eps - cone_hi - alpha, 0, gamma_max)) - D(V)/m, at
         * the window's open range; infinite when a_w <= 0. The fire gate only needs the boresight within
         * alpha of the line of sight, so the nose-down the window forces is what is left after the
         * pitch-free cone AND the fire cone (the plan's formula left alpha out, which made every run at
         * the shipped geometry infeasible).
         */
        double windowTime(Situation sit) {
            double eps = StrictMath.atan2(runAltitude(sit) - sit.targetY, Math.max(sit.engageRadius, 1.0));
            double tilt = Math.max(0.0, Math.min(af.tiltMax, eps - af.coneHi - sit.fireCone));
            double drag = 0.5 * RHO * af.cdaZ * af.runSpeed * af.runSpeed / af.mass;
            double aw = g * StrictMath.tan(tilt) - drag;
            return aw <= 0.0 ? Double.POSITIVE_INFINITY : (af.vMaxH - af.runSpeed) / aw;
        }

        @Override
        public boolean canBegin(HeliState s, Situation sit) {
            return sit.targetValid && windowTime(sit) >= 1.0;
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
            lin = Math.max(sit.engageRadius + af.runSpeed * T_ALIGN, 2.0 * af.minTurnRadius);
            double psx = tp.x - lin * ex, psz = tp.z - lin * ez;
            double[] d = travelDir(s);
            double speed = Math.max(0.0, s.v.x * d[0] + s.v.z * d[1]);
            double rx = s.p.x - tp.x, rz = s.p.z - tp.z;
            double along = rx * ex + rz * ez, cross = Math.abs(rx * ez - rz * ex);
            boolean aligned = d[0] * ex + d[1] * ez >= ALIGN_COS && cross <= ALIGN_XTRACK && along <= -sit.engageRadius;
            if (aligned) {
                f.start(line(s.p.x, s.p.z, ex, ez, -along + TAIL), speed, t);
                onLine = true;
            } else {
                f.start(DubinsPlanar.shortest(s.p.x, s.p.z, d[0], d[1], psx, psz, ex, ez, radius()), speed, t);
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
            double speed = attacking ? (onLine ? af.runSpeed : af.cruiseSpeed)
                    : (phase == FirePhase.BREAK ? af.runSpeed : af.cruiseSpeed);
            f.advance(t, speed);
            if (!onLine && f.legs > 0) onLine = true;
            alt.at(t, attacking ? runAltitude(sit) : sit.cruiseY, FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            return f.sample(t, alt);
        }

        private void advancePhase(double t, HeliState s, Situation sit) {
            switch (phase) {
                case INGRESS, ATTACK -> {
                    Vector3d tp = track.p(t);
                    double rx = s.p.x - tp.x, rz = s.p.z - tp.z, range = Math.sqrt(rx * rx + rz * rz);
                    double along = rx * ex + rz * ez;
                    if (phase == FirePhase.INGRESS && onLine && range <= sit.engageRadius) {
                        phase = FirePhase.ATTACK;
                        windowOpen = t;
                    }
                    boolean pullUp = s.p.y - sit.groundBelow <= PULLUP_FLOOR + Math.max(0.0, -s.v.y) * PULLUP_LEAD;
                    boolean close = track.lost() || (onLine && along > OVERFLY_MARGIN) || (onLine && pullUp)
                            || (phase == FirePhase.ATTACK && (range <= BREAK_RANGE || t - windowOpen >= windowTime(sit)
                                    || sit.ammoFrac <= 0.0));
                    if (close) startBreak(t, tp);
                }
                case BREAK -> {
                    if (f.legs > 0 || f.s >= breakArc) phase = FirePhase.REPOSITION;
                }
                case REPOSITION -> {
                    if (f.legs > 0) done = true;
                }
                default -> {
                }
            }
        }

        private void startBreak(double t, Vector3d tp) {
            f.path.sample(f.s, f.out);
            double a = sense * af.reattack, cos = StrictMath.cos(a), sin = StrictMath.sin(a);
            nextEx = ex * cos + ez * sin;
            nextEz = -ex * sin + ez * cos;
            double psx = tp.x - lin * nextEx, psz = tp.z - lin * nextEz;
            DubinsPlanar.Path exit = DubinsPlanar.shortest(f.out[0], f.out[1], f.out[2], f.out[3],
                    psx, psz, nextEx, nextEz, radius());
            f.swap(exit);
            double ax = nextEx, az = nextEz;
            f.next = () -> line(psx, psz, ax, az, TAIL);
            breakArc = exit.segments().get(0).length();
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
