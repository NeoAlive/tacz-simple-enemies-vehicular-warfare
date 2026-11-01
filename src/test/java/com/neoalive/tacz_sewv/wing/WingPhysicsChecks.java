package com.neoalive.tacz_sewv.wing;

import java.util.Map;

import com.neoalive.tacz_sewv.wing.data.WingAirframeData;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingControl;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingState;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/** W1-W10: the airframe model, open loop. */
final class WingPhysicsChecks {

    private WingPhysicsChecks() {}

    static void run() {
        Map<String, WingAirframe> classes = WingSim.table().classes();
        assert WingSim.table().warnings().isEmpty() : "shipped table warns: " + WingSim.table().warnings();
        for (String cls : new String[] {"fighter_jet", "attack_jet", "dive_bomber", "light_prop", "gunship"}) {
            assert classes.containsKey(cls) : "W0: shipped table lacks class " + cls;
        }
        for (WingAirframe af : classes.values()) {
            trimHolds(af);
            stallCurve(af);
            glide(af);
            fastStable(af);
            authorityScalesWithQbar(af);
            damping(af);
            signs(af);
        }
        validationBites(classes.get("fighter_jet"));
        degraded(classes.get("attack_jet"));
        fuel(classes.get("attack_jet"));
    }

    /** W1: trims exist across the envelope, and the open-loop sim actually holds one. */
    private static void trimHolds(WingAirframe af) {
        double vs = AeroModel.stallSpeed(af, WingSim.G, WingSim.RHO, 0.0);
        for (double v : new double[] {1.3 * vs, af.cruiseSpeed, 0.95 * af.vMax}) {
            WingTrim.Result r = WingTrim.solve(af, WingSim.G, WingSim.RHO, v, 0.0, 0.0, 0.0, 0.0);
            assert r.feasible() : "W1: " + af.name + " has no trim at " + v + ": " + r;
        }
        WingSim sim = new WingSim(af);
        sim.trimAt(af.cruiseSpeed, 500.0);
        sim.run(10.0);
        assert Math.abs(sim.s.p.y - 500.0) < 3.0 : "W1: " + af.name + " trimmed cruise drifted " + (sim.s.p.y - 500.0) + " m";
        assert Math.abs(sim.speed() - af.cruiseSpeed) < 1.0 : "W1: " + af.name + " trimmed speed drifted to " + sim.speed();
        assert sim.s.w.length() < 0.02 : "W1: " + af.name + " trimmed cruise is rotating " + sim.s.w;
    }

    /** W2: the lift curve peaks at the stall, breaks smoothly and stays broken. */
    private static void stallCurve(WingAirframe af) {
        double[] peak = AeroModel.clMax(af, 0.0);
        assert Math.abs(StrictMath.toDegrees(peak[1] - af.alphaStall)) < 2.0
                : "W2: " + af.name + " CL peaks at " + StrictMath.toDegrees(peak[1]) + " deg";
        double prev = AeroModel.cl(af, StrictMath.toRadians(-30.0), 0.0);
        for (int i = 1; i <= 1800; i++) {
            double c = AeroModel.cl(af, StrictMath.toRadians(-30.0 + i * 0.05), 0.0);
            assert Math.abs(c - prev) < 0.03 : "W2: " + af.name + " CL jumps at " + (-30.0 + i * 0.05) + " deg";
            prev = c;
        }
        double post = AeroModel.cl(af, af.alphaStall + StrictMath.toRadians(8.0), 0.0);
        assert post < 0.85 * peak[0] : "W2: " + af.name + " does not lose lift past the stall (" + post + ")";
        assert AeroModel.cl(af, StrictMath.toRadians(5.0), 1.0) > AeroModel.cl(af, StrictMath.toRadians(5.0), 0.0)
                : "W2: flaps must add lift";
    }

    /**
     * W3: engine out at the row's own best-glide speed (which keeps a stall margin; best L/D alpha
     * itself sits close to the break on these draggy Minecraft-scale wings), started on the glide
     * equilibrium, the aircraft glides at that alpha's L/D.
     */
    private static void glide(WingAirframe af) {
        double vs = AeroModel.stallSpeed(af, WingSim.G, WingSim.RHO, 0.0);
        double v = Math.max(af.bestGlide, 1.3 * vs);
        double clNeed = 2.0 * af.mass * WingSim.G / (WingSim.RHO * af.area * v * v);
        double lo = StrictMath.toRadians(-10.0), hi = af.alphaStall;
        for (int k = 0; k < 60; k++) {
            double m = 0.5 * (lo + hi);
            if (AeroModel.cl(af, m, 0.0) < clNeed) lo = m;
            else hi = m;
        }
        double alpha = 0.5 * (lo + hi);
        double ld = AeroModel.cl(af, alpha, 0.0) / AeroModel.cd(af, alpha, 0.0, 0.0, 0.0, true);
        double gamma = -StrictMath.atan(1.0 / ld);
        WingSim sim = new WingSim(af);
        sim.trimAt(v, 3000.0);
        sim.s.v.set(0.0, v * StrictMath.sin(gamma), v * StrictMath.cos(gamma));
        sim.s.q.set(com.neoalive.tacz_sewv.heli.physics.McPose.toQuat(0.0, -StrictMath.toDegrees(alpha + gamma), 0.0));
        sim.s.throttle = sim.u.throttle = 0.0;
        sim.u.engineOn = false;
        sim.u.elevator = -(af.cm0 + af.cmAlpha * alpha) / af.cmDe;
        sim.run(20.0);
        double y0 = sim.s.p.y, x0 = sim.s.p.x, z0 = sim.s.p.z;
        sim.run(60.0);
        double drop = y0 - sim.s.p.y;
        double run = StrictMath.hypot(sim.s.p.x - x0, sim.s.p.z - z0);
        double got = run / drop;
        assert drop > 0.0 && Math.abs(got - ld) < 0.15 * ld
                : "W3: " + af.name + " glides at " + got + ", expected " + ld;
    }

    /** W4: two minutes at near top speed, open loop, stays bounded. */
    private static void fastStable(WingAirframe af) {
        WingSim sim = new WingSim(af);
        double v0 = 0.95 * af.vMax;
        sim.trimAt(v0, 3000.0);
        double maxRate = 0.0;
        for (int i = 0; i < 20 * 120; i++) {
            sim.tick();
            assert sim.finite() : "W4: " + af.name + " went non-finite at tick " + i;
            maxRate = Math.max(maxRate, sim.s.w.length());
        }
        assert maxRate < 1.0 : "W4: " + af.name + " body rates reached " + maxRate;
        assert sim.speed() > 0.7 * v0 && sim.speed() < 1.3 * v0 : "W4: " + af.name + " speed wandered to " + sim.speed();
    }

    /** W5: control power is proportional to dynamic pressure. */
    private static void authorityScalesWithQbar(WingAirframe af) {
        double vs = AeroModel.stallSpeed(af, WingSim.G, WingSim.RHO, 0.0);
        double v1 = 1.3 * vs, v2 = 0.9 * af.vMax;
        double a1 = rollAccel(af, v1), a2 = rollAccel(af, v2);
        double expected = (v2 / v1) * (v2 / v1);
        assert Math.abs(a2 / a1 - expected) < 0.15 * expected
                : "W5: " + af.name + " roll authority ratio " + (a2 / a1) + ", expected " + expected;
    }

    private static double rollAccel(WingAirframe af, double v) {
        WingSim sim = new WingSim(af);
        sim.trimAt(v, 1000.0);
        sim.u.aileron = 1.0;
        sim.physics.step(sim.s, sim.u, new WingEnv(WingSim.G, WingSim.RHO, sim.wind, Double.NEGATIVE_INFINITY, false));
        return sim.s.w.z / WingPhysics.H;
    }

    /** W6: a rate disturbance dies out with the sticks neutral. */
    private static void damping(WingAirframe af) {
        WingSim sim = new WingSim(af);
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.s.w.z = 1.0;
        sim.run(2.0);
        assert Math.abs(sim.s.w.z) < 0.1 : "W6: " + af.name + " roll rate still " + sim.s.w.z;
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.s.w.x = 0.5;
        sim.run(2.0);
        assert Math.abs(sim.s.w.x) < 0.05 : "W6: " + af.name + " pitch rate still " + sim.s.w.x;
    }

    /** W7: pilot signs. Pull is nose up, right aileron is starboard down, right rudder is nose right. */
    private static void signs(WingAirframe af) {
        WingSim sim = new WingSim(af);
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.u.elevator += 0.3;
        sim.tick();
        assert sim.s.w.x < 0.0 : "W7: " + af.name + " pull must pitch the nose up (wx < 0)";
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.u.aileron = 0.5;
        sim.tick();
        assert sim.s.w.z > 0.0 : "W7: " + af.name + " right aileron must roll starboard down (wz > 0)";
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.u.rudder = 0.5;
        sim.tick();
        assert sim.s.w.y < 0.0 : "W7: " + af.name + " right rudder must yaw the nose right (wy < 0)";
    }

    /** W8: the load-time checks reject rows that cannot fly. */
    private static void validationBites(WingAirframe af) {
        double g = WingAirframeData.CHECK_GRAVITY, rho = WingAirframe.RHO0;
        assert WingAirframeData.validate(af, g, rho).isEmpty() : "W8: shipped row must validate";
        assert !WingAirframeData.validate(af.with(Map.of("stability.cmAlpha", 0.3)), g, rho).isEmpty()
                : "W8: a pitch-unstable row must be rejected";
        assert !WingAirframeData.validate(af.with(Map.of("wing.cd0", 0.6)), g, rho).isEmpty()
                : "W8: a row that cannot reach vMax must be rejected";
        assert !WingAirframeData.validate(af.with(Map.of("inertia.0", 1.0)), g, rho).isEmpty()
                : "W8: a row too stiff for the sub-step must be rejected";
    }

    /** W9: a degraded hull loses its engine and most of its authority. */
    private static void degraded(WingAirframe af) {
        WingSim a = new WingSim(af), b = new WingSim(af);
        a.trimAt(af.cruiseSpeed, 1000.0);
        b.trimAt(af.cruiseSpeed, 1000.0);
        b.s.degraded = true;
        a.u.aileron = b.u.aileron = 1.0;
        a.tick();
        b.tick();
        assert b.s.engine == WingState.Engine.FAILED : "W9: degraded must fail the engine";
        assert b.s.w.z < 0.6 * a.s.w.z : "W9: degraded must cut aileron authority";
    }

    /** W10: full throttle burns SBW's full-sprint rate. */
    private static void fuel(WingAirframe af) {
        WingSim sim = new WingSim(af);
        sim.trimAt(af.cruiseSpeed, 1000.0);
        sim.s.fuel = 1.0E9;
        sim.s.throttle = sim.u.throttle = 1.0;
        sim.run(1.0);
        double burned = 1.0E9 - sim.s.fuel;
        double expected = 20.0 * WingSim.ENERGY_COST_RATE * WingPhysics.SBW_FULL_POWER;
        assert Math.abs(burned - expected) < 0.01 * expected : "W10: burned " + burned + ", expected " + expected;
        WingControl off = sim.u;
        off.engineOn = false;
        sim.tick();
        assert sim.s.engine == WingState.Engine.OFF : "W10: engine must stop when commanded off";
    }
}
