package com.neoalive.tacz_sewv.heli.guidance;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * The FreeNav procedures needed to fly at all (Phase 2): hover/rappel hold, transit, takeoff,
 * land, park, and the safety hold used when a reference goes stale. DetNav attack procedures
 * arrive in Phase 3.
 *
 * <p>Every position a procedure takes from the {@link Situation} passes through a
 * {@link Prefilter}, so a 20 Hz step in a parameter (a refreshed terrain altitude, a moved hold
 * point) never steps the reference.
 */
public final class Procedures {

    private static final double H = HeliPhysics.H;
    /** Prefilter corner for hold points and altitudes, rad/s. */
    private static final double FILTER_W = 0.8;
    /** Transit cruise speed as a fraction of the speed limit (the data-driven value comes in Phase 3). */
    private static final double CRUISE_FRACTION = 0.7;
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

    private Procedures() {}

    public static HeliProcedure create(ProcedureId id, Airframe af) {
        return switch (id) {
            case SAFETY_HOLD -> new Hold(af, Hold.Mode.SAFETY);
            case HOVER_HOLD -> new Hold(af, Hold.Mode.HOLD);
            case RAPPEL_HOLD -> new Hold(af, Hold.Mode.RAPPEL);
            case TRANSIT -> new Transit(af);
            case TAKEOFF -> new Takeoff(af);
            case LAND -> new Land(af);
            case PARK -> new Park();
        };
    }

    /** Nose heading of {@code s} as a vanilla yaw in radians. */
    static double heading(HeliState s) {
        Vector3d f = s.q.transform(new Vector3d(0, 0, 1));
        return StrictMath.atan2(-f.x, f.z);
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
     * supplies (terrain-relative, prefiltered). The path speed is a 1-D follower: accelerate to
     * cruise, brake so as to arrive at rest (s'' limited, s' continuous, so the reference is C1).
     * A moving destination is re-planned from the current reference sample, at most once a second.
     */
    static class Transit implements HeliProcedure {
        private final Airframe af;
        private final Prefilter alt = new Prefilter();
        private DubinsPlanar.Path path;
        private double s, sd, sdd, time = Double.NaN, planTime;
        private double planDestX, planDestZ;
        private final double[] out = new double[5];

        Transit(Airframe af) {
            this.af = af;
        }

        double cruise() {
            return CRUISE_FRACTION * af.vMaxH;
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
            this.path = DubinsPlanar.shortest(x, z, hx, hz, dx, dz, ex, ez, r);
            this.s = 0.0;
            this.sd = Math.max(0.0, vx * hx + vz * hz);
            this.sdd = 0.0;
            this.time = t;
            this.planTime = t;
            this.planDestX = dx;
            this.planDestZ = dz;
        }

        private void advance(double t) {
            while (time + 0.5 * H < t) {
                double brake = Math.sqrt(2.0 * PATH_ACCEL * Math.max(0.0, path.length() - s));
                double want = Math.min(cruise(), brake);
                sdd = Math.max(-PATH_ACCEL, Math.min(PATH_ACCEL, (want - sd) / 0.5));
                sd = Math.max(0.0, sd + H * sdd);
                s = Math.min(path.length(), s + H * sd);
                time += H;
            }
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
            advance(t);
            path.sample(s, out);
            alt.at(t, legAltitude(sit), FILTER_W, H, -af.vDescent, af.vClimb, af.aLatMax);
            double dx = out[2], dz = out[3], k = out[4];
            Vector3d p = new Vector3d(out[0], alt.value(), out[1]);
            Vector3d v = new Vector3d(dx * sd, alt.rate(), dz * sd);
            // a = d s'' + kappa s'^2 n_L(d), n_L(d) = (d.z, -d.x)
            Vector3d a = new Vector3d(dx * sdd + k * sd * sd * dz, alt.accel(), dz * sdd - k * sd * sd * dx);
            return new HeliReference(t, p, v, a, StrictMath.atan2(-dx, dz), -k * sd);
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
        public boolean isComplete(HeliState st, Situation sit, double t) {
            double dx = destX(sit) - st.p.x, dz = destZ(sit) - st.p.z;
            return dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS && st.v.x * st.v.x + st.v.z * st.v.z < 1.0;
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
}
