package com.neoalive.tacz_sewv.wing.guidance;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.wing.avoid.TerrainGuard;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * The fixed-wing procedures and their factory. Free navigation is unscheduled (see {@link WingProcedure}):
 * a transit is "fly along the bearing to the point", an orbit is "fly the tangent of the circle at
 * the point of it nearest to you". The controller's L1 law then does the rest, including crabbing
 * into wind, which a heading-based steer cannot.
 */
public final class WingProcedures {

    private WingProcedures() {}

    public static WingProcedure create(WingProcedureId id, WingAirframe af, double g) {
        return switch (id) {
            case TRANSIT -> new Transit(af, false);
            case RTB -> new Transit(af, true);
            case PATROL -> new Patrol(af, g);
            case SAFETY_HOLD -> new SafetyHold(af, g);
            case INGRESS -> new Ingress(af, g);
            case ATTACK_DIVE -> new AttackDive(af, g);
            case ATTACK_LEVEL -> new AttackLevel(af, g, false);
            case ATTACK_MARK -> new AttackLevel(af, g, true);
            case BREAK -> new Break(af, g);
            default -> new Orbit(af, g);
        };
    }

    /**
     * Orbit radius an airframe flies at cruise: the circle its bank and turn-rate limits allow at
     * 85 % of the effective bank (a tight, working orbit, not a lazy one), and never less than its
     * traffic-pattern radius.
     */
    public static double orbitRadius(WingAirframe af, double g) {
        double v = af.cruiseSpeed;
        double bank = Math.min(af.bankMax, StrictMath.atan(af.turnRateMax * v / g));
        return Math.max(af.patternRadius, v * v / (g * StrictMath.tan(0.85 * bank)));
    }

    /** Seconds of flight at run speed a gun/rocket dive needs on its line. */
    private static final double DIVE_SECONDS = 4.0;
    /** Dive angle the entry height is briefed for, rad. */
    private static final double DIVE_ENTRY_ANGLE = StrictMath.toRadians(28.0);
    /** Entry height bounds above the target's ground, m. */
    private static final double DIVE_ENTRY_MIN = 40.0, DIVE_ENTRY_MAX = 140.0;

    /**
     * Engage range for an aimed (gun / rocket / nose-launched) run: never less than
     * {@link #DIVE_SECONDS} at run speed, because the line is where the nose gets onto the target and
     * a fast jet given a fixed bubble spends the whole run rotating. Bombs keep their release range.
     */
    public static double runEngageRange(WingAirframe af, double configured) {
        return Math.max(configured, DIVE_SECONDS * af.runSpeed);
    }

    /** Height above the target's ground a dive of that engage range is entered from. */
    public static double diveEntryAgl(double engageRange) {
        return Math.max(DIVE_ENTRY_MIN, Math.min(DIVE_ENTRY_MAX, StrictMath.tan(DIVE_ENTRY_ANGLE) * engageRange));
    }

    /** Altitude the snapshot asks for, or the current one when it has none. */
    static double altitude(WingState s, WingSituation sit) {
        double y = Double.isNaN(sit.cruiseY) ? s.p.y : sit.cruiseY;
        return Math.min(y, sit.ceilingY);
    }

    /** Fly the bearing from the aircraft to (x, z), at the snapshot's altitude and cruise speed. */
    static WingReference toward(double t, WingState s, double x, double z, double y, double speed) {
        double dx = x - s.p.x, dz = z - s.p.z;
        double d = StrictMath.sqrt(dx * dx + dz * dz);
        double ux, uz;
        if (d > 1.0E-3) {
            ux = dx / d;
            uz = dz / d;
        } else {
            double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
            ux = vh > 1.0E-3 ? s.v.x / vh : 0.0;
            uz = vh > 1.0E-3 ? s.v.z / vh : 1.0;
        }
        return WingReference.flypath(t, new Vector3d(s.p.x, y, s.p.z), new Vector3d(ux * speed, 0.0, uz * speed),
                new Vector3d());
    }

    /**
     * The circle about (cx, cz) of radius r at the point nearest the aircraft: position there,
     * tangent velocity, centripetal acceleration. {@code clockwise} (seen from above) is a right turn.
     */
    static WingReference circle(double t, WingState s, double cx, double cz, double r, double y, double speed,
                                boolean clockwise) {
        double dx = s.p.x - cx, dz = s.p.z - cz;
        double d = StrictMath.sqrt(dx * dx + dz * dz);
        double c = d > 1.0E-3 ? dx / d : 1.0, sn = d > 1.0E-3 ? dz / d : 0.0;
        double dir = clockwise ? 1.0 : -1.0;
        double ac = speed * speed / r;
        return WingReference.flypath(t, new Vector3d(cx + r * c, y, cz + r * sn),
                new Vector3d(-dir * speed * sn, 0.0, dir * speed * c), new Vector3d(-ac * c, 0.0, -ac * sn));
    }

    // --- Procedures -------------------------------------------------------------------------------

    /** Straight to the destination (or, as RTB, to the anchor); RTB then circles it. */
    static final class Transit implements WingProcedure {

        private final WingAirframe af;
        private final boolean home;

        Transit(WingAirframe af, boolean home) {
            this.af = af;
            this.home = home;
        }

        @Override
        public WingProcedureId id() {
            return home ? WingProcedureId.RTB : WingProcedureId.TRANSIT;
        }

        @Override
        public boolean canBegin(WingState s, WingSituation sit) {
            return home ? sit.hasAnchor : sit.hasDestination;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {}

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            double x = home ? sit.anchorX : sit.destX, z = home ? sit.anchorZ : sit.destZ;
            double y = WingProcedures.altitude(s, sit);
            if (home && distance(s, x, z) < WingModeSelector.ARRIVE * sit.orbitRadius) {
                return circle(t, s, x, z, sit.orbitRadius, y, af.cruiseSpeed, sit.clockwise);
            }
            return toward(t, s, x, z, y, af.cruiseSpeed);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            if (home) return false; // RTB holds over the anchor until the leash releases it
            return !sit.hasDestination || distance(s, sit.destX, sit.destZ) < WingModeSelector.ARRIVE * sit.orbitRadius;
        }
    }

    /** Circle the destination when there is one, else the hold centre. Never completes. */
    static final class Orbit implements WingProcedure {

        private final WingAirframe af;

        Orbit(WingAirframe af, double g) {
            this.af = af;
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.ORBIT;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {}

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            double cx = sit.hasDestination ? sit.destX : sit.holdX;
            double cz = sit.hasDestination ? sit.destZ : sit.holdZ;
            return circle(t, s, cx, cz, sit.orbitRadius, WingProcedures.altitude(s, sit), af.cruiseSpeed, sit.clockwise);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            return false;
        }
    }

    /**
     * RU/US with nobody to answer to: legs between waypoints around the hold centre, each chosen
     * by a hash of the pilot and the leg number, so it is varied but reproducible. Never completes.
     */
    static final class Patrol implements WingProcedure {

        private final WingAirframe af;
        private int leg;
        private double wx, wz;

        Patrol(WingAirframe af, double g) {
            this.af = af;
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.PATROL;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {
            leg = 0;
            waypoint(sit);
        }

        private void waypoint(WingSituation sit) {
            long h = mix(Integer.toUnsignedLong(sit.pilotId) << 32 ^ Integer.toUnsignedLong(leg));
            double angle = ((h >>> 11) * 0x1.0p-53) * 2.0 * Math.PI;
            double r = sit.orbitRadius * (1.0 + 1.5 * (((h >>> 3) & 0xFF) / 255.0));
            wx = sit.holdX + r * StrictMath.cos(angle);
            wz = sit.holdZ + r * StrictMath.sin(angle);
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            if (distance(s, wx, wz) < sit.orbitRadius) {
                leg++;
                waypoint(sit);
            }
            return toward(t, s, wx, wz, WingProcedures.altitude(s, sit), af.cruiseSpeed);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            return false;
        }
    }

    /**
     * Guidance went stale (the pilot goal stopped feeding it): circle where we are, no lower than
     * we are, until fresh guidance replaces it. Deliberately needs nothing from the snapshot.
     */
    static final class SafetyHold implements WingProcedure {

        private final WingAirframe af;
        private final double g;
        private double cx, cz, y;

        SafetyHold(WingAirframe af, double g) {
            this.af = af;
            this.g = g;
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.SAFETY_HOLD;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {
            double r = orbitRadius(af, g);
            double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
            // Centre the circle a radius to the turning side, so the aircraft is already on it.
            double ux = vh > 1.0E-3 ? s.v.x / vh : 0.0, uz = vh > 1.0E-3 ? s.v.z / vh : 1.0;
            double side = sit.clockwise ? 1.0 : -1.0; // starboard of (ux, uz) is (-uz, ux)
            cx = s.p.x + side * -uz * r;
            cz = s.p.z + side * ux * r;
            y = Math.max(s.p.y, Double.isNaN(sit.cruiseY) ? s.p.y : sit.cruiseY);
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            return circle(t, s, cx, cz, orbitRadius(af, g), y, af.cruiseSpeed, sit.clockwise);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            return false;
        }
    }

    // --- Combat ----------------------------------------------------------------------------------

    /** Run alignment the goal's {@code established} flag already uses, and the break's turn-back gate. */
    static final double TURN_ALIGN = StrictMath.toRadians(35.0);
    /** Ground probed ahead for the run's pull-up test, m (the legacy 24-block ridge probe). */
    private static final double PULLUP_PROBE = 24.0;

    /** Altitude above ground, from the snapshot's ground sample. */
    static double agl(WingState s, WingSituation sit) {
        return Double.isInfinite(sit.groundBelow) ? Double.POSITIVE_INFINITY : s.p.y - sit.groundBelow;
    }

    /** Highest ground under the hull or within {@code range} ahead (the floor samples). */
    static double groundRef(WingSituation sit, double range) {
        double g = sit.groundBelow;
        for (int i = 0; i < sit.floor.length && (i + 1) * sit.floorStep <= range + sit.floorStep; i++) g = Math.max(g, sit.floor[i]);
        return g;
    }

    /** Unsigned angle between the ground track and the bearing to (x, z), rad. */
    static double headingError(WingState s, double x, double z) {
        double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
        double dx = x - s.p.x, dz = z - s.p.z, d = StrictMath.sqrt(dx * dx + dz * dz);
        if (vh < 1.0E-3 || d < 1.0E-3) return 0.0;
        double c = (s.v.x * dx + s.v.z * dz) / (vh * d);
        return StrictMath.acos(Math.max(-1.0, Math.min(1.0, c)));
    }

    /** The run altitude, never above the ceiling; the current height when none was briefed. */
    static double runAltitude(WingState s, WingSituation sit) {
        return Math.min(Double.isNaN(sit.runY) ? s.p.y : sit.runY, sit.ceilingY);
    }

    /**
     * Closing on the target at the height the run is flown from. Inside the bubble but not lined up,
     * it holds its own heading outbound until clear of the bubble and then comes back in on a long
     * straight leg (circling aligns nothing: an orbit's tangent is never radial). Inside, lined up,
     * but the run is unsafe: circle the target while the geometry changes. Never completes: the
     * selector moves it on to a run.
     */
    static final class Ingress implements WingProcedure {

        private final WingAirframe af;
        private boolean outbound;
        private double ox, oz;

        Ingress(WingAirframe af, double g) {
            this.af = af;
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.INGRESS;
        }

        @Override
        public boolean canBegin(WingState s, WingSituation sit) {
            return sit.targetValid;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {
            outbound = false;
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            double y = runAltitude(s, sit);
            double d = distance(s, sit.targetX, sit.targetZ);
            if (outbound && d > 1.5 * sit.engageRange) outbound = false;
            if (!outbound && d < sit.engageRange && !sit.established) {
                double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
                ox = vh > 1.0E-3 ? s.v.x / vh : 0.0;
                oz = vh > 1.0E-3 ? s.v.z / vh : 1.0;
                outbound = true;
            }
            if (outbound) {
                double out = 2.0 * sit.engageRange;
                return toward(t, s, s.p.x + ox * out, s.p.z + oz * out, y, af.cruiseSpeed);
            }
            if (d < sit.engageRange) {
                return circle(t, s, sit.targetX, sit.targetZ, sit.orbitRadius, y, af.cruiseSpeed, sit.clockwise);
            }
            return toward(t, s, sit.targetX, sit.targetZ, y, af.cruiseSpeed);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            return false;
        }
    }

    /** Shared run bookkeeping: the axis is latched at begin and every exit is measured along it. */
    abstract static class Run implements WingProcedure {

        final WingAirframe af;
        final double g;
        double axisX, axisZ, startX, startZ;
        private double lastTx, lastTz;

        Run(WingAirframe af, double g) {
            this.af = af;
            this.g = g;
        }

        @Override
        public boolean sticky() {
            return true;
        }

        @Override
        public boolean canBegin(WingState s, WingSituation sit) {
            return sit.targetValid;
        }

        /** Where the axis is laid to: the target, or for a bomb the led ground point. */
        abstract double axisX(WingSituation sit);

        abstract double axisZ(WingSituation sit);

        @Override
        public void begin(WingState s, WingSituation sit, double t) {
            double dx = axisX(sit) - s.p.x, dz = axisZ(sit) - s.p.z, d = StrictMath.sqrt(dx * dx + dz * dz);
            double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
            axisX = d > 1.0E-3 ? dx / d : (vh > 1.0E-3 ? s.v.x / vh : 0.0);
            axisZ = d > 1.0E-3 ? dz / d : (vh > 1.0E-3 ? s.v.z / vh : 1.0);
            startX = s.p.x;
            startZ = s.p.z;
            lastTx = sit.targetX;
            lastTz = sit.targetZ;
        }

        /**
         * Passed the target on the locked axis, the ground coming up faster than the pull-up can
         * absorb, or off the end of the run. A completed bomb stick is deliberately not an exit:
         * the last bomb goes well short of the target, and breaking there pulls up before overflying it.
         */
        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            if (sit.targetValid) {
                lastTx = sit.targetX;
                lastTz = sit.targetZ;
            }
            double plane = (s.p.x - startX) * axisX + (s.p.z - startZ) * axisZ;
            double target = (lastTx - startX) * axisX + (lastTz - startZ) * axisZ;
            if (plane > target + sit.overflyMargin) return true;
            double clearance = s.p.y - groundRef(sit, PULLUP_PROBE);
            double drop = Math.max(Math.max(0.0, -s.v.y) * sit.pullupLead,
                    TerrainGuard.pullOutDrop(af, g, s.v.length(), s.v.y));
            if (clearance <= sit.minAttackClearance + drop) return true;
            return StrictMath.hypot(s.p.x - startX, s.p.z - startZ) >= sit.runLength;
        }
    }

    /**
     * Gun, rocket or nose-launched missile run: point the weapon line at the led aim point
     * (NOSE_AIM), at run speed, no deeper than the store's delivery angle. The controller's speed
     * hold brakes the dive on its own (the airbrake opens when the throttle is closed and still
     * too fast), which is what buys the gun tracking time.
     */
    static final class AttackDive extends Run {

        AttackDive(WingAirframe af, double g) {
            super(af, g);
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.ATTACK_DIVE;
        }

        @Override
        double axisX(WingSituation sit) {
            return sit.targetX;
        }

        @Override
        double axisZ(WingSituation sit) {
            return sit.targetZ;
        }

        /** The nose direction to fly: the goal's boresight-corrected one, clamped to the dive limit. */
        Vector3d aim(WingState s, WingSituation sit) {
            Vector3d a = sit.hasAimDir ? new Vector3d(sit.aimDirX, sit.aimDirY, sit.aimDirZ)
                    : new Vector3d(sit.targetX - s.p.x, sit.targetY - s.p.y, sit.targetZ - s.p.z);
            if (a.lengthSquared() < 1.0E-9) a.set(axisX, 0.0, axisZ);
            a.normalize();
            double h = StrictMath.sqrt(a.x * a.x + a.z * a.z);
            double el = StrictMath.atan2(a.y, h);
            if (el < -sit.maxDive && h > 1.0E-6) {
                double c = StrictMath.cos(sit.maxDive);
                a.set(a.x / h * c, -StrictMath.sin(sit.maxDive), a.z / h * c);
            }
            return a;
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            Vector3d a = aim(s, sit);
            return WingReference.noseAim(t, new Vector3d(s.p), new Vector3d(axisX * af.runSpeed, 0.0, axisZ * af.runSpeed), a);
        }

        @Override
        public boolean fireWindow(WingState s, WingSituation sit) {
            Vector3d nose = s.q.transform(new Vector3d(0.0, 0.0, 1.0));
            return nose.angle(aim(s, sit)) < Math.max(af.coneHi - af.coneLo, StrictMath.toRadians(4.0));
        }
    }

    /**
     * Level bombing pass (and the grid-mark pass, which is the same thing aimed at dirt): fly the
     * locked axis as a LINE through the led ground aim point at the briefed height, slowed for the
     * release and back to cruise once the stick is gone. Steering by line, not by heading, is what
     * removes the lateral offset that is the whole miss for a store aimed by when it is released.
     * The release itself is the goal's (it solves the drop from the hull's own velocity).
     */
    static final class AttackLevel extends Run {

        private final boolean mark;
        private final double vSlow;

        AttackLevel(WingAirframe af, double g, boolean mark) {
            super(af, g);
            this.mark = mark;
            this.vSlow = Math.max(1.3 * AeroModel.stallSpeed(af, g, WingAirframe.RHO0, 0.0), 0.85 * af.runSpeed);
        }

        @Override
        public WingProcedureId id() {
            return mark ? WingProcedureId.ATTACK_MARK : WingProcedureId.ATTACK_LEVEL;
        }

        @Override
        double axisX(WingSituation sit) {
            return sit.levelDelivery ? sit.bombAimX : sit.targetX;
        }

        @Override
        double axisZ(WingSituation sit) {
            return sit.levelDelivery ? sit.bombAimZ : sit.targetZ;
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            double lx = axisX(sit), lz = axisZ(sit);
            double along = (s.p.x - lx) * axisX + (s.p.z - lz) * axisZ;
            double speed = sit.stickComplete || !sit.levelDelivery ? af.cruiseSpeed : vSlow;
            return WingReference.flypath(t, new Vector3d(lx + axisX * along, runAltitude(s, sit), lz + axisZ * along),
                    new Vector3d(axisX * speed, 0.0, axisZ * speed), new Vector3d());
        }
    }

    /**
     * Reverse for the next pass: extend along the exit track, climbing to buy back height (a low hard
     * turn is how a heavy hull ends in the trees), until far enough out that the next run gets a
     * full run-in, then turn back onto the target. Turning back straight away from overhead puts
     * the target inside the turn circle, and pursuit of a point inside your own turn circle is an
     * orbit around it that never closes. Over when back toward the target at the height the next
     * run starts from (or the ceiling).
     */
    static final class Break implements WingProcedure {

        /** Extend to this multiple of the engage range before turning back. */
        private static final double EXTEND = 1.1;
        /** Never extend longer than this, s (a target that keeps pace must not drag us off). */
        private static final double EXTEND_MAX = 15.0;
        private final WingAirframe af;
        private double began;
        private double tx, tz, ex, ez;
        private boolean turning;

        Break(WingAirframe af, double g) {
            this.af = af;
        }

        @Override
        public WingProcedureId id() {
            return WingProcedureId.BREAK;
        }

        @Override
        public boolean sticky() {
            return true;
        }

        @Override
        public void begin(WingState s, WingSituation sit, double t) {
            began = t;
            tx = sit.targetX;
            tz = sit.targetZ;
            double vh = StrictMath.sqrt(s.v.x * s.v.x + s.v.z * s.v.z);
            ex = vh > 1.0E-3 ? s.v.x / vh : 0.0;
            ez = vh > 1.0E-3 ? s.v.z / vh : 1.0;
            turning = false;
        }

        private double recoverY(WingSituation sit) {
            double y = Double.isNaN(sit.cruiseY) ? Double.NEGATIVE_INFINITY : sit.cruiseY;
            if (!Double.isInfinite(sit.groundBelow)) y = Math.max(y, sit.groundBelow + sit.entryAgl);
            return Math.min(y, sit.ceilingY);
        }

        @Override
        public WingReference refAt(double t, WingState s, WingSituation sit) {
            if (sit.targetValid) {
                tx = sit.targetX;
                tz = sit.targetZ;
            }
            double y = Math.max(recoverY(sit), Math.min(s.p.y, sit.ceilingY));
            if (!turning) {
                boolean far = distance(s, tx, tz) >= EXTEND * sit.engageRange;
                boolean climbed = agl(s, sit) >= 0.5 * sit.entryAgl || s.p.y >= sit.ceilingY - 2.0;
                turning = (far && climbed) || t - began > EXTEND_MAX;
            }
            if (!turning) return toward(t, s, s.p.x + ex * 500.0, s.p.z + ez * 500.0, y, af.cruiseSpeed);
            return toward(t, s, tx, tz, y, af.cruiseSpeed);
        }

        @Override
        public boolean isComplete(WingState s, WingSituation sit, double t) {
            boolean high = agl(s, sit) >= sit.entryAgl || s.p.y >= sit.ceilingY - 2.0;
            return turning && high && headingError(s, tx, tz) < TURN_ALIGN;
        }

        @Override
        public boolean fireWindow(WingState s, WingSituation sit) {
            return false;
        }
    }

    static double distance(WingState s, double x, double z) {
        double dx = x - s.p.x, dz = z - s.p.z;
        return StrictMath.sqrt(dx * dx + dz * dz);
    }

    /** One SplitMix64 step (the golden-gamma add keeps key 0 off a fixed point). */
    static long mix(long key) {
        long z = key + 0x9E3779B97F4A7C15L;
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return z;
    }
}
