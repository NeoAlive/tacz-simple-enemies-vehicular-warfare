package com.neoalive.tacz_sewv.wing;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.ModeSelector.Precedence;
import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.wing.guidance.WingModeSelector;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureId;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureStack;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedures;
import com.neoalive.tacz_sewv.wing.guidance.WingSituation;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;

/** G1-G6: guidance (selector, stack, procedures) and the terrain guard, closed loop. */
final class WingGuidanceChecks {

    private WingGuidanceChecks() {}

    static void run() {
        selectorTable();
        for (WingAirframe af : WingSim.table().classes().values()) {
            orbitHolds(af);
            transitArrives(af);
            safetyHold(af);
            diveRecovery(af);
            ridge(af);
        }
        patrol(WingSim.table().classes().get("attack_jet"));
    }

    private static WingSituation sit(double cruiseY) {
        WingSituation s = new WingSituation();
        s.cruiseY = cruiseY;
        s.orbitRadius = 100.0;
        s.clockwise = true;
        return s;
    }

    /** G1: the selector's table, its precedence classes and its hysteresis. */
    private static void selectorTable() {
        WingSituation s = sit(500.0);
        assert WingModeSelector.select(s).id() == WingProcedureId.ORBIT : "G1: default is ORBIT";
        s.autonomous = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.PATROL : "G1: autonomous with no anchor patrols";
        s.hasAnchor = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.ORBIT : "G1: an anchor is held, not patrolled";
        s.leash = WingSituation.Leash.RETURN;
        var c = WingModeSelector.select(s);
        assert c.id() == WingProcedureId.RTB && c.precedence() == Precedence.ORDERED : "G1: leash RETURN is an ordered RTB";
        s.leash = WingSituation.Leash.FREE;
        s.hasDestination = true;
        s.destDistance = 1000.0;
        assert WingModeSelector.select(s).id() == WingProcedureId.TRANSIT : "G1: a far destination is a transit";
        assert WingModeSelector.select(s).precedence() == Precedence.FREENAV : "G1: unordered transit is FREENAV";
        s.underOrders = true;
        assert WingModeSelector.select(s).precedence() == Precedence.ORDERED : "G1: ordered transit is ORDERED";
        s.destDistance = 200.0; // between ARRIVE (120) and DEPART (250) radii
        s.active = WingProcedureId.TRANSIT;
        assert WingModeSelector.select(s).id() == WingProcedureId.TRANSIT : "G1: a transit runs until it arrives";
        s.active = WingProcedureId.ORBIT;
        assert WingModeSelector.select(s).id() == WingProcedureId.ORBIT : "G1: an orbit does not flick to a transit";
        s.destDistance = 100.0;
        s.active = WingProcedureId.TRANSIT;
        assert WingModeSelector.select(s).id() == WingProcedureId.ORBIT : "G1: arrived means orbit";
    }

    /** G2: started off the circle, the aircraft settles onto it and holds radius and height. */
    private static void orbitHolds(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 300.0, 480.0, 0.0);
        WingSituation s = sit(500.0);
        double r = WingProcedures.orbitRadius(af, WingSim.G);
        for (int i = 0; i < 60 * 20; i++) sim.fly(s, null);
        double rad = 0.0, alt = 0.0;
        for (int i = 0; i < 40 * 20; i++) {
            sim.fly(s, null);
            rad = Math.max(rad, Math.abs(Math.hypot(sim.core.s.p.x, sim.core.s.p.z) - r));
            alt = Math.max(alt, Math.abs(sim.core.s.p.y - 500.0));
        }
        assert sim.core.stack.activeId() == WingProcedureId.ORBIT : "G2: " + af.name + " should be orbiting";
        assert rad < 0.15 * r + 5.0 : "G2: " + af.name + " radius error " + rad + " on r=" + r;
        assert alt < 4.0 : "G2: " + af.name + " altitude error " + alt;
    }

    /** G3: a destination 1.5 km off the beam is reached, and the selector then orbits it. */
    private static void transitArrives(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 500.0, 0.0);
        WingSituation s = sit(520.0);
        s.hasDestination = true;
        s.destX = 1500.0;
        s.destZ = 300.0;
        double limit = Math.hypot(1500.0, 300.0) / af.cruiseSpeed + 40.0;
        boolean arrived = false;
        for (int i = 0; i < limit * 20; i++) {
            s.destDistance = Math.hypot(s.destX - sim.core.s.p.x, s.destZ - sim.core.s.p.z);
            sim.fly(s, null);
            if (sim.core.stack.activeId() == WingProcedureId.ORBIT) {
                arrived = true;
                break;
            }
        }
        assert arrived : "G3: " + af.name + " never arrived (" + s.destDistance + " m left)";
        assert Math.abs(sim.core.s.p.y - 520.0) < 25.0 : "G3: " + af.name + " arrived at " + sim.core.s.p.y;
    }

    /** G4: stale guidance holds safe: circles near where it was and does not descend. */
    private static void safetyHold(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 400.0, 0.0);
        WingSituation stale = sit(Double.NaN);
        stale.time = -10.0;
        assert WingProcedureStack.stale(stale, 0.0) : "G4: an old snapshot is stale";
        sim.core.stack.switchTo(WingProcedureId.SAFETY_HOLD, sim.core.s, stale, 0.0, 0);
        double r = WingProcedures.orbitRadius(af, WingSim.G), far = 0.0, low = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 60 * 20; i++) {
            sim.core.fly(new com.neoalive.tacz_sewv.wing.physics.WingEnv(WingSim.G, WingSim.RHO, sim.wind,
                    Double.NEGATIVE_INFINITY, false), stale, sim.tick++);
            far = Math.max(far, Math.hypot(sim.core.s.p.x, sim.core.s.p.z));
            low = Math.min(low, sim.core.s.p.y);
        }
        assert far < 3.0 * r : "G4: " + af.name + " wandered " + far + " m (r=" + r + ")";
        assert low > 392.0 : "G4: " + af.name + " descended to " + low; // entry transient into a tight orbit
    }

    /** G5: a 35 degree dive at the ground from 140 m: the guard pulls out with room to spare. */
    private static void diveRecovery(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 140.0, 0.0);
        double v = af.cruiseSpeed, gamma = StrictMath.toRadians(-35.0);
        sim.core.s.v.set(0.0, v * StrictMath.sin(gamma), v * StrictMath.cos(gamma));
        sim.core.s.q.set(McPose.toQuat(0.0, 35.0, 0.0));
        WingSituation s = sit(-100.0); // guidance itself asks for the ground: only the guard can save it
        s.hasDestination = true;
        s.destX = 0.0;
        s.destZ = 5000.0;
        s.destDistance = 5000.0;
        double low = Double.POSITIVE_INFINITY;
        boolean recovered = false;
        for (int i = 0; i < 20 * 20; i++) {
            sim.fly(s, (x, z) -> 0.0);
            low = Math.min(low, sim.core.s.p.y);
            recovered |= sim.core.guard.recovering();
        }
        // Either layer may save it: an agile airframe is pulled out by the floor bias alone, before
        // the recovery is needed, and then it must have kept real margin.
        assert low > 3.0 : "G5: " + af.name + " bottomed out at " + low + " m";
        assert recovered || low > 0.5 * com.neoalive.tacz_sewv.wing.avoid.TerrainGuard.CLEARANCE
                : "G5: " + af.name + " neither recovered nor kept margin (low " + low + ")";
        // Guidance keeps asking for the ground, so afterwards the floor bias holds it near
        // terrain + clearance (gently hunting there); it must simply stay clear.
        assert sim.core.s.p.y > 5.0 : "G5: " + af.name + " ended at " + sim.core.s.p.y + " m";
    }

    /** G6a: level at 100 m toward a 160 m ridge 800 m out: crosses it with clearance. */
    private static void ridge(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 100.0, 0.0);
        WingSituation s = sit(100.0);
        s.hasDestination = true;
        s.destX = 0.0;
        s.destZ = 4000.0;
        s.destDistance = 4000.0;
        java.util.function.DoubleBinaryOperator terrain = (x, z) -> (z > 800.0 && z < 900.0) ? 160.0 : 40.0;
        double worst = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 60 * 20 && sim.core.s.p.z < 1000.0; i++) {
            s.destDistance = 4000.0 - sim.core.s.p.z;
            sim.fly(s, terrain);
            var p = sim.core.s.p;
            worst = Math.min(worst, p.y - terrain.applyAsDouble(p.x, p.z));
        }
        assert sim.core.s.p.z >= 1000.0 : "G6: " + af.name + " never got past the ridge";
        assert worst > 3.0 : "G6: " + af.name + " cleared the ridge by only " + worst + " m";
    }

    /** G6b: patrol stays around its centre and is reproducible run to run. */
    private static void patrol(WingAirframe af) {
        Vector3d endA = patrolRun(af), endB = patrolRun(af);
        assert endA.equals(endB) : "G6: patrol must be deterministic";
    }

    private static Vector3d patrolRun(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 500.0, 0.0);
        WingSituation s = sit(500.0);
        s.autonomous = true;
        s.pilotId = 4242;
        double r = WingProcedures.orbitRadius(af, WingSim.G), far = 0.0;
        for (int i = 0; i < 180 * 20; i++) {
            sim.fly(s, null);
            far = Math.max(far, Math.hypot(sim.core.s.p.x, sim.core.s.p.z));
        }
        assert sim.core.stack.activeId() == WingProcedureId.PATROL : "G6: should be patrolling";
        assert far < 6.0 * r : "G6: patrol wandered " + far + " m (r=" + r + ")";
        return new Vector3d(sim.core.s.p);
    }
}
