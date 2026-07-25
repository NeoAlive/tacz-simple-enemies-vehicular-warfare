package com.neoalive.tacz_sewv.entity.ai.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.TreeMap;

/**
 * How much each thing the crew can see is worth to each thing it could do.
 *
 * <p>Balancing this AI means changing roughly a hundred numbers, which is why they are a datapack
 * file rather than config entries: {@code /reload} applies an edit without restarting, and a pack
 * can retune the whole thing without touching code. The shipped file is
 * {@code data/tacz_sewv/sewv/ai/weights.json}.
 *
 * <p>A score is a plain weighted sum — every modifier is {@code weight × signal}, where a signal
 * is normalised to 0..1 (or -1..1 where it has a direction). So a weight reads as "points at full
 * strength", and two weights side by side are directly comparable.
 *
 * <p>Weights are stored as flat arrays indexed by ordinal rather than maps: scoring runs for every
 * crew in a battle and allocates nothing.
 */
public final class UtilityWeights {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The table every crew scores against. Swapped wholesale on {@code /reload}, never mutated,
     * so a crew mid-tick either sees the old table or the new one and never a half-written one.
     */
    private static volatile UtilityWeights active = fallback();

    public static UtilityWeights active() {
        return active;
    }

    /**
     * The reserved top-level key that scores {@link Confidence} instead of an action. Confidence is
     * not something a crew can decide to do, but it is scored from the same signals by the same
     * arithmetic, so it lives in the same file rather than as a second tuning surface.
     */
    public static final String CONFIDENCE_KEY = "confidence";

    /** [action][signal] */
    private final double[][] signalWeights;
    /** [action][doctrine axis] */
    private final double[][] axisWeights;
    /** The confidence row: [signal] and [doctrine axis]. */
    private final double[] confidenceSignals;
    private final double[] confidenceAxes;

    private UtilityWeights(double[][] signalWeights, double[][] axisWeights,
                           double[] confidenceSignals, double[] confidenceAxes) {
        this.signalWeights = signalWeights;
        this.axisWeights = axisWeights;
        this.confidenceSignals = confidenceSignals;
        this.confidenceAxes = confidenceAxes;
    }

    /**
     * The confidence delta from an even fight, before {@link Confidence} clamps it into 0-100.
     *
     * <p>Any weight on {@code confidence} itself is ignored here — it is not sampled yet when this
     * runs, and reading last second's value would make confidence quietly self-referential.
     */
    public double scoreConfidence(double[] signals, Doctrine doctrine) {
        double total = 0.0;
        for (int s = 0; s < this.confidenceSignals.length; s++) {
            if (s == Signal.CONFIDENCE.ordinal()) continue;
            if (this.confidenceSignals[s] != 0.0) total += this.confidenceSignals[s] * signals[s];
        }
        for (int x = 0; x < this.confidenceAxes.length; x++) {
            if (this.confidenceAxes[x] != 0.0) {
                total += this.confidenceAxes[x] * doctrine.get(Doctrine.Axis.VALUES[x]);
            }
        }
        return total;
    }

    /**
     * The utility of one action, given the current signal sample and the crew's doctrine.
     *
     * @param signals indexed by {@link Signal} ordinal, as filled by {@code TacticalBrain}
     */
    public double score(Action action, double[] signals, Doctrine doctrine) {
        int a = action.ordinal();
        double total = 0.0;

        double[] weights = this.signalWeights[a];
        for (int s = 0; s < weights.length; s++) {
            if (weights[s] != 0.0) total += weights[s] * signals[s];
        }

        double[] axes = this.axisWeights[a];
        for (int x = 0; x < axes.length; x++) {
            if (axes[x] != 0.0) total += axes[x] * doctrine.get(Doctrine.Axis.VALUES[x]);
        }

        return total;
    }

    /**
     * The safety net, used only when the weights file is missing or unreadable.
     *
     * <p>Deliberately NOT a copy of the shipped file — a second full table would be a second thing
     * to keep tuned. This is just enough for a crew to fight and to break off when it is losing, so
     * a broken datapack degrades the AI instead of parking every vehicle in the world.
     */
    public static UtilityWeights fallback() {
        double[][] signals = new double[Action.VALUES.length][Signal.VALUES.length];
        double[][] axes = new double[Action.VALUES.length][Doctrine.Axis.VALUES.length];

        signals[Action.ATTACK.ordinal()][Signal.ENEMY_VISIBLE.ordinal()] = 50.0;
        signals[Action.ADVANCE.ordinal()][Signal.TOO_FAR.ordinal()] = 60.0;
        signals[Action.RETREAT.ordinal()][Signal.LOW_HEALTH.ordinal()] = 90.0;
        signals[Action.RETREAT.ordinal()][Signal.TOO_CLOSE.ordinal()] = 40.0;
        signals[Action.DEPLOY_SMOKE.ordinal()][Signal.LOW_HEALTH.ordinal()] = 60.0;
        signals[Action.DEPLOY_SMOKE.ordinal()][Signal.SMOKE_READY.ordinal()] = 10.0;
        signals[Action.DEPLOY_SMOKE.ordinal()][Signal.SCREENED.ordinal()] = -200.0;
        signals[Action.HOLD.ordinal()][Signal.BASE.ordinal()] = 5.0;
        // Out of contact, keep working the standing destination rather than parking.
        signals[Action.PATROL.ordinal()][Signal.BASE.ordinal()] = 20.0;

        double[] confSignals = new double[Signal.VALUES.length];
        confSignals[Signal.LOW_HEALTH.ordinal()] = -30.0;
        confSignals[Signal.OUTNUMBERED.ordinal()] = -20.0;

        return new UtilityWeights(signals, axes, confSignals,
                new double[Doctrine.Axis.VALUES.length]);
    }

    /**
     * Build a table from every loaded weights file.
     *
     * <p>Files are applied in id order and each one overwrites only the actions it names, so a pack
     * can retune a single action without restating the rest. A malformed entry is logged and
     * skipped rather than thrown: a datapack typo should cost one weight, not the whole AI.
     */
    public static UtilityWeights parse(Map<ResourceLocation, JsonElement> files) {
        double[][] signals = new double[Action.VALUES.length][Signal.VALUES.length];
        double[][] axes = new double[Action.VALUES.length][Doctrine.Axis.VALUES.length];
        double[] confSignals = new double[Signal.VALUES.length];
        double[] confAxes = new double[Doctrine.Axis.VALUES.length];
        int applied = 0;

        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(file.getValue(), "weights");
            } catch (RuntimeException e) {
                LOGGER.error("[sewv] AI weights {} is not a JSON object — skipped: {}",
                        file.getKey(), e.getMessage());
                continue;
            }

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (CONFIDENCE_KEY.equals(entry.getKey())) {
                    applied += readRow(file.getKey(), CONFIDENCE_KEY, entry.getValue(),
                            confSignals, confAxes);
                    continue;
                }
                Action action = Action.byKey(entry.getKey());
                if (action == null) {
                    LOGGER.warn("[sewv] AI weights {}: no such action '{}' — ignored",
                            file.getKey(), entry.getKey());
                    continue;
                }
                applied += readRow(file.getKey(), action.key, entry.getValue(),
                        signals[action.ordinal()], axes[action.ordinal()]);
            }
        }

        if (applied == 0) {
            LOGGER.error("[sewv] No usable AI weights found — falling back to the built-in minimum. "
                    + "Vehicle crews will fight, but not well.");
            return fallback();
        }
        LOGGER.info("[sewv] Loaded {} AI utility weights from {} file(s)", applied, files.size());
        return new UtilityWeights(signals, axes, confSignals, confAxes);
    }

    /** Read one row — an action's modifiers, or the confidence row — into its weight arrays. */
    private static int readRow(ResourceLocation source, String rowKey, JsonElement element,
                               double[] signals, double[] axes) {
        JsonObject modifiers;
        try {
            modifiers = GsonHelper.convertToJsonObject(element, rowKey);
        } catch (RuntimeException e) {
            LOGGER.error("[sewv] AI weights {}: '{}' is not an object — skipped", source, rowKey);
            return 0;
        }

        // A file that names a row replaces that row wholesale, so a pack tuning "attack" gets
        // exactly the attack it wrote rather than its numbers layered over ours.
        java.util.Arrays.fill(signals, 0.0);
        java.util.Arrays.fill(axes, 0.0);

        int count = 0;
        for (Map.Entry<String, JsonElement> modifier : modifiers.entrySet()) {
            String key = modifier.getKey();
            double weight;
            try {
                weight = GsonHelper.convertToDouble(modifier.getValue(), key);
            } catch (RuntimeException e) {
                LOGGER.warn("[sewv] AI weights {}: '{}.{}' is not a number — ignored",
                        source, rowKey, key);
                continue;
            }
            // Bounded so one absurd number cannot make an action unbeatable and freeze the crew
            // on it forever — the scores are meant to be comparable, not a lockout.
            weight = Mth.clamp(weight, -1000.0, 1000.0);

            Signal signal = Signal.byKey(key);
            if (signal != null) {
                signals[signal.ordinal()] = weight;
                count++;
                continue;
            }
            Doctrine.Axis axis = Doctrine.Axis.byKey(key);
            if (axis != null) {
                axes[axis.ordinal()] = weight;
                count++;
                continue;
            }
            LOGGER.warn("[sewv] AI weights {}: '{}.{}' is neither a signal nor a doctrine axis "
                    + "— ignored", source, rowKey, key);
        }
        return count;
    }

    /**
     * Loads {@code data/<namespace>/sewv/ai/*.json} and installs the result.
     *
     * <p>Registered on {@code AddReloadListenerEvent}, so the weights follow the server's datapacks
     * and {@code /reload} re-reads them without a restart — which is the whole reason these numbers
     * are a datapack file and not config entries.
     */
    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/ai");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
        }
    }
}
