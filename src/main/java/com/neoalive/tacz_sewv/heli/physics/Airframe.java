package com.neoalive.tacz_sewv.heli.physics;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One helicopter class's parameters: geometry, rotor, engine, tail, fuselage, limits and control
 * bandwidths. SI units throughout, angles stored in radians. Immutable.
 *
 * <p>Built from a flat map of dotted keys ({@code rotor.radius}, {@code inertia.0}, ...) so this
 * class knows nothing about JSON. The parser in {@code heli.data} flattens the file and hands it
 * over. {@link #KEYS} is the full key set; keys in {@link #DEFAULTS} may be omitted.
 */
public final class Airframe {

    public static final double RHO0 = 1.225;

    /** Every numeric key a row may carry. */
    public static final List<String> KEYS = List.of(
            "mass", "cgHeight", "inertia.0", "inertia.1", "inertia.2",
            "rotor.radius", "rotor.tipSpeed", "rotor.inertia", "rotor.hubHeight", "rotor.sigmaCd0",
            "rotor.inducedKappa", "rotor.cyclicMaxDeg", "rotor.hubStiffness", "rotor.angularDamping",
            "effects.etl.k", "effects.etl.muPeak", "effects.groundEffect.k", "effects.groundEffect.c",
            "effects.vrs.lossMax", "effects.vrs.xiLo", "effects.vrs.xiPeak", "effects.vrs.xiHi",
            "effects.vrs.etaHi", "effects.axial.k",
            "engine.maxPower", "engine.governor.wn", "engine.governor.kd", "engine.startRate",
            "engine.collectiveLag", "engine.efficiency", "engine.idlePower", "engine.frictionCoeff",
            "engine.hoverBurnFraction",
            "tail.arm", "tail.height", "tail.nominalThrust", "tail.maxThrust", "tail.inflowDamping", "tail.yawLag",
            "fuselage.cdA.0", "fuselage.cdA.1", "fuselage.cdA.2",
            "limits.vMaxH", "limits.vClimb", "limits.vDescent", "limits.tiltMaxDeg", "limits.rateMaxPR",
            "limits.yawRateMax", "limits.minTurnRadius", "limits.aLatMax",
            "control.pos", "control.posV", "control.vel", "control.velZeta", "control.att", "control.rate",
            "control.rateZeta", "control.yaw",
            "autorotation.collective", "autorotation.kOmega", "autorotation.flareHeight");

    /** Keys a row may omit. {@code tail.yawLag} is absent: its default depends on the tail type. */
    public static final Map<String, Double> DEFAULTS = Map.ofEntries(
            Map.entry("effects.etl.k", 0.15), Map.entry("effects.etl.muPeak", 0.10),
            Map.entry("effects.groundEffect.k", 0.28), Map.entry("effects.groundEffect.c", 1.43),
            Map.entry("effects.vrs.lossMax", 0.35), Map.entry("effects.vrs.xiLo", 0.3),
            Map.entry("effects.vrs.xiPeak", 0.8), Map.entry("effects.vrs.xiHi", 1.6),
            Map.entry("effects.vrs.etaHi", 1.0), Map.entry("effects.axial.k", 0.1),
            Map.entry("engine.governor.kd", 0.1),
            Map.entry("engine.hoverBurnFraction", 0.7576), Map.entry("control.rateZeta", 0.05));

    public final String name;
    public final boolean coaxial;
    /** +1 rotor turns counter-clockwise seen from above (US practice), -1 clockwise. */
    public final double rotorDir;
    /** Normalised collective breakpoints in [0, 1] and their thrust coefficients. */
    public final double[] ctTheta;
    public final double[] ctValue;

    public final double mass, cgHeight, ix, iy, iz;
    public final double radius, area, tipSpeed, omegaN, rotorInertia, hubHeight, sigmaCd0, kappa;
    public final double cyclicMax, hubStiffness, angularDamping;
    public final double etlK, etlMuPeak, geK, geC, vrsLoss, vrsXiLo, vrsXiPeak, vrsXiHi, vrsEtaHi;
    /** c_lambda, the C_T slope against inflow ratio (about sigma a / 4). */
    public final double axialK;
    public final double maxPower, maxTorque, govWn, govKd, startRate, collectiveLag, efficiency, idlePower;
    public final double frictionCoeff, hoverBurnFraction;
    public final double tailArm, tailHeight, tailNominal, tailMax, tailDamping, yawLag;
    public final double cdaX, cdaY, cdaZ;
    public final double vMaxH, vClimb, vDescent, tiltMax, rateMaxPR, yawRateMax, minTurnRadius, aLatMax;
    public final double bwPos, bwPosV, bwVel, velZeta, bwAtt, bwRate, rateZeta, bwYaw;
    public final double autoCollective, autoKOmega, flareHeight;

    private final TreeMap<String, Double> canonical;

    /**
     * @param values dotted numeric keys; missing keys fall back to {@link #DEFAULTS}
     * @param ctTable rows of {@code [theta0, C_T]}
     * @throws IllegalArgumentException naming the first missing key
     */
    public Airframe(String name, Map<String, Double> values, double[][] ctTable, boolean ccw, boolean coaxial) {
        this.name = name;
        this.coaxial = coaxial;
        this.rotorDir = ccw ? 1.0 : -1.0;
        TreeMap<String, Double> v = new TreeMap<>(DEFAULTS);
        v.putAll(values);
        v.putIfAbsent("tail.yawLag", coaxial ? v.getOrDefault("engine.collectiveLag", 0.15) : 0.05);
        for (String key : KEYS) {
            if (!v.containsKey(key)) throw new IllegalArgumentException("missing '" + key + "'");
        }
        this.canonical = v;

        this.ctTheta = new double[ctTable.length];
        this.ctValue = new double[ctTable.length];
        for (int i = 0; i < ctTable.length; i++) {
            this.ctTheta[i] = ctTable[i][0];
            this.ctValue[i] = ctTable[i][1];
        }

        mass = v.get("mass");
        cgHeight = v.get("cgHeight");
        ix = v.get("inertia.0");
        iy = v.get("inertia.1");
        iz = v.get("inertia.2");
        radius = v.get("rotor.radius");
        area = Math.PI * radius * radius;
        tipSpeed = v.get("rotor.tipSpeed");
        omegaN = tipSpeed / radius;
        rotorInertia = v.get("rotor.inertia");
        hubHeight = v.get("rotor.hubHeight");
        sigmaCd0 = v.get("rotor.sigmaCd0");
        kappa = v.get("rotor.inducedKappa");
        cyclicMax = StrictMath.toRadians(v.get("rotor.cyclicMaxDeg"));
        hubStiffness = v.get("rotor.hubStiffness");
        angularDamping = v.get("rotor.angularDamping");
        etlK = v.get("effects.etl.k");
        etlMuPeak = v.get("effects.etl.muPeak");
        geK = v.get("effects.groundEffect.k");
        geC = v.get("effects.groundEffect.c");
        vrsLoss = v.get("effects.vrs.lossMax");
        vrsXiLo = v.get("effects.vrs.xiLo");
        vrsXiPeak = v.get("effects.vrs.xiPeak");
        vrsXiHi = v.get("effects.vrs.xiHi");
        vrsEtaHi = v.get("effects.vrs.etaHi");
        axialK = v.get("effects.axial.k");
        maxPower = v.get("engine.maxPower");
        maxTorque = maxPower / omegaN;
        govWn = v.get("engine.governor.wn");
        govKd = v.get("engine.governor.kd");
        startRate = v.get("engine.startRate");
        collectiveLag = v.get("engine.collectiveLag");
        efficiency = v.get("engine.efficiency");
        idlePower = v.get("engine.idlePower");
        frictionCoeff = v.get("engine.frictionCoeff");
        hoverBurnFraction = v.get("engine.hoverBurnFraction");
        tailArm = v.get("tail.arm");
        tailHeight = v.get("tail.height");
        tailNominal = v.get("tail.nominalThrust");
        tailMax = v.get("tail.maxThrust");
        tailDamping = v.get("tail.inflowDamping");
        yawLag = v.get("tail.yawLag");
        cdaX = v.get("fuselage.cdA.0");
        cdaY = v.get("fuselage.cdA.1");
        cdaZ = v.get("fuselage.cdA.2");
        vMaxH = v.get("limits.vMaxH");
        vClimb = v.get("limits.vClimb");
        vDescent = v.get("limits.vDescent");
        tiltMax = StrictMath.toRadians(v.get("limits.tiltMaxDeg"));
        rateMaxPR = StrictMath.toRadians(v.get("limits.rateMaxPR"));
        yawRateMax = StrictMath.toRadians(v.get("limits.yawRateMax"));
        minTurnRadius = v.get("limits.minTurnRadius");
        aLatMax = v.get("limits.aLatMax");
        bwPos = v.get("control.pos");
        bwPosV = v.get("control.posV");
        bwVel = v.get("control.vel");
        velZeta = v.get("control.velZeta");
        bwAtt = v.get("control.att");
        bwRate = v.get("control.rate");
        rateZeta = v.get("control.rateZeta");
        bwYaw = v.get("control.yaw");
        autoCollective = v.get("autorotation.collective");
        autoKOmega = v.get("autorotation.kOmega");
        flareHeight = v.get("autorotation.flareHeight");
    }

    /** Copy with some keys replaced — for tuning experiments and self-checks. */
    public Airframe with(Map<String, Double> overrides) {
        TreeMap<String, Double> v = new TreeMap<>(this.canonical);
        v.putAll(overrides);
        double[][] table = new double[this.ctTheta.length][];
        for (int i = 0; i < table.length; i++) table[i] = new double[] {this.ctTheta[i], this.ctValue[i]};
        return new Airframe(this.name, v, table, this.rotorDir > 0, this.coaxial);
    }

    /**
     * Copy with every C_T scaled by {@code k}: keeps hover collective where it was tuned when a
     * hull's own SBW gravity differs from the gravity the rows are written for.
     */
    public Airframe withCtScale(double k) {
        double[][] table = new double[this.ctTheta.length][];
        for (int i = 0; i < table.length; i++) table[i] = new double[] {this.ctTheta[i], this.ctValue[i] * k};
        return new Airframe(this.name, this.canonical, table, this.rotorDir > 0, this.coaxial);
    }

    /** Canonical, order-independent description: two rows are the same airframe iff these match. */
    public String describe() {
        return name + (coaxial ? " coaxial" : " tail") + (rotorDir > 0 ? " ccw " : " cw ")
                + Arrays.toString(ctTheta) + Arrays.toString(ctValue) + canonical;
    }
}
