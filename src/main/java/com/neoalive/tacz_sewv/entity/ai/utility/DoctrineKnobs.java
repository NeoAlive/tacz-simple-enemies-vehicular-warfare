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
    public static final int BASE_STALEMATE_TICKS = 120;

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

    /** Ticks a stalemate is tolerated before the crew repositions. Maneuver loses patience sooner. */
    public static int stalemateTicks(Doctrine d) {
        return (int) Math.round(BASE_STALEMATE_TICKS * (1.0 - 0.4 * m(d)));
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

    /** Throttle cap while inside a target's ring on an ordered course: cautious crews creep, others drive. */
    public static double courseSpeedCap(Doctrine d) {
        double risk = r(d);
        return risk >= 0.0 ? 1.0 : 0.75 + 0.25 * (1.0 + risk);
    }
}
