package com.neoalive.tacz_sewv.wing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.neoalive.tacz_sewv.wing.data.WingAirframeData;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/**
 * Headless checks for the fixed-wing flight stack. Run via {@code ./gradlew selfCheckWing}.
 *
 * <ul>
 * <li><b>D1</b>: the same scenario twice is bit-identical, every tick.</li>
 * <li><b>D3</b>: key order inside the data file cannot change a row.</li>
 * <li><b>D5</b>: the pure packages import nothing from Minecraft and call no non-strict
 *     transcendental.</li>
 * <li><b>D2</b>: the closed loop (controller + physics) is bit-identical too.</li>
 * <li><b>W1-W10</b>: the airframe model ({@link WingPhysicsChecks}).</li>
 * <li><b>C1-C8</b>: the controller cascade ({@link WingControlChecks}).</li>
 * <li><b>G1-G6</b>: guidance and the terrain guard ({@link WingGuidanceChecks}).</li>
 * <li><b>K1-K6</b>: combat guidance ({@link WingCombatChecks}).</li>
 * </ul>
 * Ends with the informational SBW envelope comparison ({@link SbwEnvelope}).
 */
public final class WingFlightSelfCheck {

    private static final Path PURE_ROOT = Path.of("src/main/java/com/neoalive/tacz_sewv/wing");
    private static final List<String> PURE_PACKAGES = List.of("physics", "control", "guidance", "avoid");
    private static final Pattern NON_STRICT_TRIG = Pattern.compile(
            "(?<!Strict)\\bMath\\.(sin|cos|tan|asin|acos|atan2?|exp|log|log10|pow|cbrt|hypot|expm1|log1p|sqrt)\\(");

    public static void main(String[] args) throws IOException {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        layering();
        determinism();
        WingPhysicsChecks.run();
        WingControlChecks.run();
        WingGuidanceChecks.run();
        WingCombatChecks.run();
        SbwEnvelope.print();
        System.out.println("wing flight self-check: OK (D1-D3, D5, W1-W10, C1-C8, G1-G6, K1-K6)");
    }

    // --- D1, D3 ---------------------------------------------------------------------------------

    private static void determinism() throws IOException {
        WingSim a = scenario(), b = scenario();
        for (int i = 0; i < 20 * 60; i++) {
            schedule(a);
            schedule(b);
            a.tick();
            b.tick();
            assert bits(a).equals(bits(b)) : "D1: runs diverged at tick " + i;
        }
        assert a.finite() : "D1: the scenario must stay finite";

        CtlSim ca = new CtlSim("attack_jet"), cb = new CtlSim("attack_jet");
        for (CtlSim c : new CtlSim[] {ca, cb}) {
            c.start(c.af.cruiseSpeed, 120.0, 600.0, 0.0);
            c.wind.set(3.0, 0.0, -2.0);
        }
        var circle = CtlSim.circle(0.0, 600.0, 0.0, 120.0, ca.af.cruiseSpeed);
        for (int i = 0; i < 20 * 60; i++) {
            ca.tick(circle);
            cb.tick(circle);
            assert ctlBits(ca).equals(ctlBits(cb)) : "D2: closed-loop runs diverged at tick " + i;
        }

        JsonObject root = JsonParser.parseString(Files.readString(WingSim.SHIPPED)).getAsJsonObject();
        var plain = WingAirframeData.parse(List.of(Map.entry("a", root)));
        var reversed = WingAirframeData.parse(List.of(Map.entry("a", reverseKeys(root).getAsJsonObject())));
        assert plain.classes().keySet().equals(reversed.classes().keySet()) : "D3: class set differs";
        for (String k : plain.classes().keySet()) {
            assert plain.classes().get(k).describe().equals(reversed.classes().get(k).describe())
                    : "D3: key order changed class " + k;
        }
    }

    private static WingSim scenario() {
        WingSim s = new WingSim("fighter_jet");
        s.trimAt(s.af.cruiseSpeed, 800.0);
        s.wind.set(2.0, 0.0, -1.0);
        return s;
    }

    /** Deterministic stick doublets on every axis, so all three moment paths are exercised. */
    private static void schedule(WingSim s) {
        double t = s.tick / 20.0;
        WingTrim.Result r = WingTrim.solve(s.af, WingSim.G, WingSim.RHO, s.af.cruiseSpeed, 0.0, 0.0, 0.0, 0.0);
        s.u.elevator = r.elevator() + 0.1 * StrictMath.sin(0.7 * t);
        s.u.aileron = 0.2 * StrictMath.sin(0.5 * t);
        s.u.rudder = 0.1 * StrictMath.sin(0.3 * t);
    }

    private static String bits(WingSim s) {
        double[] v = {s.s.p.x, s.s.p.y, s.s.p.z, s.s.v.x, s.s.v.y, s.s.v.z, s.s.q.x, s.s.q.y, s.s.q.z, s.s.q.w,
                s.s.w.x, s.s.w.y, s.s.w.z, s.s.throttle};
        StringBuilder b = new StringBuilder();
        for (double d : v) b.append(Long.toHexString(Double.doubleToRawLongBits(d))).append(',');
        return b.toString();
    }

    private static String ctlBits(CtlSim c) {
        var s = c.core.s;
        double[] v = {s.p.x, s.p.y, s.p.z, s.v.x, s.v.y, s.v.z, s.q.x, s.q.y, s.q.z, s.q.w, s.w.x, s.w.y, s.w.z,
                s.throttle, c.core.u.elevator, c.core.u.aileron, c.core.u.rudder};
        StringBuilder b = new StringBuilder();
        for (double d : v) b.append(Long.toHexString(Double.doubleToRawLongBits(d))).append(',');
        return b.toString();
    }

    private static JsonElement reverseKeys(JsonElement e) {
        if (e.isJsonObject()) {
            List<String> keys = new ArrayList<>(e.getAsJsonObject().keySet());
            Collections.reverse(keys);
            JsonObject out = new JsonObject();
            for (String k : keys) out.add(k, reverseKeys(e.getAsJsonObject().get(k)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(reverseKeys(x));
            return out;
        }
        return e;
    }

    // --- D5 -------------------------------------------------------------------------------------

    private static void layering() throws IOException {
        assert Files.isDirectory(PURE_ROOT.resolve("physics")) : "D5: missing pure package wing/physics";
        for (String pkg : PURE_PACKAGES) {
            Path dir = PURE_ROOT.resolve(pkg);
            if (!Files.isDirectory(dir)) continue; // later phases add control, guidance, avoid
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(file);
                    assert !src.contains("import net.minecraft") : "D5: " + file + " imports Minecraft";
                    assert !src.contains("Mth.") : "D5: " + file + " uses vanilla Mth (float LUT)";
                    assert !NON_STRICT_TRIG.matcher(src).find() : "D5: " + file + " uses a non-strict Math transcendental";
                }
            }
        }
    }
}
