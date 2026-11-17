package com.neoalive.tacz_sewv.wing;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.ModeSelector.Precedence;
import com.neoalive.tacz_sewv.wing.guidance.WingModeSelector;
import com.neoalive.tacz_sewv.wing.guidance.WingProcedureId;
import com.neoalive.tacz_sewv.wing.guidance.WingSituation;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;

/**
 * K1-K6: combat guidance. The test plays the drive goal's part, filling the target facts every tick
 * from the sim state with the goal's own thresholds, against a static ground target on flat ground.
 */
final class WingCombatChecks {

    private static final double ENGAGE = 192.0, RUN_ALIGN = StrictMath.toRadians(12.0);

    private WingCombatChecks() {}

    static void run() {
        selectorTable();
        stickiness();
        for (WingAirframe af : WingSim.table().classes().values()) {
            gunPass(af);
            bombRun(af);
            reposition(af);
        }
        rangeLimitPreempts(WingSim.table().classes().get("attack_jet"));
    }

    private static WingSituation target(double tx, double tz) {
        WingSituation s = new WingSituation();
        s.cruiseY = 200.0;
        s.clockwise = true;
        s.orbitRadius = 80.0;
        s.targetValid = true;
        s.targetX = tx;
        s.targetZ = tz;
        s.engageRange = ENGAGE;
        s.runLength = ENGAGE + 16.0;
        s.runY = 120.0;
        s.groundBelow = 0.0;
        return s;
    }

    /** The goal's per-tick facts, from the sim state. */
    private static void facts(CtlSim sim, WingSituation s) {
        var p = sim.core.s.p;
        double dx = s.targetX - p.x, dz = s.targetZ - p.z, d = Math.hypot(dx, dz);
        s.targetDistance = d;
        double vh = Math.hypot(sim.core.s.v.x, sim.core.s.v.z);
        double c = vh > 1.0E-3 && d > 1.0E-3 ? (sim.core.s.v.x * dx + sim.core.s.v.z * dz) / (vh * d) : 1.0;
        s.established = StrictMath.acos(Math.max(-1.0, Math.min(1.0, c))) <= RUN_ALIGN;
        s.hasAimDir = !s.levelDelivery;
        Vector3d a = new Vector3d(dx, s.targetY - p.y, dz).normalize();
        s.aimDirX = a.x;
        s.aimDirY = a.y;
        s.aimDirZ = a.z;
        s.bombAimX = s.targetX;
        s.bombAimZ = s.targetZ;
    }

    /** K1: the combat rows of the selector. */
    private static void selectorTable() {
        WingSituation s = target(0.0, 1000.0);
        s.targetDistance = 1000.0;
        assert WingModeSelector.select(s).id() == WingProcedureId.INGRESS : "K1: far target is ingress";
        assert WingModeSelector.select(s).precedence() == Precedence.ATTACK : "K1: combat is the ATTACK class";
        s.targetDistance = 150.0;
        assert WingModeSelector.select(s).id() == WingProcedureId.INGRESS : "K1: not lined up is ingress";
        s.established = true;
        s.runSafe = false;
        assert WingModeSelector.select(s).id() == WingProcedureId.INGRESS : "K1: unsafe run is ingress";
        s.runSafe = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.ATTACK_DIVE : "K1: gun run";
        s.levelDelivery = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.ATTACK_LEVEL : "K1: bomb run";
        s.isMark = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.ATTACK_MARK : "K1: grid-mark run";
        s.active = WingProcedureId.ATTACK_MARK;
        assert WingModeSelector.select(s).id() == WingProcedureId.BREAK : "K1: a run is followed by a break";
        s.hasAnchor = true;
        s.leash = WingSituation.Leash.RECALL;
        assert WingModeSelector.select(s).id() == WingProcedureId.BREAK : "K1: recalled mid-run finishes the run";
        s.active = WingProcedureId.INGRESS;
        assert WingModeSelector.select(s).id() == WingProcedureId.RTB : "K1: recalled otherwise goes home";
        s.leash = WingSituation.Leash.FREE;
        s.underOrders = true;
        assert WingModeSelector.select(s).id() == WingProcedureId.ORBIT : "K1: under orders, no fighting";
    }

    /** K2: a run is not interrupted by its own break choice; a break is not cut short either. */
    private static void stickiness() {
        CtlSim sim = new CtlSim("attack_jet").start(40.0, 0.0, 120.0, 0.0);
        WingSituation s = target(0.0, 150.0);
        facts(sim, s);
        s.runSafe = true;
        sim.core.stack.request(new WingModeSelector.Choice(WingProcedureId.ATTACK_DIVE, Precedence.ATTACK), sim.core.s, s, 0.0, 0);
        sim.core.stack.request(new WingModeSelector.Choice(WingProcedureId.BREAK, Precedence.ATTACK), sim.core.s, s, 0.05, 1);
        assert sim.core.stack.activeId() == WingProcedureId.ATTACK_DIVE : "K2: an unfinished run must continue";
        sim.core.stack.request(new WingModeSelector.Choice(WingProcedureId.RTB, Precedence.ORDERED), sim.core.s, s, 0.1, 2);
        // RTB needs an anchor to begin; without one it falls back to an orbit, but it must pre-empt.
        assert sim.core.stack.activeId() != WingProcedureId.ATTACK_DIVE : "K2: a higher class pre-empts a run";
    }

    /** K3: a full gun pass: ingress, dive with the boresight on, break without touching down, again. */
    private static void gunPass(WingAirframe af) {
        WingSituation s = target(0.0, 900.0);
        s.engageRange = com.neoalive.tacz_sewv.wing.guidance.WingProcedures.runEngageRange(af, ENGAGE);
        s.runY = com.neoalive.tacz_sewv.wing.guidance.WingProcedures.diveEntryAgl(s.engageRange);
        s.runLength = s.engageRange + 16.0;
        s.cruiseY = s.runY + 30.0;
        s.maxDive = StrictMath.toRadians(55.0);
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, s.cruiseY, 0.0);
        boolean dived = false, broke = false, second = false;
        int onTarget = 0;
        double lowAgl = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 150 * 20 && !second; i++) {
            facts(sim, s);
            sim.fly(s, (x, z) -> 0.0);
            WingProcedureId a = sim.core.stack.activeId();
            lowAgl = Math.min(lowAgl, sim.core.s.p.y);
            if (a == WingProcedureId.ATTACK_DIVE) {
                dived = true;
                if (sim.core.stack.fireWindow(sim.core.s, s)) onTarget++;
                if (broke) second = true;
            }
            if (a == WingProcedureId.BREAK && dived) broke = true;
        }
        assert dived : "K3: " + af.name + " never started a gun run";
        assert onTarget >= 10 : "K3: " + af.name + " boresight on target for only " + onTarget + " ticks";
        assert broke : "K3: " + af.name + " never broke off";
        assert second : "K3: " + af.name + " never came round for a second pass";
        assert lowAgl > 10.0 : "K3: " + af.name + " got down to " + lowAgl + " m";
    }

    /** K4: a bomb run flies its line: small cross-track at the target, at height, slowed. */
    private static void bombRun(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 30.0, 120.0, 0.0);
        WingSituation s = target(0.0, 700.0);
        s.levelDelivery = true;
        s.cruiseY = 120.0;
        s.runY = 120.0;
        s.engageRange = 400.0;
        s.runLength = 600.0;
        double crossAtTarget = Double.NaN, altErr = 0.0, maxV = 0.0;
        boolean ran = false;
        for (int i = 0; i < 90 * 20; i++) {
            facts(sim, s);
            sim.fly(s, (x, z) -> 0.0);
            var p = sim.core.s.p;
            if (sim.core.stack.activeId() == WingProcedureId.ATTACK_LEVEL) {
                ran = true;
                if (Math.abs(p.z - 700.0) < 300.0) altErr = Math.max(altErr, Math.abs(p.y - 120.0));
                if (Math.abs(p.z - 700.0) < 150.0) maxV = Math.max(maxV, sim.speed());
                if (Double.isNaN(crossAtTarget) && p.z >= 700.0) crossAtTarget = Math.abs(p.x);
            }
            if (ran && sim.core.stack.activeId() == WingProcedureId.BREAK) break;
        }
        assert ran : "K4: " + af.name + " never started the bomb run";
        assert !Double.isNaN(crossAtTarget) && crossAtTarget < 4.0 : "K4: " + af.name + " crossed the target " + crossAtTarget + " m off";
        assert altErr < 6.0 : "K4: " + af.name + " altitude error on the run " + altErr;
        assert maxV < af.runSpeed : "K4: " + af.name + " not slowed for the release (" + maxV + ")";
    }

    /** K5: inside the bubble and across the axis: repositions outbound, comes back in, runs. */
    private static void reposition(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 160.0, 0.0);
        WingSituation s = target(120.0, 60.0); // close, ninety degrees off the nose
        s.cruiseY = 160.0;
        boolean ran = false;
        for (int i = 0; i < 90 * 20 && !ran; i++) {
            facts(sim, s);
            sim.fly(s, (x, z) -> 0.0);
            ran = sim.core.stack.activeId() == WingProcedureId.ATTACK_DIVE;
        }
        assert ran : "K5: " + af.name + " never lined up a run from inside the bubble";
    }

    /** K6: the hard range limit (a RETURN to the player) pre-empts a run in progress. */
    private static void rangeLimitPreempts(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 160.0, 0.0);
        WingSituation s = target(0.0, 150.0);
        facts(sim, s);
        sim.core.stack.request(new WingModeSelector.Choice(WingProcedureId.ATTACK_DIVE, Precedence.ATTACK), sim.core.s, s, 0.0, 0);
        s.leash = WingSituation.Leash.RETURN;
        s.hasAnchor = true;
        s.anchorX = 0.0;
        s.anchorZ = -2000.0;
        s.active = sim.core.stack.activeId();
        sim.core.stack.request(WingModeSelector.select(s), sim.core.s, s, 0.05, 1);
        assert sim.core.stack.activeId() == WingProcedureId.RTB : "K6: the range limit must pre-empt the run";
    }
}
