package com.neoalive.tacz_sewv.wing.data;

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

import com.neoalive.tacz_sewv.wing.control.WingController;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/**
 * Parses and validates {@code data/<ns>/sewv/wing/*.json}. Gson only, no Minecraft types, so it
 * runs headless. Same file shape and override rules as the helicopter {@code AirframeData}:
 *
 * <pre>
 * { "classes": { "&lt;name&gt;": &lt;row&gt; },
 *   "hulls":   { "&lt;registry id&gt;": "&lt;class&gt;" },
 *   "clues":   [ { "contains": "&lt;substring&gt;", "class": "&lt;class&gt;" } ] }
 * </pre>
 * Files are applied in the order given. A file that names a class replaces that whole row. Unknown
 * keys warn and are ignored; a row that is missing a key or fails validation is skipped with a
 * warning naming why. The one string key is {@code propulsion.type}: {@code jet} or {@code prop}.
 */
public final class WingAirframeData {

    /** Gravity rows are checked against: SBW's default 0.06 blocks/tick^2. */
    public static final double CHECK_GRAVITY = 0.06 * 400.0;

    public record Clue(String contains, String className) {}

    public record Result(Map<String, WingAirframe> classes, Map<String, String> hulls, List<Clue> clues,
                         List<String> warnings) {}

    private WingAirframeData() {}

    public static Result parse(List<Map.Entry<String, JsonObject>> files) {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        Map<String, String> hulls = new TreeMap<>();
        List<Clue> clues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, JsonObject> file : files) {
            JsonObject root = file.getValue();
            for (String key : root.keySet()) {
                if (!Set.of("classes", "hulls", "clues", "_doc").contains(key)) {
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

        Map<String, WingAirframe> classes = new TreeMap<>();
        for (Map.Entry<String, JsonObject> row : rows.entrySet()) {
            String where = "class '" + row.getKey() + "'";
            try {
                WingAirframe af = build(row.getKey(), row.getValue(), warnings, where);
                List<String> problems = validate(af, CHECK_GRAVITY, WingAirframe.RHO0);
                if (problems.isEmpty()) classes.put(row.getKey(), af);
                else warnings.add(where + " rejected: " + String.join("; ", problems));
            } catch (RuntimeException e) {
                warnings.add(where + " rejected: " + e.getMessage());
            }
        }
        for (Map.Entry<String, String> h : hulls.entrySet()) {
            if (!classes.containsKey(h.getValue())) warnings.add("hull " + h.getKey() + " names unknown class " + h.getValue());
        }
        return new Result(classes, hulls, clues, warnings);
    }

    private static WingAirframe build(String name, JsonObject row, List<String> warnings, String where) {
        Map<String, Double> values = new TreeMap<>();
        flatten("", row, values);
        JsonObject prop = row.getAsJsonObject("propulsion");
        String type = prop != null && prop.has("type") ? prop.get("type").getAsString() : "jet";
        if (!type.equals("jet") && !type.equals("prop")) throw new IllegalArgumentException("propulsion.type must be jet or prop");
        Set<String> known = new HashSet<>(WingAirframe.KEYS);
        for (String key : values.keySet()) {
            if (!known.contains(key)) warnings.add(where + ": unknown key '" + key + "' ignored");
        }
        values.keySet().retainAll(known);
        return new WingAirframe(name, values, type.equals("prop"));
    }

    /** Numbers become dotted keys; arrays of numbers become key.0, key.1, ...; {@code _doc} strings are skipped. */
    private static void flatten(String prefix, JsonObject o, Map<String, Double> out) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (key.equals("propulsion.type") || e.getKey().equals("_doc")) continue;
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

    /**
     * Load-time checks. An empty list means the row is usable. Besides signs and ranges they prove
     * the row can actually fly: it trims level from 1.3 x stall to near top speed, reaches its own
     * {@code vMax}, and its fastest rotational modes stay well inside what the explicit sub-step
     * integrates stably.
     */
    public static List<String> validate(WingAirframe af, double g, double rho) {
        List<String> p = new ArrayList<>();
        for (double v : new double[] {af.mass, af.ix, af.iy, af.iz, af.span, af.area, af.clAlpha, af.oswald,
                af.staticThrust, af.vMax}) {
            if (!(v > 0.0)) p.add("mass, inertias, span, area, clAlpha, oswald, staticThrust and vMax must be positive");
        }
        if (af.prop && !(af.power > 0.0)) p.add("a prop row needs propulsion.power > 0");
        if (!(af.cd0 > 0.0)) p.add("wing.cd0 must be positive");
        if (!p.isEmpty()) return p;

        sign(p, af.cmAlpha < 0, "stability.cmAlpha must be < 0 (static pitch stability)");
        sign(p, af.cmq < 0, "stability.cmq must be < 0");
        sign(p, af.clBeta <= 0, "stability.clBeta must be <= 0 (dihedral effect)");
        sign(p, af.clp < 0, "stability.clp must be < 0");
        sign(p, af.cnBeta > 0, "stability.cnBeta must be > 0 (weathercock stability)");
        sign(p, af.cnr < 0, "stability.cnr must be < 0");
        sign(p, af.cyBeta < 0, "stability.cyBeta must be < 0");
        sign(p, af.cmDe > 0 && af.clDa > 0 && af.cnDr > 0, "control powers cmDe, clDa, cnDr must be > 0");
        double stallDeg = StrictMath.toDegrees(af.alphaStall);
        if (stallDeg < 6.0 || stallDeg > 25.0) p.add("wing.alphaStallDeg must be in [6, 25]");
        if (!(af.alphaMax < af.alphaStall)) p.add("limits.alphaMaxDeg must be below wing.alphaStallDeg");
        if (!(af.nMax > 1.0 && af.nMin < 0.0)) p.add("limits need nMax > 1 and nMin < 0");
        double bankDeg = StrictMath.toDegrees(af.bankMax);
        if (bankDeg < 15.0 || bankDeg > 85.0) p.add("limits.bankMaxDeg must be in [15, 85]");
        if (!(af.coneLo < af.coneHi)) p.add("aim.coneDeg must be [lo, hi] with lo < hi");
        if (!(af.turnRateMax > 0.0)) p.add("limits.turnRateMaxDeg must be positive");
        // A coordinated turn needs q = psiDot sin(phi) and r = psiDot cos(phi): the body-rate limits
        // must leave room for the turn-rate limit or the hull slips instead of turning.
        if (af.ratePitch < 1.3 * af.turnRateMax) p.add("limits.rateMax pitch must be >= 1.3 x limits.turnRateMaxDeg");
        if (af.rateYaw < 1.1 * af.turnRateMax) p.add("limits.rateMax yaw must be >= 1.1 x limits.turnRateMaxDeg");
        if (!p.isEmpty()) return p;

        double vs = AeroModel.stallSpeed(af, g, rho, 0.0);
        if (vs > 0.6 * af.vMax) p.add(String.format(java.util.Locale.ROOT, "stall speed %.1f > 0.6 x vMax %.1f", vs, af.vMax));
        double top = WingTrim.topSpeed(af, g, rho);
        if (top < 0.95 * af.vMax) p.add(String.format(java.util.Locale.ROOT, "top speed %.1f cannot reach 0.95 x vMax %.1f", top, af.vMax));
        for (double v : new double[] {1.3 * vs, af.cruiseSpeed, 0.95 * af.vMax}) {
            WingTrim.Result t = WingTrim.solve(af, g, rho, v, 0.0, 0.0, 0.0, 0.0);
            if (!t.feasible()) {
                p.add(String.format(java.util.Locale.ROOT, "no level trim at %.1f m/s (alpha %.1f deg, elevator %.2f, throttle %.2f)",
                        v, StrictMath.toDegrees(t.alpha()), t.elevator(), t.throttle()));
            }
        }
        // Elevator authority: holding alphaMax at the turn-rate limit, at cruise, must leave margin.
        // Pitch damping grows with pitch rate, and a row whose elevator cannot beat it at a working
        // turn rate pins the stick and hunts in every steep orbit.
        double qHat = af.turnRateMax * af.chord / (2.0 * af.cruiseSpeed);
        double deTurn = -(af.cm0 + af.cmAlpha * af.alphaMax + af.cmq * qHat) / af.cmDe;
        if (deTurn > 0.9) {
            p.add(String.format(java.util.Locale.ROOT,
                    "stability.cmDe too weak: alphaMax at the turn-rate limit needs elevator %.2f > 0.9", deTurn));
        }
        if (!(af.vRotate > vs)) p.add("procedures.vRotate must exceed the stall speed " + fmt(vs));
        if (!(af.approachSpeed >= 1.15 * vs)) p.add("procedures.approachSpeed must be >= 1.15 x stall speed " + fmt(vs));
        if (!(af.bestGlide >= 1.1 * vs)) p.add("procedures.bestGlide must be >= 1.1 x stall speed " + fmt(vs));
        if (!(af.cruiseSpeed >= 1.3 * vs && af.cruiseSpeed <= af.vMax)) p.add("procedures.cruiseSpeed must be in [1.3 vs, vMax]");
        if (!(af.runSpeed <= af.vMax)) p.add("procedures.runSpeed must be <= vMax");

        // Controller bandwidths: each loop at least ~3x slower than the one inside it, and the rate
        // loop well inside the sub-step (it is a discrete first-order loop at 120 Hz).
        double tol = 1.0 - 1.0E-9;
        if (af.bwRate < 3.0 * WingController.ROLL_ATT * af.bwAtt * tol) p.add("control.rate must be >= 3.6 x control.att");
        if (af.bwAtt < 3.0 * af.bwTecs * tol) p.add("control.att must be >= 3 x control.tecs");
        if (af.bwRate * WingPhysics.H > 0.2) p.add("control.rate x sub-step must be <= 0.2");
        if (!(af.l1Period > 0.0 && af.l1Zeta >= 0.5 && af.l1Zeta <= 1.2)) p.add("control needs l1Period > 0 and l1Zeta in [0.5, 1.2]");

        // Integrator margins at vMax, the stiffest point of the envelope.
        double qs = 0.5 * rho * af.vMax * af.vMax * af.area;
        double wsp = StrictMath.sqrt(qs * af.chord * -af.cmAlpha / af.ix);
        double kq = qs * af.chord * af.chord / (2.0 * af.vMax) * -af.cmq / af.ix;
        double kp = qs * af.span * af.span / (2.0 * af.vMax) * -af.clp / af.iz;
        double kr = qs * af.span * af.span / (2.0 * af.vMax) * -af.cnr / af.iy;
        double h = WingPhysics.H;
        if (wsp * h > 0.2) p.add(String.format(java.util.Locale.ROOT, "short-period x sub-step %.3f > 0.2 (raise inertia.0)", wsp * h));
        if (Math.max(kq, Math.max(kp, kr)) * h > 0.5) {
            p.add(String.format(java.util.Locale.ROOT, "rate damping x sub-step %.3f > 0.5 (raise inertias)", Math.max(kq, Math.max(kp, kr)) * h));
        }
        return p;
    }

    private static void sign(List<String> p, boolean ok, String msg) {
        if (!ok) p.add(msg);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
