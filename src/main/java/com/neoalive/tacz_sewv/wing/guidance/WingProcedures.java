package com.neoalive.tacz_sewv.wing.guidance;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * The free-navigation procedures and their factory. All unscheduled (see {@link WingProcedure}):
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
            default -> new Orbit(af, g);
        };
    }

    /**
     * Orbit radius an airframe flies comfortably at cruise: the circle its bank and turn-rate
     * limits allow at 60 % of the effective bank, and never less than its traffic-pattern radius.
     */
    public static double orbitRadius(WingAirframe af, double g) {
        double v = af.cruiseSpeed;
        double bank = Math.min(af.bankMax, StrictMath.atan(af.turnRateMax * v / g));
        return Math.max(af.patternRadius, v * v / (g * StrictMath.tan(0.6 * bank)));
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
            double r = sit.orbitRadius * (1.5 + 2.5 * (((h >>> 3) & 0xFF) / 255.0));
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
