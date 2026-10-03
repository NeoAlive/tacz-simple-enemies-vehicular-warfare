package com.neoalive.tacz_sewv.heli;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.OrderKind;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureId;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureStack;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * Phase 2 guidance checks: the selector subset (S1, S3), the stack's minimum-life rule (S7) and
 * staleness fallback, and a whole guided mission through the real stack and selector, the headless
 * mirror of the in-game exit run: park, take off, transit 300 m, hold 60 s, land, park, take off.
 */
final class HeliGuidedChecks {

    private HeliGuidedChecks() {}

    static void run() {
        selector();
        minimumLife();
        for (String cls : HeliPhysicsChecks.CLASSES) mission(cls);
    }

    // S1 / S3 ----------------------------------------------------------------------------------------
    private static void selector() {
        Situation s = new Situation();
        assert ModeSelector.select(s).id() == ProcedureId.HOVER_HOLD : "S3: an empty situation must hold";
        s.order = OrderKind.LANDED;
        assert ModeSelector.select(s).id() == ProcedureId.PARK : "S1: LANDED -> PARK";
        s.order = OrderKind.LAND;
        assert ModeSelector.select(s).id() == ProcedureId.LAND : "S1: LAND -> LAND";
        s.order = OrderKind.RAPPEL;
        assert ModeSelector.select(s).id() == ProcedureId.RAPPEL_HOLD : "S1: RAPPEL -> RAPPEL_HOLD";
        s.order = OrderKind.TAKEOFF;
        assert ModeSelector.select(s).id() == ProcedureId.TAKEOFF : "S1: TAKEOFF -> TAKEOFF";

        s = new Situation();
        s.hasDestination = true;
        s.destDistance = 100;
        assert ModeSelector.select(s).id() == ProcedureId.TRANSIT : "S1: far destination -> TRANSIT";
        s.destDistance = 30;
        assert ModeSelector.select(s).id() == ProcedureId.HOVER_HOLD : "S1: near destination -> HOVER_HOLD";
        s.active = ProcedureId.TRANSIT;
        assert ModeSelector.select(s).id() == ProcedureId.TRANSIT : "S1: a transit runs until it arrives";
        s.destDistance = 3;
        assert ModeSelector.select(s).id() == ProcedureId.HOVER_HOLD : "S1: an arrived transit hands over to the hold";

        s = new Situation();
        s.targetValid = true;
        s.hasDestination = true;
        s.destDistance = 100;
        assert ModeSelector.select(s).id() == ProcedureId.TRANSIT : "S1 row 9: an unarmed hull with a target keeps its destination";
        s.armed = true;
        assert ModeSelector.select(s).precedence() == ModeSelector.Precedence.ATTACK : "S1 row 8: an armed hull attacks";
        s.underOrders = true;
        assert ModeSelector.select(s).id() == ProcedureId.TRANSIT : "S4: an order outranks the target";
        s.order = OrderKind.LAND;
        assert ModeSelector.select(s).id() == ProcedureId.LAND : "S4: a forced order outranks everything";
    }

    // S7 and staleness ---------------------------------------------------------------------------------
    private static void minimumLife() {
        Airframe af = Sim.airframe("light");
        Sim sim = new Sim(af).hover(0, 100, 0, 0);
        Situation sit = new Situation();
        sit.hasDestination = true;
        sit.destX = 2;
        sit.destZ = 0;
        sit.destY = 100;
        ProcedureStack stack = new ProcedureStack(af, Sim.G);
        stack.switchTo(ProcedureId.TRANSIT, sim.s, sit, 0.0, 0L);
        // The destination is already inside the arrival radius and the hull at rest: complete-on-begin.
        assert !stack.complete(sim.s, sit, 0.0, 0L) : "S7: completion evaluated on the begin tick";
        assert stack.complete(sim.s, sit, 0.05, 1L) : "S7: completion missed on the following tick";

        sit.time = 0.0;
        assert !ProcedureStack.stale(sit, 0.9) && ProcedureStack.stale(sit, 1.1) : "stale threshold is 1 s";
    }

    // Mission ------------------------------------------------------------------------------------------
    private static void mission(String cls) {
        Airframe af = Sim.airframe(cls);
        Sim sim = new Sim(af);
        sim.onGround = true;
        ProcedureStack stack = sim.core.stack;
        long[] tick = {0};
        double[] worstJump = {0}, worstDv = {0};
        HeliReference[] prev = {null};
        java.util.function.Consumer<HeliReference> continuity = r -> {
            if (prev[0] != null) {
                worstJump[0] = Math.max(worstJump[0], new Vector3d(r.p()).distance(prev[0].p()));
                worstDv[0] = Math.max(worstDv[0], new Vector3d(r.v()).distance(prev[0].v()));
            }
            prev[0] = r;
        };

        // Parked, then a takeoff order to 35 m.
        Situation sit = new Situation();
        sit.order = OrderKind.LANDED;
        for (int i = 0; i < 40; i++) sim.tickGuided(sit, tick[0]++, continuity);
        assert stack.activeId() == ProcedureId.PARK && sim.s.engine == HeliState.Engine.OFF : "mission: not parked";
        int takeoffTicks = takeoff(sim, tick, continuity);

        // Transit 300 m east at 35 m, then hold over the destination for 60 s.
        Situation go = new Situation();
        go.hasDestination = true;
        go.destX = 300;
        go.destZ = 0;
        go.destY = 35;
        go.holdX = 300;
        go.holdZ = 0;
        go.holdY = 35;
        int transitTicks = 0;
        while (transitTicks < 20 * 120) {
            go.destDistance = Math.hypot(300 - sim.s.p.x, sim.s.p.z);
            sim.tickGuided(go, tick[0]++, continuity);
            transitTicks++;
            if (stack.activeId() == ProcedureId.HOVER_HOLD && go.destDistance < 2) break;
        }
        assert stack.activeId() == ProcedureId.HOVER_HOLD : "mission " + cls + ": transit never arrived";
        double worstHold = 0;
        for (int i = 0; i < 20 * 60; i++) {
            go.destDistance = Math.hypot(300 - sim.s.p.x, sim.s.p.z);
            sim.tickGuided(go, tick[0]++, continuity);
            if (i >= 20 * 10) worstHold = Math.max(worstHold, sim.s.p.distance(300, 35, 0));
        }
        assert worstHold <= 1.0 : "mission " + cls + ": hold strayed " + worstHold + " m";

        // Land on a pad 40 m on, settle the way the goal does, park, then take off again.
        Situation land = new Situation();
        land.order = OrderKind.LAND;
        land.padX = 340;
        land.padZ = 0;
        land.touchdownY = 0;
        land.transitY = 24;
        boolean settled = false;
        double sink = 0;
        for (int i = 0; i < 20 * 120 && !settled; i++) {
            double vyBefore = sim.s.v.y;
            sim.tickGuided(land, tick[0]++, continuity);
            if (sim.onGround) sink = Math.max(sink, -vyBefore);
            settled = sim.onGround && Math.hypot(340 - sim.s.p.x, sim.s.p.z) <= 2.25;
        }
        assert settled : "mission " + cls + ": never settled on the pad (at " + sim.s.p + ")";
        assert sink < 3.0 : "mission " + cls + ": touchdown sink " + sink + " m/s";
        Situation parked = new Situation();
        parked.order = OrderKind.LANDED;
        for (int i = 0; i < 20 * 20; i++) sim.tickGuided(parked, tick[0]++, continuity);
        assert sim.s.engine == HeliState.Engine.OFF && sim.onGround : "mission " + cls + ": did not park";
        int again = takeoff(sim, tick, continuity);

        System.out.printf(java.util.Locale.ROOT,
                "  M %s: takeoff %.1f s, transit 300 m %.1f s, hold +-%.2f m, touchdown %.2f m/s, re-takeoff %.1f s, "
                        + "reference max step %.3f m / %.3f m/s per sub-step%n",
                cls, takeoffTicks / 20.0, transitTicks / 20.0, worstHold, sink, again / 20.0, worstJump[0], worstDv[0]);
        double h = HeliPhysics.H;
        assert worstJump[0] <= (af.vMaxH + 0.5 * af.vMaxH + 1.0) * h : "mission " + cls + ": reference position stepped";
        assert worstDv[0] <= (3.0 * Sim.G) * h : "mission " + cls + ": reference velocity stepped by " + worstDv[0];
    }

    /** TAKEOFF to 35 m, cleared the way the goal clears it; returns ticks taken. */
    private static int takeoff(Sim sim, long[] tick, java.util.function.Consumer<HeliReference> sink) {
        Situation up = new Situation();
        up.order = OrderKind.TAKEOFF;
        up.climbTo = 35;
        int n = 0;
        while (n < 20 * 90 && !(sim.s.p.y >= 35 - 2.5 && !sim.onGround)) {
            sim.tickGuided(up, tick[0]++, sink);
            n++;
        }
        assert sim.s.p.y >= 32.5 : "takeoff did not reach 35 m (at " + sim.s.p.y + ")";
        return n;
    }
}
