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
import com.neoalive.tacz_sewv.heli.avoid.PathProbe;
import com.neoalive.tacz_sewv.heli.control.FlightCore;
import com.neoalive.tacz_sewv.heli.control.HeliController;
import com.neoalive.tacz_sewv.heli.data.AirframeData;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.Parity;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureId;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureStack;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
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
    /** The shared per-tick flight stack (what the in-game runtime runs); the fields below alias it. */
    final FlightCore core;
    final HeliPhysics physics;
    final HeliController ctl;
    final HeliState s;
    final HeliControl u;
    final Vector3d wind = new Vector3d();
    double groundY = 0.0;
    double t;
    /** When false the controller is bypassed and {@link #u} is held as set (open loop). */
    boolean closedLoop = true;
    AvoidForce barrier;
    ObstacleSet obstacles = ObstacleSet.empty();
    final Vector3d avoid = new Vector3d();
    boolean onGround;
    /** Guided runs: the tactical bias's world (terrain columns and other airframes) and this hull's id. */
    PathProbe.Terrain terrain = (x, z, yb, yt) -> Double.NaN;
    List<PathProbe.Traffic> traffic = List.of();
    int selfId;

    Sim(String cls) {
        this(airframe(cls));
    }

    Sim(Airframe af) {
        this.af = af;
        this.core = new FlightCore(af, G, RHO, 0.0, null);
        this.physics = core.physics;
        this.ctl = core.ctl;
        this.s = core.s;
        this.u = core.u;
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
     * One game tick the way {@code HeliRuntime} runs it, through the same {@link FlightCore}: the
     * selector and the transition rule, the barrier (when {@link #barrier} is set) and the tactical
     * bias against {@link #terrain} and {@link #traffic}, six sub-steps. Fills the two summary
     * fields the goal takes from the runtime (engage cycle, firing run). Every reference sample is
     * handed to {@code sink} (may be null).
     */
    void tickGuided(Situation sit, long tick, java.util.function.Consumer<HeliReference> sink) {
        double t0 = tick / 20.0;
        sit.time = t0;
        ProcedureStack stack = core.stack;
        if (stack.activeId() != null) sit.active = stack.activeId();
        sit.engageCycle = stack.engageCycle();
        sit.inFiringRun = stack.activeId() == ProcedureId.FIRE_RUN;
        sit.hullVx = s.v.x;
        sit.hullVz = s.v.z;
        core.guide(ModeSelector.select(sit), sit, t0, tick);
        core.barrier = barrier;
        ObstacleSet set = new ObstacleSet(obstacles.boxes(), stack.groundBarrier(t0) ? obstacles.groundY() : Double.NaN);
        PathProbe.Result path = PathProbe.probe(core.rawRef(t0, sit), s.v, HeliAvoidChecks.HALF_WIDTH,
                HeliAvoidChecks.HEIGHT, terrain, traffic, selfId, Parity.side(sit.pilotId));
        core.avoidance(set, path, HeliAvoidChecks.HALF_WIDTH + 3.0, t0);
        core.integrate(env(), sit, tick, sink);
        avoid.set(core.avoid);
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
