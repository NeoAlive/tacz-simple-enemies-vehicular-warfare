package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.Arrays;

/**
 * Canned battlefield samples and a pure re-implementation of the crew's decision over them. The ledger editor's
 * preview and {@code selfCheckUtility} both judge doctrine through this, so what the player is shown and what the
 * checks pin are the same arithmetic as {@link TacticalBrain} (minus the per-hull spread and hysteresis).
 *
 * <p>The duel samples are the shape measured in the 2026-11-22 live audit: one enemy hull with its escort riflemen,
 * open ground, light fire, the crew exposed with smoke ready.
 */
public final class DoctrineScenarios {

    /** Every scenario the preview shows, in display order. */
    public enum Scenario {
        EVEN_DUEL("even_duel"),
        ESCORTED_DUEL("escorted_duel"),
        OUTNUMBERED("outnumbered"),
        HURT("hurt"),
        LOST_CONTACT("lost_contact");

        public final String key;
        public static final Scenario[] VALUES = values();

        Scenario(String key) {
            this.key = key;
        }
    }

    /** The combat actions a crew with smoke ready can choose between. */
    static final Action[] COMBAT = {
            Action.ATTACK, Action.ADVANCE, Action.RETREAT, Action.FLANK_LEFT, Action.HOLD, Action.DEPLOY_SMOKE};
    /** Out of contact, free to move (no standing order). */
    static final Action[] STAND_DOWN = {Action.HOLD, Action.PATROL, Action.SEARCH_LAST_KNOWN, Action.SUPPRESS};

    private DoctrineScenarios() {}

    /** 0 at an even fight or better, 1 from 1:3 down. The live crew signal uses exactly this. */
    public static double outnumbered(double forceRatio) {
        double v = (1.0 / Math.max(forceRatio, 1.0E-3) - 1.0) / 2.0;
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }

    /** One enemy hull, optional escort riflemen (a third of a hull each), our hull at {@code health}. */
    public static double[] duel(int escorts, double health) {
        double[] s = new double[Signal.VALUES.length];
        s[Signal.BASE.ordinal()] = 1.0;
        s[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = 1.0;
        s[Signal.TOO_FAR.ordinal()] = 0.1;
        s[Signal.UNDER_FIRE.ordinal()] = 0.3;
        double enemyWeight = 1.0 + escorts / 3.0;
        s[Signal.OUTNUMBERED.ordinal()] = outnumbered(1.0 / enemyWeight);
        s[Signal.THREAT_DENSITY.ordinal()] = Math.min(1.0, Math.max(0.0, (enemyWeight - 1.0) / 4.0));
        s[Signal.ALONE.ordinal()] = 1.0;
        s[Signal.OPEN.ordinal()] = 1.0;
        s[Signal.STEEP_GROUND.ordinal()] = 0.33;
        s[Signal.EXPOSED.ordinal()] = 1.0;
        s[Signal.RECENT_SHOT.ordinal()] = 1.0;
        s[Signal.SMOKE_READY.ordinal()] = 1.0;
        s[Signal.LOW_HEALTH.ordinal()] = Math.min(1.0, Math.max(0.0, 1.0 - health / 0.5));
        return s;
    }

    public static double[] sample(Scenario scenario) {
        return switch (scenario) {
            case EVEN_DUEL -> duel(0, 1.0);
            case ESCORTED_DUEL -> duel(3, 1.0);
            case OUTNUMBERED -> duel(6, 1.0);
            case HURT -> duel(3, 0.3);
            case LOST_CONTACT -> {
                double[] s = new double[Signal.VALUES.length];
                s[Signal.BASE.ordinal()] = 1.0;
                s[Signal.LOST_CONTACT.ordinal()] = 1.0;
                s[Signal.ALONE.ordinal()] = 1.0;
                s[Signal.OPEN.ordinal()] = 1.0;
                yield s;
            }
        };
    }

    public static Action[] candidates(Scenario scenario) {
        return scenario == Scenario.LOST_CONTACT ? STAND_DOWN : COMBAT;
    }

    /** What a crew of {@code doctrine} would choose in {@code scenario}, and its confidence. */
    public record Verdict(Action action, double confidence) {}

    public static Verdict decide(UtilityWeights weights, Doctrine doctrine, Scenario scenario) {
        return decide(weights, doctrine, sample(scenario), candidates(scenario));
    }

    /** Same order as {@link TacticalBrain#update}: caution, then confidence, then the actions. */
    public static Verdict decide(UtilityWeights weights, Doctrine doctrine, double[] sample, Action[] candidates) {
        double[] s = Arrays.copyOf(sample, sample.length);
        double caution = Doctrine.cautionCentre(doctrine);
        s[Signal.CAUTION.ordinal()] = Math.max(-1.0, Math.min(1.0, caution * 2.0 - 1.0));
        double confidence = Confidence.evaluate(s, doctrine, weights);
        s[Signal.CONFIDENCE.ordinal()] = Confidence.asSignal(confidence);
        Action best = null;
        double bestScore = 0.0;
        for (Action action : candidates) {
            double score = weights.score(action, s, doctrine);
            if (best == null || score > bestScore) {
                best = action;
                bestScore = score;
            }
        }
        return new Verdict(best, confidence);
    }
}
