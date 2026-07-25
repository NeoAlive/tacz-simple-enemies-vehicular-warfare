package com.neoalive.tacz_sewv.entity.ai.utility;

import net.minecraft.util.Mth;

/**
 * How well the crew thinks the fight is going, as one 0-100 number.
 *
 * <p>Its whole purpose is to stop every action re-deriving the same judgement: instead of Attack,
 * Advance and Retreat each weighing health against numbers against weather, they weigh
 * {@link Signal#CONFIDENCE} and this decides it once.
 *
 * <p>It is scored from the <b>same signals and the same weights file</b> as every action — the
 * {@code "confidence"} row — so it is tunable without a rebuild and cannot drift into a second,
 * hidden scoring system with its own rules.
 *
 * <h2>Why it is built from penalties, not bonuses</h2>
 *
 * <p>The first version started at 50 and added {@code (health - 0.5) * 60}, which handed an
 * undamaged hull +30 before anything had happened. Combined with an ally bonus it pinned live
 * crews at 100, and a value that is always at its maximum is a constant, not a gradient — it had
 * stopped telling the scorer anything. So being undamaged, fuelled and loaded is now simply the
 * neutral 50 it should be: confidence rises only for real advantage (friends nearby) and falls for
 * real trouble (losses, numbers, pressure, bad ground, weather).
 */
public final class Confidence {

    /** An even fight, and the value a crew with nothing remarkable about its situation sits at. */
    public static final double NEUTRAL = 50.0;

    private Confidence() {}

    /**
     * Evaluate confidence from an already-sampled signal array.
     *
     * <p>Runs before the actions are scored, because {@link Signal#CONFIDENCE} is an input to them.
     * The confidence row must therefore never weight {@code confidence} itself; the loader ignores
     * it there rather than letting it read a stale value from the previous second.
     */
    public static double evaluate(double[] signals, Doctrine doctrine, UtilityWeights weights) {
        return Mth.clamp(NEUTRAL + weights.scoreConfidence(signals, doctrine), 0.0, 100.0);
    }

    /**
     * Confidence as the -1..+1 signal the action weights are calibrated against, so a merely
     * average battlefield contributes nothing rather than a permanent half-weight bonus.
     */
    public static double asSignal(double confidence) {
        return Mth.clamp((confidence - NEUTRAL) / NEUTRAL, -1.0, 1.0);
    }
}
