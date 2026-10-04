package com.neoalive.tacz_sewv.heli.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/**
 * Parses and validates {@code data/<ns>/sewv/heli/*.json}. Gson only, no Minecraft types, so it
 * runs headless; the reload listener (Phase 2) just feeds it the files.
 *
 * <pre>
 * { "classes": { "&lt;name&gt;": &lt;row&gt; },
 *   "hulls":   { "&lt;registry id&gt;": "&lt;class&gt;" },
 *   "clues":   [ { "contains": "&lt;substring&gt;", "class": "&lt;class&gt;" } ] }
 * </pre>
 * Files are applied in the order given (callers sort by resource location). A file that names a
 * class replaces that whole row, as {@code UtilityWeights} does. Unknown keys warn and are
 * ignored; a row that is missing a key or fails validation is skipped with a warning naming why.
 */
public final class AirframeData {

    /** Gravity rows are checked against: SBW's default 0.06 blocks/tick^2. */
    public static final double CHECK_GRAVITY = 0.06 * 400.0;
    /** Ratio checks allow a hair of floating-point slack, so "exactly 3x" passes. */
    private static final double TOL = 1.0 - 1.0E-9;

    public record Clue(String contains, String className) {}

    public record Result(Map<String, Airframe> classes, Map<String, String> hulls, List<Clue> clues,
                         List<String> warnings) {}

    private AirframeData() {}

    public static Result parse(List<Map.Entry<String, JsonObject>> files) {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        Map<String, String> hulls = new TreeMap<>();
        List<Clue> clues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, JsonObject> file : files) {
            JsonObject root = file.getValue();
            for (String key : root.keySet()) {
                if (!Set.of("classes", "hulls", "clues").contains(key)) {
                    warnings.add(file.getKey() + ": unknown top-level key '" + key + "'");
                }
            }
            if (root.has("classes")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("classes").entrySet()) {
                    rows.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
            if (root.has("hulls")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("hulls").entrySet()) {
                    hulls.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (root.has("clues")) {
                for (JsonElement e : root.getAsJsonArray("clues")) {
                    JsonObject o = e.getAsJsonObject();
                    clues.add(new Clue(o.get("contains").getAsString(), o.get("class").getAsString()));
                }
            }
        }

        Map<String, Airframe> classes = new TreeMap<>();
        for (Map.Entry<String, JsonObject> row : rows.entrySet()) {
            String where = "class '" + row.getKey() + "'";
            try {
                Airframe af = build(row.getKey(), row.getValue(), warnings, where);
                List<String> problems = validate(af, CHECK_GRAVITY, Airframe.RHO0);
                if (problems.isEmpty()) {
                    classes.put(row.getKey(), af);
                } else {
                    warnings.add(where + " rejected: " + String.join("; ", problems));
                }
            } catch (RuntimeException e) {
                warnings.add(where + " rejected: " + e.getMessage());
            }
        }
        for (Map.Entry<String, String> h : hulls.entrySet()) {
            if (!classes.containsKey(h.getValue())) warnings.add("hull " + h.getKey() + " names unknown class " + h.getValue());
        }
        return new Result(classes, hulls, clues, warnings);
    }

    private static Airframe build(String name, JsonObject row, List<String> warnings, String where) {
        Map<String, Double> values = new TreeMap<>();
        flatten("", row, values);
        String dir = stringAt(row, "rotor", "direction", "ccw");
        String type = stringAt(row, "tail", "type", "tail");
        if (!dir.equals("ccw") && !dir.equals("cw")) throw new IllegalArgumentException("rotor.direction must be ccw or cw");
        if (!type.equals("tail") && !type.equals("coaxial")) throw new IllegalArgumentException("tail.type must be tail or coaxial");

        JsonArray table = row.getAsJsonObject("rotor").getAsJsonArray("ctTable");
        if (table == null) throw new IllegalArgumentException("missing 'rotor.ctTable'");
        double[][] ct = new double[table.size()][];
        for (int i = 0; i < table.size(); i++) {
            JsonArray pt = table.get(i).getAsJsonArray();
            ct[i] = new double[] {pt.get(0).getAsDouble(), pt.get(1).getAsDouble()};
        }
        Set<String> known = new HashSet<>(Airframe.KEYS);
        for (String key : values.keySet()) {
            if (!known.contains(key)) warnings.add(where + ": unknown key '" + key + "' ignored");
        }
        values.keySet().retainAll(known);
        return new Airframe(name, values, ct, dir.equals("ccw"), type.equals("coaxial"));
    }

    /** Numbers become dotted keys; arrays of numbers become key.0, key.1, ...; strings and ctTable are read separately. */
    private static void flatten(String prefix, JsonObject o, Map<String, Double> out) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (key.equals("rotor.ctTable") || key.equals("rotor.direction") || key.equals("tail.type")) continue;
            if (v.isJsonObject()) {
                flatten(key, v.getAsJsonObject(), out);
            } else if (v.isJsonArray()) {
                JsonArray a = v.getAsJsonArray();
                for (int i = 0; i < a.size(); i++) out.put(key + "." + i, a.get(i).getAsDouble());
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                out.put(key, v.getAsDouble());
            } else {
                throw new IllegalArgumentException("'" + key + "' is not a number");
            }
        }
    }

    private static String stringAt(JsonObject row, String group, String key, String fallback) {
        JsonObject g = row.getAsJsonObject(group);
        return g != null && g.has(key) ? g.get(key).getAsString() : fallback;
    }

    /**
     * Froude scaling exponent of every dimensional key: value x lambda^e for a hull lambda times the
     * reference size. Same gravity, so speeds go as sqrt(lambda), times as sqrt(lambda), masses as
     * lambda^3. Thrust rho A V_t^2 C_T then goes as lambda^3 like the weight, so C_T, hover collective
     * and every dimensionless ratio of the row are unchanged. (Plan O6 scaled lengths and mass but
     * not tip speed: thrust lambda^2 against weight lambda^3, so a bigger hull could not lift.)
     * Keys absent here are dimensionless or tactical and are kept.
     */
    private static final Map<String, Double> FROUDE = Map.ofEntries(
            Map.entry("mass", 3.0), Map.entry("cgHeight", 1.0), Map.entry("refWidth", 1.0),
            Map.entry("inertia.0", 5.0), Map.entry("inertia.1", 5.0), Map.entry("inertia.2", 5.0),
            Map.entry("rotor.radius", 1.0), Map.entry("rotor.tipSpeed", 0.5), Map.entry("rotor.inertia", 5.0),
            Map.entry("rotor.hubHeight", 1.0), Map.entry("rotor.hubStiffness", 4.0), Map.entry("rotor.angularDamping", 4.5),
            Map.entry("engine.maxPower", 3.5), Map.entry("engine.idlePower", 3.5), Map.entry("engine.frictionCoeff", 4.5),
            Map.entry("engine.governor.wn", -0.5), Map.entry("engine.startRate", -1.0), Map.entry("engine.collectiveLag", 0.5),
            Map.entry("tail.arm", 1.0), Map.entry("tail.height", 1.0), Map.entry("tail.nominalThrust", 3.0),
            Map.entry("tail.maxThrust", 3.0), Map.entry("tail.inflowDamping", 2.5), Map.entry("tail.yawLag", 0.5),
            Map.entry("fuselage.cdA.0", 2.0), Map.entry("fuselage.cdA.1", 2.0), Map.entry("fuselage.cdA.2", 2.0),
            Map.entry("limits.vMaxH", 0.5), Map.entry("limits.vClimb", 0.5), Map.entry("limits.vDescent", 0.5),
            Map.entry("limits.rateMaxPR", -0.5), Map.entry("limits.yawRateMax", -0.5), Map.entry("limits.minTurnRadius", 1.0),
            Map.entry("control.pos", -0.5), Map.entry("control.posV", -0.5), Map.entry("control.vel", -0.5),
            Map.entry("control.att", -0.5), Map.entry("control.rate", -0.5), Map.entry("control.yaw", -0.5),
            Map.entry("autorotation.flareHeight", 1.0),
            Map.entry("procedures.cruiseSpeed", 0.5), Map.entry("procedures.turnSpeed", 0.5),
            Map.entry("procedures.orbitSpeed", 0.5),
            Map.entry("procedures.runSpeed", 0.5));

    /** The row Froude-scaled to a hull {@code lambda} times its reference size (see {@link #FROUDE}). */
    public static Airframe scaled(Airframe af, double lambda) {
        Map<String, Double> o = new TreeMap<>();
        for (Map.Entry<String, Double> e : FROUDE.entrySet()) {
            o.put(e.getKey(), af.value(e.getKey()) * StrictMath.pow(lambda, e.getValue()));
        }
        return af.with(o);
    }

    /**
     * Load-time checks (plan section 5). An empty list means the row is usable. The two thrust
     * limits are independent: the rotor table bounds thrust aerodynamically, the engine bounds
     * shaft power, and a power-limited hull droops rotor speed rather than hitting a fixed cap.
     */
    public static List<String> validate(Airframe af, double g, double rho) {
        List<String> p = new ArrayList<>();
        double[] x = af.ctTheta, y = af.ctValue;
        if (x.length < 5) p.add("ctTable needs at least 5 points");
        if (x.length > 0 && (x[0] != 0.0 || x[x.length - 1] != 1.0)) p.add("ctTable must span collective 0..1");
        for (int i = 1; i < x.length; i++) {
            if (!(x[i] > x[i - 1]) || !(y[i] > y[i - 1])) p.add("ctTable must be strictly increasing (point " + i + ")");
        }
        if (y.length > 0 && y[0] < 0.0) p.add("ctTable C_T(0) must be >= 0");
        for (double v : new double[] {af.mass, af.ix, af.iy, af.iz, af.radius, af.tipSpeed, af.rotorInertia}) {
            if (!(v > 0.0)) p.add("mass, inertias, radius, tip speed and rotor inertia must be positive");
        }
        if (!p.isEmpty()) return p;

        double weight = af.mass * g;
        double tMaxAero = rho * af.area * af.tipSpeed * af.tipSpeed * RotorModel.maxCt(af);
        if (tMaxAero < 1.15 * weight) p.add(String.format("aerodynamic T/W %.2f < 1.15", tMaxAero / weight));
        double pReq = RotorModel.powerRequired(af, rho, 1.15 * weight, 0.0);
        if (af.maxPower < pReq) p.add(String.format("maxPower %.0f < power for T/W 1.15 (%.0f)", af.maxPower, pReq));
        if (af.bwRate < 3.0 * af.bwAtt * TOL) p.add("control.rate must be >= 3 x control.att");
        if (af.bwAtt < 3.0 * af.bwVel * TOL) p.add("control.att must be >= 3 x control.vel");
        if (af.bwVel < 3.0 * Math.max(af.bwPos, af.bwPosV) * TOL) p.add("control.vel must be >= 3 x control.pos/posV");
        if (af.bwRate * HeliPhysics.H > 0.2) p.add("control.rate x sub-step must be <= 0.2");
        if (af.bwYaw * TOL > 1.0 / (3.0 * af.yawLag)) p.add("control.yaw must be <= 1/(3 tail.yawLag)");
        if (af.bwVel * TOL > 1.0 / (3.0 * af.collectiveLag)) p.add("control.vel must be <= 1/(3 engine.collectiveLag)");
        if (af.minTurnRadius < af.turnSpeed * af.turnSpeed / af.aLatMax * TOL) {
            p.add("limits.minTurnRadius must be >= procedures.turnSpeed^2 / limits.aLatMax");
        }
        if (!(af.coneLo < af.coneHi)) p.add("aim.pitchFreeConeDeg must be [lo, hi] with lo < hi");
        if (!(af.patrolRMin > 0.0 && af.patrolRMin <= af.patrolRMax)) p.add("procedures.patrol needs 0 < rMin <= rMax");
        if (!af.coaxial) {
            double qHover = RotorModel.hoverPower(af, g, rho) / af.omegaN;
            double authority = af.tailArm * Math.min(af.tailNominal, af.tailMax); // full pedal gives T_trN
            if (authority < 1.3 * qHover) {
                p.add(String.format("tail authority %.0f < 1.3 x hover torque %.0f", authority, qHover));
            }
        }
        return p;
    }
}
