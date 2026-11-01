package com.neoalive.tacz_sewv.wing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.wing.data.WingAirframeData;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingControl;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingState;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/** Headless open-loop harness: one airframe, its physics, state and controls, ticked at 20 Hz. */
final class WingSim {

    static final Path SHIPPED = Path.of("src/main/resources/data/tacz_sewv/sewv/wing/airframes.json");
    static final double G = WingAirframeData.CHECK_GRAVITY;
    static final double RHO = WingAirframe.RHO0;
    static final double ENERGY_COST_RATE = 256.0;

    private static WingAirframeData.Result table;

    final WingAirframe af;
    final WingPhysics physics;
    final WingState s = new WingState();
    final WingControl u = new WingControl();
    final Vector3d wind = new Vector3d();
    long tick;

    WingSim(String cls) {
        this(table().classes().get(cls));
    }

    WingSim(WingAirframe af) {
        assert af != null : "no such class";
        this.af = af;
        this.physics = new WingPhysics(af, ENERGY_COST_RATE);
    }

    static WingAirframeData.Result table() {
        if (table == null) table = parse(SHIPPED);
        return table;
    }

    static WingAirframeData.Result parse(Path file) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return WingAirframeData.parse(List.of(Map.entry("shipped", root)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Level trim at {@code v}, heading south (+Z), gear up, at height {@code h}. */
    WingTrim.Result trimAt(double v, double h) {
        WingTrim.Result r = WingTrim.solve(af, G, RHO, v, 0.0, 0.0, 0.0, 0.0);
        s.p.set(0.0, h, 0.0);
        s.v.set(0.0, 0.0, v);
        s.q.set(McPose.toQuat(0.0, -StrictMath.toDegrees(r.alpha()), 0.0));
        s.w.zero();
        s.gear = 0.0;
        u.gearDown = false;
        s.throttle = u.throttle = Math.max(0.0, Math.min(1.0, r.throttle()));
        u.elevator = r.elevator();
        u.aileron = u.rudder = 0.0;
        return r;
    }

    void tick() {
        WingEnv env = new WingEnv(G, RHO, wind, Double.NEGATIVE_INFINITY, false);
        for (int k = 0; k < 6; k++) physics.step(s, u, env);
        tick++;
    }

    void run(double seconds) {
        for (int i = 0, n = (int) StrictMath.round(seconds * 20.0); i < n; i++) tick();
    }

    double speed() {
        return s.v.length();
    }

    boolean finite() {
        return Double.isFinite(s.p.x + s.p.y + s.p.z + s.v.x + s.v.y + s.v.z + s.q.x + s.q.y + s.q.z + s.q.w
                + s.w.x + s.w.y + s.w.z);
    }
}
