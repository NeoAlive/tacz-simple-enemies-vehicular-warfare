package com.neoalive.tacz_sewv.wing.physics;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One fixed-wing airframe class: geometry, aerodynamic and stability derivatives, propulsion,
 * actuator rates, envelope limits, controller bandwidths and procedure speeds. Built from a row of
 * {@code data/<ns>/sewv/wing/*.json}; the same {@code KEYS}/{@code DEFAULTS}/{@link #with}/
 * {@link #describe} pattern as the helicopter {@code Airframe}.
 *
 * <p><b>Minecraft scale.</b> Rows are realistic in <i>shape</i> (a lift curve with a stall, a
 * parabolic polar, stability derivatives with textbook signs) and sized for SBW's world: gravity
 * is 400 x the hull's {@code Gravity} (24 m/s^2 by default) and one block is one metre, with speed
 * ranges compressed to roughly 15-60 m/s. Masses therefore come out small; only the ratios matter.
 *
 * <p>Derivative signs are the standard aerodynamic ones (stability axes: x forward, y right,
 * z down, nose-up / roll-right / nose-right positive) with control powers stored positive for the
 * pilot-sign inputs of {@link WingControl}: {@code cmAlpha, cmq, clBeta, clp, cnr, cyBeta < 0},
 * {@code cnBeta, cmDe, clDa, cnDr > 0}.
 */
public final class WingAirframe {

    public static final double RHO0 = 1.225;

    /** Every numeric key a row may carry. */
    public static final List<String> KEYS = List.of(
            "mass", "refWidth", "inertia.0", "inertia.1", "inertia.2",
            "wing.span", "wing.area", "wing.cl0", "wing.clAlpha", "wing.alphaStallDeg", "wing.stallSharpness",
            "wing.clPost", "wing.oswald", "wing.cd0", "wing.flapCl", "wing.flapCd", "wing.gearCd", "wing.brakeCd",
            "wing.windmillCd",
            "stability.cm0", "stability.cmAlpha", "stability.cmq", "stability.cmDe",
            "stability.clBeta", "stability.clp", "stability.clDa",
            "stability.cnBeta", "stability.cnr", "stability.cnDr", "stability.cyBeta",
            "stability.restDamping", "stability.degradedAuthority",
            "propulsion.staticThrust", "propulsion.power", "propulsion.propEfficiency", "propulsion.thrustSpeedK",
            "propulsion.spool",
            "actuators.gearTime", "actuators.flapTime", "actuators.brakeTime",
            "limits.vMax", "limits.nMax", "limits.nMin", "limits.bankMaxDeg", "limits.alphaMaxDeg",
            "limits.rateMax.0", "limits.rateMax.1", "limits.rateMax.2", "limits.turnRateMaxDeg", "limits.ceiling",
            "control.tecs", "control.tecsZeta", "control.l1Period", "control.l1Zeta",
            "control.att", "control.rate", "control.rateZeta", "control.yawDamper",
            "procedures.vRotate", "procedures.approachSpeed", "procedures.cruiseSpeed", "procedures.runSpeed",
            "procedures.bestGlide", "procedures.flareHeight", "procedures.glideslopeDeg", "procedures.patternRadius",
            "procedures.breakG", "procedures.tRecover",
            "aim.coneDeg.0", "aim.coneDeg.1");

    /** Keys a row may omit. */
    public static final Map<String, Double> DEFAULTS = Map.ofEntries(
            Map.entry("refWidth", 0.0),
            Map.entry("wing.stallSharpness", 50.0), Map.entry("wing.clPost", 0.7),
            Map.entry("wing.flapCl", 0.45), Map.entry("wing.flapCd", 0.04),
            Map.entry("wing.gearCd", 0.02), Map.entry("wing.brakeCd", 0.08), Map.entry("wing.windmillCd", 0.01),
            Map.entry("stability.restDamping", 0.5), Map.entry("stability.degradedAuthority", 0.4),
            Map.entry("propulsion.power", 0.0), Map.entry("propulsion.propEfficiency", 0.8),
            Map.entry("propulsion.thrustSpeedK", 0.0),
            Map.entry("actuators.gearTime", 3.0), Map.entry("actuators.flapTime", 2.0),
            Map.entry("actuators.brakeTime", 0.8),
            Map.entry("limits.ceiling", 320.0),
            Map.entry("control.tecs", 0.5), Map.entry("control.tecsZeta", 0.9),
            Map.entry("control.l1Period", 10.0), Map.entry("control.l1Zeta", 0.75),
            Map.entry("control.att", 2.5), Map.entry("control.rate", 10.0), Map.entry("control.rateZeta", 0.7),
            Map.entry("control.yawDamper", 1.0),
            Map.entry("procedures.flareHeight", 4.0), Map.entry("procedures.glideslopeDeg", 4.0),
            Map.entry("procedures.tRecover", 4.0),
            Map.entry("aim.coneDeg.0", -3.0), Map.entry("aim.coneDeg.1", 3.0));

    public final String name;
    /** Propeller (power-limited) rather than jet (thrust-limited). */
    public final boolean prop;

    public final double mass, refWidth, ix, iy, iz;
    public final double span, area, chord, aspect, cl0, clAlpha, alphaStall, stallCentre, stallSharpness, clPost, oswald,
            inducedK, cd0, flapCl, flapCd, gearCd, brakeCd, windmillCd;
    public final double cm0, cmAlpha, cmq, cmDe, clBeta, clp, clDa, cnBeta, cnr, cnDr, cyBeta;
    public final double restDamping, degradedAuthority;
    public final double staticThrust, power, propEfficiency, thrustSpeedK, spool;
    public final double gearTime, flapTime, brakeTime;
    public final double vMax, nMax, nMin, bankMax, alphaMax, ceiling;
    /** Body-rate limits, rad/s: pitch, yaw, roll. */
    public final double ratePitch, rateYaw, rateRoll;
    /**
     * Coordinated turn-rate limit, rad/s. The controller caps bank to {@code atan(turnRateMax V / g)},
     * which is what keeps turn rate in the gameplay range at Minecraft gravity (24 m/s^2 at 15-80 m/s
     * would otherwise turn at well over 100 deg/s at the g limit).
     */
    public final double turnRateMax;
    public final double bwTecs, tecsZeta, l1Period, l1Zeta, bwAtt, bwRate, rateZeta, yawDamper;
    public final double vRotate, approachSpeed, cruiseSpeed, runSpeed, bestGlide, flareHeight, glideslope,
            patternRadius, breakG, tRecover;
    /** Fire cone [lo, hi] as target depression below the nose, radians. */
    public final double coneLo, coneHi;

    private final TreeMap<String, Double> canonical;

    /**
     * @param values dotted numeric keys; missing keys fall back to {@link #DEFAULTS}
     * @throws IllegalArgumentException naming the first missing key
     */
    public WingAirframe(String name, Map<String, Double> values, boolean prop) {
        this.name = name;
        this.prop = prop;
        TreeMap<String, Double> v = new TreeMap<>(DEFAULTS);
        v.putAll(values);
        for (String key : KEYS) {
            if (!v.containsKey(key)) throw new IllegalArgumentException("missing '" + key + "'");
        }
        this.canonical = v;

        mass = v.get("mass");
        refWidth = v.get("refWidth");
        ix = v.get("inertia.0");
        iy = v.get("inertia.1");
        iz = v.get("inertia.2");
        span = v.get("wing.span");
        area = v.get("wing.area");
        chord = area / span;
        aspect = span * span / area;
        cl0 = v.get("wing.cl0");
        clAlpha = v.get("wing.clAlpha");
        alphaStall = StrictMath.toRadians(v.get("wing.alphaStallDeg"));
        stallSharpness = v.get("wing.stallSharpness");
        clPost = v.get("wing.clPost");
        // The blend is centred past the stall so the lift PEAK lands on alphaStall: with sigma
        // ~ logistic, dCL/da = 0 where (1 - sigma) clAlpha = sigma' (CLlin - plate), which puts
        // the peak ln(M delta / clAlpha) / M before the centre.
        double delta = Math.max(0.05, cl0 + clAlpha * alphaStall - clPost * StrictMath.sin(2.0 * alphaStall));
        stallCentre = alphaStall + StrictMath.log(Math.max(1.0001, stallSharpness * delta / clAlpha)) / stallSharpness;
        oswald = v.get("wing.oswald");
        inducedK = 1.0 / (Math.PI * oswald * aspect);
        cd0 = v.get("wing.cd0");
        flapCl = v.get("wing.flapCl");
        flapCd = v.get("wing.flapCd");
        gearCd = v.get("wing.gearCd");
        brakeCd = v.get("wing.brakeCd");
        windmillCd = v.get("wing.windmillCd");
        cm0 = v.get("stability.cm0");
        cmAlpha = v.get("stability.cmAlpha");
        cmq = v.get("stability.cmq");
        cmDe = v.get("stability.cmDe");
        clBeta = v.get("stability.clBeta");
        clp = v.get("stability.clp");
        clDa = v.get("stability.clDa");
        cnBeta = v.get("stability.cnBeta");
        cnr = v.get("stability.cnr");
        cnDr = v.get("stability.cnDr");
        cyBeta = v.get("stability.cyBeta");
        restDamping = v.get("stability.restDamping");
        degradedAuthority = v.get("stability.degradedAuthority");
        staticThrust = v.get("propulsion.staticThrust");
        power = v.get("propulsion.power");
        propEfficiency = v.get("propulsion.propEfficiency");
        thrustSpeedK = v.get("propulsion.thrustSpeedK");
        spool = v.get("propulsion.spool");
        gearTime = v.get("actuators.gearTime");
        flapTime = v.get("actuators.flapTime");
        brakeTime = v.get("actuators.brakeTime");
        vMax = v.get("limits.vMax");
        nMax = v.get("limits.nMax");
        nMin = v.get("limits.nMin");
        bankMax = StrictMath.toRadians(v.get("limits.bankMaxDeg"));
        alphaMax = StrictMath.toRadians(v.get("limits.alphaMaxDeg"));
        ratePitch = StrictMath.toRadians(v.get("limits.rateMax.0"));
        rateYaw = StrictMath.toRadians(v.get("limits.rateMax.1"));
        rateRoll = StrictMath.toRadians(v.get("limits.rateMax.2"));
        turnRateMax = StrictMath.toRadians(v.get("limits.turnRateMaxDeg"));
        ceiling = v.get("limits.ceiling");
        bwTecs = v.get("control.tecs");
        tecsZeta = v.get("control.tecsZeta");
        l1Period = v.get("control.l1Period");
        l1Zeta = v.get("control.l1Zeta");
        bwAtt = v.get("control.att");
        bwRate = v.get("control.rate");
        rateZeta = v.get("control.rateZeta");
        yawDamper = v.get("control.yawDamper");
        vRotate = v.get("procedures.vRotate");
        approachSpeed = v.get("procedures.approachSpeed");
        cruiseSpeed = v.get("procedures.cruiseSpeed");
        runSpeed = v.get("procedures.runSpeed");
        bestGlide = v.get("procedures.bestGlide");
        flareHeight = v.get("procedures.flareHeight");
        glideslope = StrictMath.toRadians(v.get("procedures.glideslopeDeg"));
        patternRadius = v.get("procedures.patternRadius");
        breakG = v.get("procedures.breakG");
        tRecover = v.get("procedures.tRecover");
        coneLo = StrictMath.toRadians(v.get("aim.coneDeg.0"));
        coneHi = StrictMath.toRadians(v.get("aim.coneDeg.1"));
    }

    /** The row's value for {@code key} (one of {@link #KEYS}). */
    public double value(String key) {
        Double v = canonical.get(key);
        if (v == null) throw new IllegalArgumentException("unknown key '" + key + "'");
        return v;
    }

    /** Copy with some keys replaced, for tuning experiments and self-checks. */
    public WingAirframe with(Map<String, Double> overrides) {
        TreeMap<String, Double> v = new TreeMap<>(this.canonical);
        v.putAll(overrides);
        return new WingAirframe(this.name, v, this.prop);
    }

    /** Canonical, order-independent description: two rows are the same airframe iff these match. */
    public String describe() {
        return name + (prop ? " prop " : " jet ") + canonical;
    }
}
