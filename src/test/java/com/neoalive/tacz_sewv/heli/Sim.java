package com.neoalive.tacz_sewv.heli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;
import com.neoalive.tacz_sewv.heli.control.HeliController;
import com.neoalive.tacz_sewv.heli.data.AirframeData;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliEnv;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/**
 * Headless stand-in for the in-game loop: one hull over flat ground. Six physics sub-steps per
 * tick, controller every sub-step, ground contact resolved once per tick (as {@code move()} does
 * in game): position clamped and downward velocity cut. Skid friction is the physics' own.
 */
final class Sim {

    static final Path SHIPPED = Path.of("src/main/resources/data/tacz_sewv/sewv/heli/airframes.json");
    static final double G = AirframeData.CHECK_GRAVITY;
    static final double RHO = Airframe.RHO0;

    private static Map<String, Airframe> classes;

    final Airframe af;
    final HeliPhysics physics;
    final HeliController ctl;
    final HeliState s = new HeliState();
    final HeliControl u = new HeliControl();
    final Vector3d wind = new Vector3d();
    double groundY = 0.0;
    double t;
    /** When false the controller is bypassed and {@link #u} is held as set (open loop). */
    boolean closedLoop = true;
    AvoidForce barrier;
    ObstacleSet obstacles = ObstacleSet.empty();
    final Vector3d avoid = new Vector3d();
    boolean onGround;

    Sim(String cls) {
        this(airframe(cls));
    }

    Sim(Airframe af) {
        this.af = af;
        this.physics = new HeliPhysics(af, 0.0);
        this.ctl = new HeliController(af, G, RHO);
    }

    static synchronized Map<String, Airframe> classes() {
        if (classes == null) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(SHIPPED)).getAsJsonObject();
                AirframeData.Result r = AirframeData.parse(List.of(Map.entry("airframes.json", root)));
                assert r.warnings().isEmpty() : "shipped airframes: " + r.warnings();
                classes = r.classes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }

    static Airframe airframe(String cls) {
        Airframe af = classes().get(cls);
        assert af != null : "no shipped class " + cls;
        return af;
    }

    /** Trimmed powered hover at (x, y, z), nose at {@code yawDeg}, rotor at speed, collective at hover. */
    Sim hover(double x, double y, double z, double yawDeg) {
        s.p.set(x, y, z);
        s.v.zero();
        s.w.zero();
        s.q.set(McPose.toQuat(yawDeg, 0.0, 0.0));
        s.omega = af.omegaN;
        s.omegaTarget = af.omegaN;
        s.engine = HeliState.Engine.RUN;
        s.theta0 = RotorModel.hoverCollective(af, G, RHO);
        u.collective = s.theta0;
        return this;
    }

    HeliEnv env() {
        return new HeliEnv(G, RHO, wind, groundY, onGround);
    }

    /** One game tick toward {@code ref} (sampled per sub-step at absolute time). */
    void tick(DoubleFunction<HeliReference> ref) {
        if (barrier != null) barrier.update(obstacles, s.p, s.v);
        HeliEnv env = env();
        for (int k = 0; k < 6; k++) {
            if (barrier != null) barrier.force(obstacles, s.p, s.v, af.mass, avoid);
            if (closedLoop) ctl.step(ref.apply(t), s, env, avoid, u);
            physics.step(s, u, env, avoid);
            t += HeliPhysics.H;
        }
        onGround = false;
        if (s.p.y <= groundY) {
            s.p.y = groundY;
            if (s.v.y < 0.0) s.v.y = 0.0;
            onGround = true;
        }
    }

    /**
     * One game tick the way {@code HeliRuntime} runs it: select, switch with a blend, then six
     * sub-steps of reference, controller (or let-go when the procedure is not flying) and physics.
     * Every reference sample is handed to {@code sink} (may be null).
     */
    void tickGuided(com.neoalive.tacz_sewv.heli.guidance.ProcedureStack stack,
                    com.neoalive.tacz_sewv.heli.guidance.Situation sit, long tick,
                    java.util.function.Consumer<HeliReference> sink) {
        double t0 = tick / 20.0;
        sit.time = t0;
        if (stack.activeId() != null) sit.active = stack.activeId();
        var want = com.neoalive.tacz_sewv.heli.guidance.ModeSelector.select(sit);
        if (stack.activeId() != want) stack.switchTo(want, s, sit, t0, tick);
        HeliEnv env = env();
        for (int k = 0; k < 6; k++) {
            double tk = (6.0 * tick + k) / 120.0;
            HeliReference r = stack.refAt(tk, s, sit);
            if (sink != null) sink.accept(r);
            u.engine = stack.engine();
            if (barrier != null) barrier.force(obstacles, s.p, s.v, af.mass, avoid);
            if (stack.flying()) {
                ctl.step(r, s, env, avoid, u);
            } else {
                u.collective = 0;
                u.cLon = u.cLat = u.pedal = 0;
                ctl.reset();
            }
            physics.step(s, u, env, avoid);
        }
        t = (tick + 1) / 20.0;
        onGround = false;
        if (s.p.y <= groundY) {
            s.p.y = groundY;
            if (s.v.y < 0.0) s.v.y = 0.0;
            onGround = true;
        }
    }

    void run(double seconds, DoubleFunction<HeliReference> ref) {
        int ticks = (int) Math.round(seconds * 20.0);
        for (int i = 0; i < ticks; i++) tick(ref);
    }

    /** A stationary reference. */
    static DoubleFunction<HeliReference> hold(double x, double y, double z, double yawDeg) {
        Vector3d p = new Vector3d(x, y, z), zero = new Vector3d();
        double yaw = Math.toRadians(yawDeg);
        return t -> new HeliReference(t, p, zero, zero, yaw, 0.0);
    }

    /** Tilt of the mast from vertical, degrees. */
    double tiltDeg() {
        Vector3d up = s.q.transform(new Vector3d(0, 1, 0));
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
    }
}
