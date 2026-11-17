package com.neoalive.tacz_sewv.wing;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.wing.control.WingCore;
import com.neoalive.tacz_sewv.wing.guidance.WingReference;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/** Closed-loop harness: the real {@link WingCore} (controller + physics) against a test reference. */
final class CtlSim {

    final WingAirframe af;
    final WingCore core;
    final Vector3d wind = new Vector3d();
    final double vs;
    long tick;

    CtlSim(String cls) {
        this(WingSim.table().classes().get(cls));
    }

    CtlSim(WingAirframe af) {
        this.af = af;
        this.core = new WingCore(af, WingSim.G, WingSim.RHO, WingSim.ENERGY_COST_RATE);
        this.vs = AeroModel.stallSpeed(af, WingSim.G, WingSim.RHO, 0.0);
    }

    /** Level trim at {@code v}, at {@code p}, heading south (+Z), gear up. */
    CtlSim start(double v, double x, double y, double z) {
        WingTrim.Result r = WingTrim.solve(af, WingSim.G, WingSim.RHO, v, 0.0, 0.0, 0.0, 0.0);
        core.s.p.set(x, y, z);
        core.s.v.set(0.0, 0.0, v);
        core.s.q.set(McPose.toQuat(0.0, -StrictMath.toDegrees(r.alpha()), 0.0));
        core.s.w.zero();
        core.s.gear = 0.0;
        core.u.gearDown = false;
        core.s.throttle = core.u.throttle = Math.max(0.0, Math.min(1.0, r.throttle()));
        core.u.elevator = r.elevator();
        core.ctl.reset();
        return this;
    }

    void tick(WingCore.ReferenceSource src) {
        core.integrate(new WingEnv(WingSim.G, WingSim.RHO, wind, Double.NEGATIVE_INFINITY, false), src, tick);
        tick++;
    }

    /** One tick of the full stack: selector, transition rule, procedure, terrain guard, controller, physics. */
    void fly(com.neoalive.tacz_sewv.wing.guidance.WingSituation sit, java.util.function.DoubleBinaryOperator ground) {
        sit.time = tick / 20.0;
        sit.active = core.stack.activeId();
        sit.orbitRadius = com.neoalive.tacz_sewv.wing.guidance.WingProcedures.orbitRadius(af, WingSim.G);
        sampleFloor(sit, ground);
        core.guide(com.neoalive.tacz_sewv.wing.guidance.WingModeSelector.select(sit), sit, sit.time, tick);
        core.fly(new WingEnv(WingSim.G, WingSim.RHO, wind, Double.NEGATIVE_INFINITY, false), sit, tick);
        tick++;
    }

    /** The goal's floor sampling, against a test terrain function (x, z) -> ground height. */
    void sampleFloor(com.neoalive.tacz_sewv.wing.guidance.WingSituation sit, java.util.function.DoubleBinaryOperator ground) {
        if (ground == null) {
            sit.floor = new double[0];
            return;
        }
        var s = core.s;
        double vh = Math.hypot(s.v.x, s.v.z);
        double ux = vh > 1.0E-3 ? s.v.x / vh : 0.0, uz = vh > 1.0E-3 ? s.v.z / vh : 1.0;
        sit.floorStep = Math.max(8.0, vh * com.neoalive.tacz_sewv.wing.guidance.WingSituation.FLOOR_SECONDS_PER_SAMPLE);
        sit.floor = new double[com.neoalive.tacz_sewv.wing.guidance.WingSituation.FLOOR_SAMPLES];
        for (int i = 0; i < sit.floor.length; i++) {
            double d = (i + 1) * sit.floorStep;
            sit.floor[i] = ground.applyAsDouble(s.p.x + ux * d, s.p.z + uz * d);
        }
    }

    double t() {
        return tick / 20.0;
    }

    double speed() {
        return core.s.v.distance(wind);
    }

    double bankDeg() {
        return McPose.toEuler(core.s.q, null).rollDeg();
    }

    boolean finite() {
        var s = core.s;
        return Double.isFinite(s.p.x + s.p.y + s.p.z + s.v.x + s.v.y + s.v.z + s.q.x + s.q.y + s.q.z + s.q.w
                + s.w.x + s.w.y + s.w.z);
    }

    // --- Test references ------------------------------------------------------------------------

    /** Straight and level southbound from (x0, h, z0) at speed v; altitude switches to h1 at t1. */
    static WingCore.ReferenceSource line(double x0, double h, double z0, double v, double t1, double h1) {
        return (t, s) -> WingReference.flypath(t, new Vector3d(x0, t < t1 ? h : h1, z0 + v * t),
                new Vector3d(0.0, 0.0, v), new Vector3d());
    }

    /** Right-hand circle about (cx, h, cz), radius r, speed v, starting due east of the centre. */
    static WingCore.ReferenceSource circle(double cx, double h, double cz, double r, double v) {
        double w = v / r;
        return (t, s) -> {
            double th = w * t, c = StrictMath.cos(th), sn = StrictMath.sin(th);
            return WingReference.flypath(t, new Vector3d(cx + r * c, h, cz + r * sn),
                    new Vector3d(-v * sn, 0.0, v * c), new Vector3d(-r * w * w * c, 0.0, -r * w * w * sn));
        };
    }
}
