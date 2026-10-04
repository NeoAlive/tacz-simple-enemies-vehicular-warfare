package com.neoalive.tacz_sewv.heli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;
import com.neoalive.tacz_sewv.heli.avoid.PathProbe;
import com.neoalive.tacz_sewv.heli.guidance.Envelope;
import com.neoalive.tacz_sewv.heli.guidance.FirePhase;
import com.neoalive.tacz_sewv.heli.guidance.HeliProcedure;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.OrderKind;
import com.neoalive.tacz_sewv.heli.guidance.Parity;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureId;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureStack;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;

/**
 * Phase 3 checks (plan section 8): the full selector (S1-S6, S8, S9), the transition rule (S5),
 * Patrol (D4, R1), the attack envelopes (R8), the three attack procedures flown closed-loop (R3,
 * R4, R5, FireStill on a parked tank), the touchdown exclusion (A3), procedure isolation (A4), and
 * airframe mutual avoidance in the scenarios where two hulls manoeuvre near each other most:
 * formation and opposing FireLoops, crossing FireRuns.
 */
final class HeliAttackChecks {

    private static final double H = HeliPhysics.H;
    private static final int TARGET_ID = 77;

    private HeliAttackChecks() {}

    static void run() throws IOException {
        selectorTable();
        pick8e();
        parity();
        transitionRule();
        patrol();
        envelope();
        for (String cls : HeliPhysicsChecks.CLASSES) {
            fireStill(cls);
            fireLoop(cls);
            fireRuns(cls);
        }
        targetLoss();
        froude();
        for (String cls : HeliPhysicsChecks.CLASSES) engineOut(cls);
        touchdown();
        isolation();
        mutualAvoidance("light");
        mutualAvoidance("attack");
    }

    // --- S1-S4 -------------------------------------------------------------------------------------

    private static Situation armed() {
        Situation s = new Situation();
        s.targetValid = true;
        s.targetId = TARGET_ID;
        s.armed = true;
        s.targetLos = true;
        s.targetMotion = Situation.TargetMotion.SLOW;
        s.targetCategory = Situation.TargetCategory.INFANTRY;
        s.evadeHealth = 0.3;
        return s;
    }

    private static void expect(Situation s, ProcedureId id, ModeSelector.Precedence cls, String row) {
        ModeSelector.Choice c = ModeSelector.select(s);
        assert c.id() == id && c.precedence() == cls : "S1 " + row + ": got " + c;
    }

    private static void selectorTable() {
        Situation s = armed();
        s.healthFrac = 0.2;
        expect(s, ProcedureId.EVADE, ModeSelector.Precedence.SURVIVAL, "row 5");
        s.underOrders = true;
        expect(s, ProcedureId.HOVER_HOLD, ModeSelector.Precedence.ORDERED, "row 5 needs no order / row 7");
        s.hasDestination = true;
        s.destDistance = 100;
        expect(s, ProcedureId.TRANSIT, ModeSelector.Precedence.ORDERED, "row 6");

        s = armed();
        s.inFiringRun = true;
        s.targetLos = false;
        expect(s, ProcedureId.FIRE_RUN, ModeSelector.Precedence.ATTACK, "row 8a (sticky run)");
        s.inFiringRun = false;
        expect(s, ProcedureId.FIRE_LOOP, ModeSelector.Precedence.ATTACK, "row 8b");
        s.targetLos = true;
        s.weaponGuided = true;
        s.targetCategory = Situation.TargetCategory.VEHICLE;
        expect(s, ProcedureId.FIRE_STILL, ModeSelector.Precedence.ATTACK, "row 8c");
        s.yawStandoffOk = false;
        assert ModeSelector.select(s).id() != ProcedureId.FIRE_STILL : "S1 row 8c: needs the yaw envelope";
        s.yawStandoffOk = true;
        s.windCeilingOk = false;
        assert ModeSelector.select(s).id() != ProcedureId.FIRE_STILL : "S1 row 8c: needs the wind ceiling";
        s.windCeilingOk = true;
        s.weaponGuided = false;
        s.targetMotion = Situation.TargetMotion.FAST;
        expect(s, ProcedureId.FIRE_RUN, ModeSelector.Precedence.ATTACK, "row 8d");
        s.targetMotion = Situation.TargetMotion.SLOW;
        ProcedureId e = ModeSelector.pick8e(s.engageCycle, s.pilotId, s.targetId) ? ProcedureId.FIRE_RUN : ProcedureId.FIRE_LOOP;
        expect(s, e, ModeSelector.Precedence.ATTACK, "row 8e");

        s = armed();
        s.ammoFrac = 0.0;
        expect(s, ProcedureId.HOVER_HOLD, ModeSelector.Precedence.FREENAV, "row 9 (empty)");
        s.hasDestination = true;
        s.destDistance = 5;
        expect(s, ProcedureId.TRANSIT, ModeSelector.Precedence.FREENAV, "row 9 (empty, destination)");

        s = new Situation();
        s.hasDestination = true;
        s.destDistance = 100;
        expect(s, ProcedureId.TRANSIT, ModeSelector.Precedence.FREENAV, "row 10");
        s = new Situation();
        s.autonomous = true;
        expect(s, ProcedureId.PATROL, ModeSelector.Precedence.FREENAV, "row 11");
        s.autonomous = false;
        expect(s, ProcedureId.HOVER_HOLD, ModeSelector.Precedence.FREENAV, "row 12");

        // S2 invariance, S3 totality, S4 orders and the unarmed hull, over random summaries.
        SplittableRandom rng = new SplittableRandom(0x52L);
        for (int i = 0; i < 20_000; i++) {
            Situation a = randomSummary(rng);
            ModeSelector.Choice c = ModeSelector.select(a);
            assert c != null && c.id() != null : "S3: no winner";
            perturbGeometry(a, rng);
            assert ModeSelector.select(a).equals(c) : "S2: geometry changed the choice";
            if (!a.armed) assert c.precedence() != ModeSelector.Precedence.ATTACK : "S4: an unarmed hull attacks";
            if (a.underOrders && a.order == OrderKind.NONE) {
                assert c.precedence() == ModeSelector.Precedence.ORDERED : "S4: an order lost to " + c;
            }
        }
        System.out.println("  S1-S4: every table row, totality, geometry invariance, orders and the unarmed hull");
    }

    private static Situation randomSummary(SplittableRandom r) {
        Situation s = new Situation();
        OrderKind[] orders = OrderKind.values();
        s.order = r.nextInt(4) == 0 ? orders[r.nextInt(orders.length)] : OrderKind.NONE;
        s.underOrders = r.nextBoolean();
        s.active = ProcedureId.values()[r.nextInt(ProcedureId.values().length)];
        s.targetValid = r.nextBoolean();
        s.targetCategory = Situation.TargetCategory.values()[r.nextInt(4)];
        s.targetDistance = r.nextDouble(0, 300);
        s.targetLos = r.nextBoolean();
        s.targetMotion = Situation.TargetMotion.values()[r.nextInt(3)];
        s.hasDestination = r.nextBoolean();
        s.destDistance = r.nextDouble(0, 300);
        s.healthFrac = r.nextDouble();
        s.evadeHealth = r.nextBoolean() ? 0.3 : 0.0;
        s.ammoFrac = r.nextBoolean() ? 1.0 : 0.0;
        s.armed = r.nextBoolean();
        s.weaponGuided = r.nextBoolean();
        s.inFiringRun = r.nextInt(5) == 0;
        s.autonomous = r.nextBoolean();
        s.engageCycle = r.nextInt(10);
        s.windCeilingOk = r.nextBoolean();
        s.yawStandoffOk = r.nextBoolean();
        s.targetId = r.nextInt(1000);
        s.pilotId = r.nextInt(1000);
        return s;
    }

    private static void perturbGeometry(Situation s, SplittableRandom r) {
        s.targetX = r.nextDouble(-500, 500);
        s.targetY = r.nextDouble(0, 200);
        s.targetZ = r.nextDouble(-500, 500);
        s.targetVx = r.nextDouble(-30, 30);
        s.destX = r.nextDouble(-500, 500);
        s.holdY = r.nextDouble(0, 200);
        s.padX = r.nextDouble(-500, 500);
        s.cruiseY = r.nextDouble(0, 200);
        s.groundRef = r.nextDouble(0, 200);
        s.anchorX = r.nextDouble(-500, 500);
        s.seed = r.nextLong();
        s.weaponRange = r.nextDouble(10, 300);
        s.hullVx = r.nextDouble(-30, 30);
        s.targetHullId = r.nextInt();
    }

    // --- S8, S9 ------------------------------------------------------------------------------------

    private static void pick8e() {
        int loops = 0, longest = 0, run = 0;
        boolean prev = false;
        for (int c = 0; c < 10_000; c++) {
            boolean v = ModeSelector.pick8e(c, 7, 99);
            if (!v) loops++;
            run = c > 0 && v == prev ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = v;
        }
        double share = loops / 10_000.0;
        assert share >= 0.45 && share <= 0.55 : "S8: FIRE_LOOP share " + share;
        assert longest <= 16 : "S8: a run of " + longest + " identical picks";

        long[] bits = new long[5];
        for (int p = 0; p < 5; p++) {
            for (int c = 0; c < 64; c++) if (ModeSelector.pick8e(c, p, 12345)) bits[p] |= 1L << c;
        }
        int lo = 64, hi = 0;
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                int d = Long.bitCount(bits[a] ^ bits[b]);
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
                assert d >= 20 && d <= 44 : "S9: pilots " + a + "/" + b + " Hamming " + d;
            }
        }
        System.out.printf(Locale.ROOT, "  S8/S9: loop share %.3f, longest run %d, pilot-pair Hamming %d..%d of 64%n",
                share, longest, lo, hi);
    }

    // --- S6 ----------------------------------------------------------------------------------------

    private static void parity() {
        for (int id = 0; id <= 1000; id++) {
            Action ground = TacticalBrain.preferredFlankOf(id);
            int expected = ground == Action.FLANK_LEFT ? 1 : -1;
            assert Parity.side(id) == expected : "S6: pilot " + id + " goes " + Parity.side(id) + ", ground crews " + ground;
        }
    }

    // --- S5 ----------------------------------------------------------------------------------------

    /** Random situation streams through the real stack: no attack-to-attack switch without a completion or a hand-off. */
    private static void transitionRule() {
        Airframe af = Sim.airframe("light");
        SplittableRandom rng = new SplittableRandom(0x55L);
        int switches = 0, attackSwaps = 0;
        for (int trial = 0; trial < 20; trial++) {
            Sim sim = new Sim(af).hover(0, 40, 0, 0);
            ProcedureStack stack = sim.core.stack;
            java.util.Map<HeliProcedure, Integer> begunOn = new java.util.IdentityHashMap<>();
            for (long tick = 0; tick < 600; tick++) {
                Situation s = randomSummary(rng);
                s.order = OrderKind.NONE;
                s.targetId = TARGET_ID + rng.nextInt(2);
                s.targetX = 60 * rng.nextDouble(-1, 1);
                s.targetZ = 60 * rng.nextDouble(-1, 1);
                s.cruiseY = 40;
                s.time = tick / 20.0;
                HeliProcedure before = stack.active();
                ModeSelector.Precedence cls = stack.activeClass();
                boolean done = before != null && stack.complete(sim.s, s, s.time, tick);
                ModeSelector.Choice c = ModeSelector.select(s);
                stack.request(c, sim.s, s, s.time, tick);
                for (int k = 0; k < 6; k++) stack.refAt((6.0 * tick + k) / 120.0, sim.s, s);
                if (stack.active() != before) begunOn.put(stack.active(), s.targetId);
                if (before != null && stack.active() != before) {
                    switches++;
                    if (cls == ModeSelector.Precedence.ATTACK && stack.activeClass() == ModeSelector.Precedence.ATTACK) {
                        attackSwaps++;
                        assert done || (s.targetValid && s.targetId != begunOn.get(before))
                                : "S5: " + before.id() + " replaced by " + stack.activeId() + " without completing";
                    }
                    assert stack.activeClass() != ModeSelector.Precedence.ATTACK || stack.activeId() != ProcedureId.HOVER_HOLD
                            : "S5: a hover fallback kept the attack class";
                }
            }
        }
        System.out.printf(Locale.ROOT, "  S5: %d switches, %d attack-to-attack, every one after a completion or a hand-off%n",
                switches, attackSwaps);
    }

    // --- D4, R1 (patrol) ---------------------------------------------------------------------------

    private static void patrol() {
        Airframe af = Sim.airframe("light");
        patrolClosure(af);
        // D4 + R1: the same seed builds the same loop; a lap closes C1 at every node.
        Sim a = new Sim(af).hover(0, 40, 0, 0), b = new Sim(af).hover(0, 40, 0, 0);
        Situation s = new Situation();
        s.autonomous = true;
        s.anchorX = 10;
        s.anchorZ = -20;
        s.seed = 0xC0FFEEL;
        s.cruiseY = 40;
        List<Vector3d> pa = new ArrayList<>(), pb = new ArrayList<>();
        double worstDp = 0, worstDv = 0, farthest = 0;
        HeliReference[] prev = {null};
        double[] worst = {0, 0};
        for (long tick = 0; tick < 20 * 180; tick++) {
            a.tickGuided(s, tick, r -> {
                if (prev[0] != null) {
                    worst[0] = Math.max(worst[0], new Vector3d(r.p()).distance(prev[0].p()));
                    worst[1] = Math.max(worst[1], new Vector3d(r.v()).distance(prev[0].v()));
                }
                prev[0] = r;
            });
            Situation s2 = new Situation();
            s2.autonomous = true;
            s2.anchorX = 10;
            s2.anchorZ = -20;
            s2.seed = 0xC0FFEEL;
            s2.cruiseY = 40;
            b.tickGuided(s2, tick, null);
            pa.add(new Vector3d(a.s.p));
            pb.add(new Vector3d(b.s.p));
            farthest = Math.max(farthest, Math.hypot(a.s.p.x - 10, a.s.p.z + 20));
            assert a.core.stack.activeId() == ProcedureId.PATROL : "patrol: left the patrol (" + a.core.stack.activeId() + ")";
        }
        for (int i = 0; i < pa.size(); i++) assert pa.get(i).equals(pb.get(i)) : "D4: same seed, different flight at tick " + i;
        worstDp = worst[0];
        worstDv = worst[1];
        assert worstDp <= (af.vMaxH + 1.0) * H : "R1 patrol: reference stepped " + worstDp;
        assert worstDv <= 3.0 * Sim.G * H : "R1 patrol: reference velocity stepped " + worstDv;
        assert farthest <= af.patrolRMax + 2 * Math.max(af.minTurnRadius, af.cruiseSpeed * af.cruiseSpeed / af.aLatMax)
                : "patrol: wandered " + farthest + " m from its anchor";
        System.out.printf(Locale.ROOT, "  D4/R1 patrol: 180 s, identical twice, never left PATROL, max %.0f m from anchor, "
                + "reference step %.3f m / %.3f m/s%n", farthest, worstDp, worstDv);
    }

    /** R1: every leg ends exactly on the next leg's start pose, so a lap closes C1 (plan: 1e-6 m, 1e-9 rad). */
    private static void patrolClosure(Airframe af) {
        try {
            HeliProcedure p = com.neoalive.tacz_sewv.heli.guidance.Procedures.create(ProcedureId.PATROL, af, Sim.G);
            java.lang.reflect.Method build = p.getClass().getDeclaredMethod("build", double.class, double.class, long.class);
            build.setAccessible(true);
            java.lang.reflect.Field legsF = p.getClass().getDeclaredField("legs");
            legsF.setAccessible(true);
            SplittableRandom rng = new SplittableRandom(0xD4L);
            double worstP = 0, worstH = 0;
            for (int i = 0; i < 500; i++) {
                build.invoke(p, rng.nextDouble(-1000, 1000), rng.nextDouble(-1000, 1000), rng.nextLong());
                com.neoalive.tacz_sewv.heli.guidance.DubinsPlanar.Path[] legs =
                        (com.neoalive.tacz_sewv.heli.guidance.DubinsPlanar.Path[]) legsF.get(p);
                double[] end = new double[5], start = new double[5];
                for (int k = 0; k < legs.length; k++) {
                    legs[k].sample(legs[k].length(), end);
                    legs[(k + 1) % legs.length].sample(0.0, start);
                    worstP = Math.max(worstP, Math.hypot(end[0] - start[0], end[1] - start[1]));
                    worstH = Math.max(worstH, Math.abs(Math.atan2(end[2] * start[3] - end[3] * start[2],
                            end[2] * start[2] + end[3] * start[3])));
                }
            }
            assert worstP < 1e-6 && worstH < 1e-9 : "R1 patrol: lap opens by " + worstP + " m / " + worstH + " rad";
            System.out.printf(Locale.ROOT, "  R1 patrol: 500 seeded loops close to %.1e m / %.1e rad at every node%n", worstP, worstH);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("R1 patrol: cannot reach the loop geometry", e);
        }
    }

    // --- R8 ----------------------------------------------------------------------------------------

    /** Seconds of plain hover before a target appears: every attack is entered through a blend, as in game. */
    private static final double WARMUP = 5.0;
    /** A loop entered from a hover must have its nose within 5 deg of the centre by this many seconds. */
    private static final double LOOP_SETTLE = 30.0;

    /** A hover where the hull is: no target. */
    private static Situation hoverAt(Sim sim) {
        Situation s = new Situation();
        s.holdX = sim.s.p.x;
        s.holdY = sim.s.p.y;
        s.holdZ = sim.s.p.z;
        s.cruiseY = 40;
        return s;
    }

    private static Situation targetAt(double x, double z) {
        Situation s = armed();
        s.targetX = x;
        s.targetZ = z;
        s.cruiseY = 40;
        return s;
    }

    private static void envelope() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            Airframe af = Sim.airframe(cls);
            double rho = Airframe.RHO0;
            assert Envelope.windCeiling(af, Sim.G, rho) > 10.0 : "R8 " + cls + ": wind ceiling implausibly low";
            assert Envelope.windOk(af, Sim.G, rho, 0.0) && !Envelope.windOk(af, Sim.G, rho, Envelope.windCeiling(af, Sim.G, rho))
                    : "R8 " + cls + ": wind gate is not 0.8 w_ceil";
            Situation s = targetAt(100, 0);
            assert Envelope.yawOk(af, Sim.G, rho, s, 0, 0) : "R8 " + cls + ": a static target must be in the yaw envelope";
            s.targetVz = 200.0;
            assert !Envelope.yawOk(af, Sim.G, rho, s, 0, 0) : "R8 " + cls + ": a 200 m/s crossing target cannot be";
            Sim sim = new Sim(af).hover(0, 40, 0, 0);
            assert !com.neoalive.tacz_sewv.heli.guidance.Procedures.create(ProcedureId.FIRE_STILL, af, Sim.G).canBegin(sim.s, s)
                    : "R8 " + cls + ": FireStill began outside its yaw envelope";
            // fireWindow is the pitch-free cone widened by the fire cone.
            double hi = af.coneHi + s.fireCone, lo = af.coneLo - s.fireCone;
            assert Envelope.inCone(af, 100 * Math.tan(hi - 0.01), 0, 100, s.fireCone)
                    && !Envelope.inCone(af, 100 * Math.tan(hi + 0.01), 0, 100, s.fireCone)
                    && !Envelope.inCone(af, 100 * Math.tan(lo - 0.01), 0, 100, s.fireCone) : "R8 " + cls + ": cone edges";
            // The elevation solve: altitude within its clamps, the target inside the cone at the result.
            Situation deep = targetAt(0, 0);
            deep.targetY = 0;
            deep.groundRef = 60; // a ridge round a valley target
            double[] st = Envelope.station(af, deep, Envelope.STANDOFF_MIN);
            assert st[1] >= deep.groundRef + Envelope.STATION_CLEARANCE - 1e-9 : "R8 " + cls + ": station under the ridge";
            assert st[0] <= deep.weaponRange + 1e-9 : "R8 " + cls + ": standoff past weapon range";
            assert Envelope.inCone(af, st[1], deep.targetY, st[0], deep.fireCone) || st[0] >= deep.weaponRange - 1e-9
                    : "R8 " + cls + ": the solve left the target outside the cone";
        }
        System.out.println("  R8: wind ceiling, yaw envelope gates FireStill, cone edges, elevation solve");
    }

    // --- Attack procedures flown closed-loop -------------------------------------------------------

    private static double noseError(Sim sim, double tx, double tz) {
        Vector3d f = sim.s.q.transform(new Vector3d(0, 0, 1));
        double nose = Math.atan2(-f.x, f.z), los = Math.atan2(-(tx - sim.s.p.x), tz - sim.s.p.z);
        return Math.abs(Math.toDegrees(Math.IEEEremainder(nose - los, 2 * Math.PI)));
    }

    /**
     * FireStill against a parked tank: captures the solved station, then the reference is still
     * (no drift across the dwell re-begins, even with the terrain under the hull read differently
     * every tick), the hull holds it with a steady pitch (no limit cycle), nose on, fire window open.
     */
    private static void fireStill(String cls) {
        Airframe af = Sim.airframe(cls);
        Sim sim = new Sim(af).hover(-90, 30, 0, -90);
        SplittableRandom terrain = new SplittableRandom(0xF5L);
        double worstRange = 0, worstNose = 0, standoff = Double.NaN, drift = 0, refSpeed = 0, captured = 0;
        double worstBore = 0, coneDeg = Double.NaN, drop = 0;
        double pitchLo = Double.MAX_VALUE, pitchHi = -Double.MAX_VALUE;
        Vector3d anchor = null;
        boolean window = true;
        for (long tick = 0; tick < 20 * 60; tick++) {
            double t = tick / 20.0;
            Situation s = t < WARMUP ? hoverAt(sim) : targetAt(0, 0);
            s.weaponGuided = true;
            s.targetCategory = Situation.TargetCategory.VEHICLE;
            s.targetMotion = Situation.TargetMotion.STATIC;
            s.weaponRange = 160;
            s.cruiseY = 40 + terrain.nextDouble(-5, 5); // the surface under a moving hull, re-read each tick
            sim.tickGuided(s, tick, null);
            if (t < WARMUP) continue;
            if (Double.isNaN(standoff)) {
                assert sim.core.stack.activeId() == ProcedureId.FIRE_STILL : "FireStill " + cls + ": got " + sim.core.stack.activeId();
                double[] st = Envelope.station(af, s, Envelope.baseStandoff(af, Sim.G, Airframe.RHO0, s, sim.s.p.x, sim.s.p.z));
                standoff = Math.max(st[0], 2.0 * Math.abs(st[1] - s.targetY));
                coneDeg = Math.toDegrees(s.fireCone);
            }
            HeliReference r = sim.core.lastRef;
            double v = new Vector3d(r.v()).length();
            if (v >= 0.05) captured = t - WARMUP; // the last moment the reference was still moving
            if (t >= WARMUP + 15) {
                if (anchor == null) anchor = new Vector3d(r.p());
                drift = Math.max(drift, anchor.distance(r.p()));
                refSpeed = Math.max(refSpeed, v);
                worstRange = Math.max(worstRange, Math.abs(Math.hypot(sim.s.p.x, sim.s.p.z) - standoff));
                worstNose = Math.max(worstNose, noseError(sim, 0, 0));
                Vector3d f = sim.s.q.transform(new Vector3d(0, 0, 1));
                // What the fire gate judges: the nose (boresight) against the line to the target, in 3D.
                Vector3d los = new Vector3d(-sim.s.p.x, -sim.s.p.y, -sim.s.p.z).normalize();
                worstBore = Math.max(worstBore, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, f.dot(los))))));
                drop = sim.s.p.y;
                double pitch = Math.toDegrees(Math.asin(-f.y));
                pitchLo = Math.min(pitchLo, pitch);
                pitchHi = Math.max(pitchHi, pitch);
                window &= sim.core.stack.fireWindow(sim.s, s);
            }
        }
        assert drift < 0.01 && refSpeed < 1e-3 : "FireStill " + cls + ": station moved " + drift + " m (ref speed " + refSpeed + ")";
        assert worstRange < 2.0 : "FireStill " + cls + ": station error " + worstRange;
        assert worstNose < 5.0 : "FireStill " + cls + ": nose error " + worstNose;
        assert pitchHi - pitchLo < 1.0 : "FireStill " + cls + ": pitch cycles " + (pitchHi - pitchLo) + " deg peak to peak";
        assert window : "FireStill " + cls + ": fire window closed on station";
        assert worstBore < coneDeg : "FireStill " + cls + ": boresight " + worstBore + " deg off the target, cone " + coneDeg;
        System.out.printf(Locale.ROOT, "  FS %s: parked tank, standoff %.1f m for a %.1f m drop, boresight <= %.1f deg off the target "
                        + "(cone %.0f), capture %.1f s, drift %.4f m, hold +-%.2f m, nose +-%.2f deg, pitch %.2f deg p-p%n",
                cls, standoff, drop, worstBore, coneDeg, captured, drift, worstRange, worstNose, pitchHi - pitchLo);
    }

    /** R3: FireLoop round a 5 m/s target: radius, nose on the centre, orbit sense = parity. */
    private static void fireLoop(String cls) {
        Airframe af = Sim.airframe(cls);
        for (int pilot : new int[] {0, 1}) {
            Situation probe = targetAt(0, 0);
            probe.targetVx = 5.0;
            double radius = com.neoalive.tacz_sewv.heli.guidance.Procedures.loopRadius(af, Sim.G, probe, 0, 60);
            Sim sim = new Sim(af).hover(0, 15, radius, 180);
            double worstR = 0, worstNose = 0, swept = 0, prevTh = Double.NaN, worstAt = 0, settled = 0;
            for (long tick = 0; tick < 20 * 95; tick++) {
                double t = tick / 20.0, tx = 5.0 * Math.max(0.0, t - WARMUP);
                Situation s = t < WARMUP ? hoverAt(sim) : targetAt(tx, 0);
                s.targetVx = t < WARMUP ? 0.0 : 5.0;
                s.targetLos = false; // row 8b: the aspect-changing orbit
                s.pilotId = pilot;
                sim.tickGuided(s, tick, null);
                if (t < WARMUP) continue;
                double rx = sim.s.p.x - (tx + 5.0 / 20.0), rz = sim.s.p.z, th = Math.atan2(rx, rz);
                if (!Double.isNaN(prevTh)) swept += Math.IEEEremainder(th - prevTh, 2 * Math.PI);
                prevTh = th;
                if (noseError(sim, tx + 0.25, 0) >= 5.0) settled = t - WARMUP;
                if (t >= WARMUP + LOOP_SETTLE) {
                    double err = Math.abs(Math.hypot(rx, rz) - radius);
                    if (err > worstR) worstAt = t;
                    worstR = Math.max(worstR, err);
                    worstNose = Math.max(worstNose, noseError(sim, tx + 0.25, 0));
                }
            }
            assert settled < LOOP_SETTLE : "R3 " + cls + "/" + pilot + ": nose still off 5 deg " + settled + " s into the loop";
            assert worstR < 2.0 : "R3 " + cls + "/" + pilot + ": radius error " + worstR + " at " + worstAt + " s";
            assert worstNose < 5.0 : "R3 " + cls + "/" + pilot + ": nose error " + worstNose;
            assert Math.signum(swept) == Parity.side(pilot) : "R3 " + cls + "/" + pilot + ": orbit sense " + swept;
            System.out.printf(Locale.ROOT, "  R3 %s pilot %d: R_o %.1f m, entry settles in %.1f s, then radius +-%.2f m, nose +-%.2f deg, "
                    + "swept %.0f deg (parity %+d)%n", cls, pilot, radius, settled, worstR, worstNose, Math.toDegrees(swept), Parity.side(pilot));
        }
    }

    /** R4: three FireRun passes on a static target: on the axis in the window, exits rotating by reattack. */
    private static void fireRuns(String cls) {
        Airframe af = Sim.airframe(cls);
        Sim sim = new Sim(af).hover(-150, 34, 0, -90);
        List<Double> attackHeadings = new ArrayList<>();
        double worstHeading = 0, worstCross = 0, window = 0, longestWindow = 0, minClear = Double.MAX_VALUE;
        FirePhase last = FirePhase.NONE;
        for (long tick = 0; tick < 20 * 150 && attackHeadings.size() < 3; tick++) {
            Situation s = targetAt(0, 0);
            s.targetMotion = Situation.TargetMotion.FAST; // row 8d: a run
            sim.tickGuided(s, tick, null);
            FirePhase ph = sim.core.stack.firePhase();
            minClear = Math.min(minClear, sim.s.p.y);
            if (ph == FirePhase.ATTACK) {
                double vh = Math.hypot(sim.s.v.x, sim.s.v.z);
                double ux = sim.s.v.x / vh, uz = sim.s.v.z / vh, rx = -sim.s.p.x, rz = -sim.s.p.z;
                worstCross = Math.max(worstCross, Math.abs(rx * uz - rz * ux));
                worstHeading = Math.max(worstHeading, noseError(sim, 0, 0));
                window += 1.0 / 20.0;
                if (last != FirePhase.ATTACK) attackHeadings.add(Math.atan2(ux, uz));
            } else if (last == FirePhase.ATTACK) {
                longestWindow = Math.max(longestWindow, window);
                window = 0;
            }
            last = ph;
        }
        assert attackHeadings.size() == 3 : "R4 " + cls + ": only " + attackHeadings.size() + " passes in 150 s";
        assert worstHeading < 5.0 : "R4 " + cls + ": heading error in the window " + worstHeading;
        assert worstCross < 2.0 : "R4 " + cls + ": cross-track in the window " + worstCross;
        int side = Parity.side(0);
        for (int i = 1; i < attackHeadings.size(); i++) {
            double turn = Math.toDegrees(Math.IEEEremainder(attackHeadings.get(i) - attackHeadings.get(i - 1), 2 * Math.PI));
            assert Math.abs(turn - side * Math.toDegrees(af.reattack)) < 10.0
                    : "R4 " + cls + ": pass " + i + " axis turned " + turn + " deg";
        }
        assert minClear > 15.0 : "R4 " + cls + ": dropped to " + minClear + " m";
        System.out.printf(Locale.ROOT, "  R4 %s: 3 passes, window <= %.2f s, heading +-%.2f deg, cross-track +-%.2f m, "
                + "axis steps %+.0f deg, lowest %.1f m%n", cls, longestWindow, worstHeading, worstCross,
                side * Math.toDegrees(af.reattack), minClear);
    }

    /** R5: the target disappears mid-run: C1 across, the run flies its exit within grace + exit, then FreeNav. */
    private static void targetLoss() {
        Airframe af = Sim.airframe("light");
        SplittableRandom rng = new SplittableRandom(0x5AL);
        for (int trial = 0; trial < 5; trial++) {
            double lossAt = rng.nextDouble(2.0, 9.0);
            Sim sim = new Sim(af).hover(-150, 34, 0, -90);
            double[] worst = {0, 0};
            HeliReference[] prev = {null};
            double ended = Double.NaN;
            for (long tick = 0; tick < 20 * 90; tick++) {
                double t = tick / 20.0;
                Situation s = targetAt(0, 0);
                s.targetMotion = Situation.TargetMotion.FAST;
                if (t >= lossAt) {
                    s.targetValid = false;
                    s.targetId = -1;
                }
                sim.tickGuided(s, tick, r -> {
                    if (prev[0] != null) {
                        worst[0] = Math.max(worst[0], new Vector3d(r.p()).distance(prev[0].p()));
                        worst[1] = Math.max(worst[1], new Vector3d(r.v()).distance(prev[0].v()));
                    }
                    prev[0] = r;
                });
                if (t > lossAt && Double.isNaN(ended) && sim.core.stack.activeClass() != ModeSelector.Precedence.ATTACK) ended = t;
            }
            assert !Double.isNaN(ended) : "R5: the run never ended after the target was lost at " + lossAt;
            assert ended - lossAt <= 60.0 : "R5: took " + (ended - lossAt) + " s to finish the exit";
            assert worst[0] <= (af.vMaxH + 0.5 * af.vMaxH + 1.0) * H : "R5: reference stepped " + worst[0];
            assert worst[1] <= 3.0 * Sim.G * H : "R5: reference velocity stepped " + worst[1];
            System.out.printf(Locale.ROOT, "  R5: target lost at %.1f s, run exited by %.1f s, reference step %.3f m / %.3f m/s%n",
                    lossAt, ended, worst[0], worst[1]);
        }
    }

    // --- Froude scaling (Phase 5) ------------------------------------------------------------------

    /**
     * A role class scaled to another hull's size (addon hulls with no row): every scaled row must
     * validate, hover at exactly the reference collective (C_T is invariant under Froude scaling,
     * which is the point of it), and hold a hover closed-loop.
     */
    private static void froude() {
        StringBuilder line = new StringBuilder();
        for (String cls : new String[] {"light", "attack"}) {
            Airframe af = Sim.airframe(cls);
            double ref = com.neoalive.tacz_sewv.heli.physics.RotorModel.hoverCollective(af, Sim.G, Sim.RHO);
            for (double lambda : new double[] {0.5, 0.7, 1.4, 2.0}) {
                Airframe sc = com.neoalive.tacz_sewv.heli.data.AirframeData.scaled(af, lambda);
                List<String> problems = com.neoalive.tacz_sewv.heli.data.AirframeData.validate(sc, Sim.G, Sim.RHO);
                assert problems.isEmpty() : "F " + cls + " x" + lambda + ": " + problems;
                double hc = com.neoalive.tacz_sewv.heli.physics.RotorModel.hoverCollective(sc, Sim.G, Sim.RHO);
                assert Math.abs(hc - ref) < 1e-6 : "F " + cls + " x" + lambda + ": hover collective " + hc + " vs " + ref;
                Sim sim = new Sim(sc).hover(0, 40, 0, 0);
                sim.run(30, Sim.hold(10, 45, 0, 0)); // a 10 m sideways, 5 m up step
                double err = sim.s.p.distance(10, 45, 0);
                assert err < 0.5 : "F " + cls + " x" + lambda + ": step settled " + err + " m off";
                line.append(String.format(Locale.ROOT, " %s x%.1f (%.0f kg) %.2f m;", cls, lambda, sc.mass, err));
            }
        }
        System.out.println("  F Froude: rows validate, hover collective invariant, 30 s after a 10 m step:" + line);
    }

    // --- Engine out (Phase 4) ---------------------------------------------------------------------

    /** The goal's engine-out rule (DriveHelicopterGoal): airborne with the engine stopped or failed. */
    private static void markEngineOut(Sim sim, Situation s) {
        s.engineOut = !sim.onGround && (sim.s.engine == com.neoalive.tacz_sewv.heli.physics.HeliState.Engine.OFF
                || sim.s.engine == com.neoalive.tacz_sewv.heli.physics.HeliState.Engine.FAILED);
        s.groundBelow = sim.groundY;
    }

    /**
     * Fuel runs out in a 100 m transit: AUTOROTATE takes over from the transit and lands under SBW's
     * 8 m/s crash gate with rotor speed to spare (manual check 4 headless). Then an engine merely
     * stopped in the air, with fuel aboard, is restarted and normal flight resumes.
     */
    private static void engineOut(String cls) {
        StringBuilder line = new StringBuilder();
        for (double h : new double[] {40, 100}) {
            double[] r = engineOutFrom(cls, h);
            assert r[0] < 8.0 && r[1] >= 0.6 : "engine out " + cls + " at " + h + " m: touchdown " + r[0] + " m/s, rotor " + r[1];
            line.append(String.format(Locale.ROOT, " from %.0f m: %.2f m/s, rotor %.2f;", h, r[0], r[1]));
        }
        Airframe af = Sim.airframe(cls);
        Sim re = new Sim(af).hover(0, 100, 0, 0);
        re.s.engine = com.neoalive.tacz_sewv.heli.physics.HeliState.Engine.OFF;
        boolean restarted = false;
        double lowest = 100;
        for (long tick = 0; tick < 20 * 30; tick++) {
            Situation s = new Situation();
            s.holdX = 0;
            s.holdY = 100;
            s.holdZ = 0;
            markEngineOut(re, s);
            re.tickGuided(s, tick, null);
            lowest = Math.min(lowest, re.s.p.y);
            restarted |= re.s.engine == com.neoalive.tacz_sewv.heli.physics.HeliState.Engine.RUN
                    && re.core.stack.activeId() == ProcedureId.HOVER_HOLD;
        }
        assert restarted : "engine out " + cls + ": a stopped engine with fuel was not restarted";
        System.out.printf(Locale.ROOT, "  E %s: fuel out in cruise, autorotation touchdown%s stopped engine with fuel restarted "
                + "in the air (lowest %.1f m)%n", cls, line, lowest);
    }

    /** {touchdown sink, rotor speed fraction, sink at the table flare height, that height} after a fuel cut at h0. */
    private static double[] engineOutFrom(String cls, double h0) {
        Airframe af = Sim.airframe(cls);
        Sim sim = new Sim(af).hover(0, h0, 0, 0);
        double entrySink = Double.NaN, entryH = Double.NaN;
        double vTouch = Double.NaN, wTouch = Double.NaN, cutAt = 10.0;
        for (long tick = 0; tick < 20 * 120 && Double.isNaN(vTouch); tick++) {
            double t = tick / 20.0;
            if (t >= cutAt) sim.s.fuel = 0.0;
            Situation s = new Situation();
            s.hasDestination = true;
            s.destX = 0;
            s.destZ = 2000;
            s.destY = h0;
            s.destDistance = 2000 - sim.s.p.z;
            markEngineOut(sim, s);
            double vy = sim.s.v.y;
            sim.tickGuided(s, tick, null);
            if (Double.isNaN(entrySink) && sim.s.p.y + af.cgHeight + af.hubHeight <= af.flareHeight) {
                entrySink = -sim.s.v.y;
                entryH = sim.s.p.y;
            }
            if (t >= cutAt + 0.5) {
                assert sim.core.stack.activeId() == ProcedureId.AUTOROTATE : "engine out " + cls + ": flying " + sim.core.stack.activeId();
            }
            if (sim.onGround) {
                vTouch = -vy;
                wTouch = sim.s.omega / af.omegaN;
            }
        }
        return new double[] {vTouch, wTouch, entrySink, entryH};
    }

    // --- A3, A4 ------------------------------------------------------------------------------------

    /** A3: with the barrier on, the landing still reaches the ground inside its footprint. */
    private static void touchdown() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            Airframe af = Sim.airframe(cls);
            Sim sim = new Sim(af).hover(0, 30, 0, 0);
            sim.barrier = HeliAvoidChecks.barrierFor(af);
            sim.obstacles = new ObstacleSet(List.of(), 0.0);
            boolean settled = false;
            for (long tick = 0; tick < 20 * 120 && !settled; tick++) {
                Situation s = new Situation();
                s.order = OrderKind.LAND;
                s.padX = 40;
                s.padZ = 0;
                s.touchdownY = 0;
                s.transitY = 24;
                sim.tickGuided(s, tick, null);
                settled = sim.onGround && Math.hypot(40 - sim.s.p.x, sim.s.p.z) <= 2.25;
            }
            assert settled : "A3 " + cls + ": the barrier kept the hull off its pad (at " + sim.s.p + ")";
        }
        System.out.println("  A3: every class lands on its pad with the ground barrier armed");
    }

    /** A4: procedures cannot observe avoidance: nothing in guidance reads the avoid package. */
    private static void isolation() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/neoalive/tacz_sewv/heli/guidance"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assert !Files.readString(f).contains("heli.avoid") : "A4: " + f + " can see the avoidance layer";
            }
        }
    }

    // --- Mutual avoidance --------------------------------------------------------------------------

    private interface SitFor {
        Situation at(int hull, long tick);
    }

    private static double gap(Sim a, Sim b) {
        double hw = HeliAvoidChecks.HALF_WIDTH, h = HeliAvoidChecks.HEIGHT;
        double gx = Math.max(0, Math.abs(a.s.p.x - b.s.p.x) - 2 * hw);
        double gz = Math.max(0, Math.abs(a.s.p.z - b.s.p.z) - 2 * hw);
        double gy = Math.max(0, Math.abs(a.s.p.y - b.s.p.y) - h);
        return Math.sqrt(gx * gx + gy * gy + gz * gz);
    }

    private static ObstacleSet.Box boxOf(Sim o, long key) {
        double hw = HeliAvoidChecks.HALF_WIDTH;
        return new ObstacleSet.Box(key, o.s.p.x - hw, o.s.p.y, o.s.p.z - hw, o.s.p.x + hw, o.s.p.y + HeliAvoidChecks.HEIGHT,
                o.s.p.z + hw, o.s.v.x, o.s.v.y, o.s.v.z);
    }

    private static PathProbe.Traffic trafficOf(Sim o, int id) {
        ObstacleSet.Box b = boxOf(o, id);
        return new PathProbe.Traffic(id, true, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), b.vx(), b.vy(), b.vz());
    }

    /**
     * Fly two hulls together, each seeing the other (barrier always, bias when {@code bias}).
     * {min gap, peak barrier g x2, peak bias m x2, peak barrier g x2 from {@code steadyFrom} s on}.
     */
    private static double[] fly(Sim a, Sim b, SitFor sits, int seconds, boolean bias, double steadyFrom) {
        Sim[] hulls = {a, b};
        int[] ids = {10, 11};
        double minGap = Double.MAX_VALUE;
        double[] peakF = {0, 0}, peakBias = {0, 0}, steadyF = {0, 0};
        for (Sim s : hulls) s.barrier = HeliAvoidChecks.barrierFor(s.af);
        for (long tick = 0; tick < 20L * seconds; tick++) {
            for (int i = 0; i < 2; i++) {
                Sim self = hulls[i], other = hulls[1 - i];
                self.obstacles = new ObstacleSet(List.of(boxOf(other, ids[1 - i])), 0.0);
                self.traffic = bias ? List.of(trafficOf(other, ids[1 - i])) : List.of();
                self.selfId = ids[i];
            }
            Situation sa = sits.at(0, tick), sb = sits.at(1, tick);
            a.tickGuided(sa, tick, null);
            b.tickGuided(sb, tick, null);
            minGap = Math.min(minGap, gap(a, b));
            for (int i = 0; i < 2; i++) {
                double gs = hulls[i].avoid.length() / (hulls[i].af.mass * Sim.G);
                peakF[i] = Math.max(peakF[i], gs);
                if (tick >= 20 * steadyFrom) steadyF[i] = Math.max(steadyF[i], gs);
                peakBias[i] = Math.max(peakBias[i], Math.hypot(hulls[i].core.bias.altitude(), hulls[i].core.bias.lateral()));
            }
        }
        return new double[] {minGap, peakF[0], peakF[1], peakBias[0], peakBias[1], steadyF[0], steadyF[1]};
    }

    private static Situation loopSit(int pilot, long tick) {
        Situation s = targetAt(0, 0);
        s.targetLos = false;
        s.pilotId = pilot;
        return s;
    }

    private static void mutualAvoidance(String cls) {
        Airframe af = Sim.airframe(cls);
        Situation probe = targetAt(0, 0);
        double radius = Envelope.station(af, probe, Envelope.baseStandoff(af, Sim.G, Airframe.RHO0, probe, 0, 60))[0];

        // Formation: two wingmen orbiting the same way, 25 deg apart. Once both are on the orbit their
        // velocities match, and the closing-speed band shrinks to r0: the barrier must be silent.
        double off = Math.toRadians(25);
        Sim a = new Sim(af).hover(0, 15, radius, 180);
        Sim b = new Sim(af).hover(radius * Math.sin(off), 15, radius * Math.cos(off), 180);
        double[] f = fly(a, b, (h, t) -> loopSit(h == 0 ? 0 : 2, t), 60, true, LOOP_SETTLE);
        assert f[0] > 0 : "M1 " + cls + ": formation wingmen touched";
        assert f[5] == 0 && f[6] == 0 : "M1 " + cls + ": barrier pushed formation wingmen apart on the orbit (" + f[5] + "/" + f[6] + " g)";
        System.out.printf(Locale.ROOT, "  M1 %s formation FireLoop (same sense, %.0f m apart): min gap %.1f m, barrier on the orbit 0 g "
                + "(entry transient %.3f/%.3f g), bias %.1f/%.1f m%n", cls, 2 * radius * Math.sin(off / 2), f[0], f[1], f[2], f[3], f[4]);

        // Opposing FireLoops (parity 0 and 1) start on opposite sides: head-on twice a lap at 2 V_o.
        for (boolean bias : new boolean[] {false, true}) {
            a = new Sim(af).hover(0, 15, radius, 180);
            b = new Sim(af).hover(0, 15, -radius, 0);
            f = fly(a, b, (h, t) -> loopSit(h, t), 60, bias, 0);
            assert f[0] > 0 : "M2 " + cls + (bias ? " (bias)" : " (barrier only)") + ": opposing orbits collided";
            System.out.printf(Locale.ROOT, "  M2 %s opposing FireLoops, %s: min gap %.1f m, barrier %.2f/%.2f g, bias %.1f/%.1f m%n",
                    cls, bias ? "barrier + bias" : "barrier only", f[0], f[1], f[2], f[3], f[4]);
        }

        // Crossing FireRuns: two passes at 90 deg timed to meet over the target, at the same run altitude.
        for (boolean bias : new boolean[] {false, true}) {
            a = new Sim(af).hover(-150, 34, 0, -90);
            b = new Sim(af).hover(0, 34, -150, 0);
            f = fly(a, b, (h, t) -> {
                Situation s = targetAt(0, 0);
                s.targetMotion = Situation.TargetMotion.FAST;
                s.pilotId = h == 0 ? 0 : 2;
                return s;
            }, 30, bias, 0);
            if (bias) assert f[0] > 0 : "M3 " + cls + ": crossing runs collided with the bias on";
            System.out.printf(Locale.ROOT, "  M3 %s crossing FireRuns (closing %.1f m/s), %s: min gap %.1f m, barrier %.2f/%.2f g, bias %.1f/%.1f m%s%n",
                    cls, af.runSpeed * Math.sqrt(2), bias ? "barrier + bias" : "barrier only", f[0], f[1], f[2], f[3], f[4],
                    !bias && f[0] <= 0 ? "  FINDING: barrier alone does not hold (closing speed above its sizing)" : "");
        }
    }
}
