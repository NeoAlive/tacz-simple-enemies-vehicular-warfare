package com.neoalive.tacz_sewv.entity.ai.utility;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.Axis;

/**
 * Every doctrine effect that is not a scorer weight: ranges, health thresholds, timings. Pure functions of a
 * {@link Doctrine}, so they are checked headlessly ({@code selfCheckDoctrine}) and the ledger editor can show the
 * same numbers the AI uses.
 *
 * <p>Rule: {@link Doctrine#NEUTRAL} answers exactly the constants these replaced, so a player without a ledger and
 * every RU/US preset of zero behave as before.
 */
public final class DoctrineKnobs {

    /** The engagement ring before doctrine (armour). Matches {@code Facts.preferredRange}. */
    public static final double BASE_BREAK_OFF = 0.25;
    public static final double BASE_BAIL = 0.15;
    public static final int BASE_COMMIT_TICKS = 40;
    public static final double BASE_SWITCH_MARGIN = 10.0;

    private DoctrineKnobs() {}

    private static double a(Doctrine d) { return d.get(Axis.AGGRESSION); }
    private static double p(Doctrine d) { return d.get(Axis.PRESERVATION); }
    private static double i(Doctrine d) { return d.get(Axis.INITIATIVE); }
    private static double s(Doctrine d) { return d.get(Axis.SUPPORT_RELIANCE); }
    private static double m(Doctrine d) { return d.get(Axis.MANEUVER); }
    private static double r(Doctrine d) { return d.get(Axis.RISK_TOLERANCE); }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Multiplier on the preferred engagement ring: aggressive crews close in, cautious ones hold off. */
    public static double ringScale(Doctrine d) {
        return clamp(1.0 - 0.25 * a(d) + 0.15 * p(d) - 0.10 * r(d), 0.65, 1.35);
    }

    /** Hull health fraction below which a crew breaks off and preserves the vehicle. */
    public static double breakOffHealth(Doctrine d) {
        return clamp(BASE_BREAK_OFF + 0.10 * p(d) - 0.07 * r(d) - 0.05 * a(d), 0.10, 0.42);
    }

    /** Hull health fraction below which the crew abandons it. Always under {@link #breakOffHealth}. */
    public static double bailHealth(Doctrine d) {
        double bail = clamp(BASE_BAIL + 0.05 * p(d) - 0.04 * r(d), 0.08, 0.22);
        return Math.min(bail, breakOffHealth(d) - 0.02);
    }

    /** Minimum ticks a plan is held before a better one may replace it. Initiative re-plans sooner. */
    public static int commitTicks(Doctrine d) {
        return (int) Math.round(BASE_COMMIT_TICKS * (1.0 - 0.35 * i(d)));
    }

    /** Score lead a challenger needs over the current plan. Initiative lowers it. */
    public static double switchMargin(Doctrine d) {
        return BASE_SWITCH_MARGIN * (1.0 - 0.3 * i(d));
    }

    /** Multiplier on target acquisition delay. Initiative and aggression notice faster. */
    public static double acquisitionScale(Doctrine d) {
        return clamp(1.0 - 0.25 * i(d) - 0.10 * a(d), 0.6, 1.4);
    }

    /** Multiplier on how long a crew tolerates a silent stalemate before repositioning. Maneuver moves sooner. */
    public static double stalemateScale(Doctrine d) {
        return 1.0 - 0.4 * m(d);
    }

    /** Multiplier on the fire-support request cooldown. Reliant crews call again sooner. */
    public static double supportCooldownScale(Doctrine d) {
        return 1.0 - 0.5 * s(d);
    }

    /** -1 (infantry first) .. +1 (armour first), used as a ranking bias inside one target-stack row. */
    public static double armourPreference(Doctrine d) {
        return d.get(Axis.TARGET_PRIORITY);
    }

    /** Effective-distance multiplier for one candidate: favoured targets read nearer. */
    public static double rankBias(Doctrine d, boolean armour) {
        double t = armourPreference(d);
        return armour ? 1.0 - 0.3 * t : 1.0 + 0.3 * t;
    }

    /** Screen with smoke when hit while holding an ordered course. */
    public static boolean smokeOnHit(Doctrine d) {
        return p(d) > 0.2;
    }

    // ---- aircraft -------------------------------------------------------------------------------------------------

    /** Helicopter weapon engagement range multiplier: bold pilots close in, careful ones stand off. */
    public static double heliRangeScale(Doctrine d) {
        return clamp(1.0 - 0.2 * a(d) + 0.15 * p(d), 0.7, 1.3);
    }

    /** Blocks added to a helicopter's cruise altitude: careful pilots fly higher. */
    public static double heliAltitudeOffset(Doctrine d) {
        return 8.0 * p(d);
    }

    /** Hull fraction below which an aircraft keeps flares going. Neutral 0.5, as the constant it replaces. */
    public static float decoyHealth(Doctrine d) {
        return (float) clamp(0.5 + 0.15 * p(d), 0.3, 0.7);
    }

    /** Chance per check to pop a preserving flare. Neutral 0.5, as the constant it replaces. */
    public static float decoyChance(Doctrine d) {
        return (float) clamp(0.5 + 0.3 * p(d), 0.1, 0.9);
    }

    /** Lowest attack-run height above ground for a plane: bolder pilots run in lower. Neutral 40. */
    public static double planeMinRunAgl(Doctrine d) {
        return 40.0 - 10.0 * r(d);
    }

    /** Multiplier on the plane's emergency-landing health, tied to the break-off so one ledger moves both. */
    public static double emergencyLandScale(Doctrine d) {
        return breakOffHealth(d) / BASE_BREAK_OFF;
    }

    // ---- infantry (SEM's CombatDecisionContext: pressure and confidence are 0..1) --------------------------------

    /** Confidence shift for an on-foot unit: aggression and risk embolden, preservation steadies. */
    public static float infantryConfidence(Doctrine d, float confidence) {
        return (float) clamp(confidence + 0.15 * (a(d) + r(d) - p(d)), 0.0, 1.0);
    }

    /** Threat pressure scale for an on-foot unit: a preserving doctrine feels fire more, a risk-taking one less. */
    public static float infantryPressure(Doctrine d, float pressure) {
        return (float) clamp(pressure * (1.0 + 0.25 * (p(d) - r(d))), 0.0, 1.0);
    }

    /** A passive doctrine (low aggression and initiative together) never lets SEM push its riflemen forward. */
    public static boolean infantryMayAdvance(Doctrine d) {
        return a(d) + i(d) >= -0.4;
    }

    /** On an ordered course with a target inside its ring, a risk-averse crew creeps at march pace instead of racing. */
    public static boolean creepsOnCourse(Doctrine d) {
        return r(d) < -0.2;
    }
}
