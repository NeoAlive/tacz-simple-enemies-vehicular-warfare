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
     * The things a weight can be multiplied by — the vocabulary of the weights file.
     *
     * <p>Doctrine axes are usable as modifier keys too, and are resolved through
     * {@link Doctrine.Axis#byKey}; they are not listed here because they already name themselves.
     */
    public enum Signal {
        /** Always 1 — an action's flat starting utility. */
        BASE("base"),
        /** We hold a live target. */
        ENEMY_VISIBLE("enemyVisible"),
        /** That target is riding a vehicle, i.e. this is an armor fight. */
        ENEMY_ARMOR("enemyArmor"),
        /** That target is on foot. */
        ENEMY_INFANTRY("enemyInfantry"),
        /** Battlefield advantage, -1 (hopeless) to +1 (dominant), 0 at an even 50. */
        CONFIDENCE("confidence"),
        /** Ramps 0..1 as the hull falls from half health to destroyed. */
        LOW_HEALTH("lowHealth"),
        /** 0 with a full rack, 0.5 running low, 1 empty. */
        LOW_AMMO("lowAmmo"),
        /** Ramps 0..1 as a PMC hull's charge falls from half to flat. Always 0 for RU/US. */
        LOW_ENERGY("lowEnergy"),
        /** 0..1 by how badly the local force ratio is against us. */
        OUTNUMBERED("outnumbered"),
        /** 0..1 by how much friendly armor is around, saturating at three. */
        ALLIES_NEARBY("alliesNearby"),
        /** 1 when no friendly unit is in sensing range at all. */
        ALONE("alone"),
        /** 0..1 by how far inside the preferred engagement ring we are. */
        TOO_CLOSE("tooClose"),
        /** 0..1 by how far outside it we are. */
        TOO_FAR("tooFar"),
        /** 1 shortly after taking a hit, decaying to 0. */
        RECENTLY_HIT("recentlyHit"),
        /** A smoke volley is loaded and ready. */
        SMOKE_READY("smokeReady"),
        /** Our line to the target is already through smoke — more would buy nothing. */
        SCREENED("screened"),
        /** The gun cannot fire right now (reloading, overheated, empty, throttled). */
        CANNOT_SHOOT("cannotShoot"),
        /**
         * 0..1 by how many nearby friendlies have no target of their own, saturating at three.
         *
         * <p>Whether supporting fire is available at all is <b>not</b> a signal — it is a hard
         * feasibility gate on the three Call actions, so a weight can never talk a crew into
         * radioing a battery that does not exist.
         */
        IDLE_ALLY("idleAlly"),

        // Where we are fighting. Exactly one ground signal and one sky signal is 1 at a time.
        /** Open ground: long sightlines, nothing to hide behind. */
        OPEN("open"),
        /** Woodland: close cover, broken sightlines, good flanking country. */
        FOREST("forest"),
        /** Built-up: very short engagement ranges and blind corners. */
        URBAN("urban"),
        /** High ground: steep, awkward, and hard on a turret's elevation arc. */
        MOUNTAIN("mountain"),
        /** Wetland: soft going and poor footing. */
        SWAMP("swamp"),
        /** Desert or badlands: the longest sightlines in the game. */
        DESERT("desert"),

        RAIN("rain"),
        SNOW("snow"),
        STORM("storm"),

        /** 0..1 by how steep the ground around the hull is. */
        STEEP_GROUND("steepGround"),
        /** 0..1 by how far above ordinary fighting altitude we are. */
        HIGH_ALTITUDE("highAltitude");

        public final String key;

        Signal(String key) {
            this.key = key;
        }

        public static final Signal[] VALUES = values();

        @Nullable
        public static Signal byKey(String key) {
            for (Signal signal : VALUES) {
                if (signal.key.equals(key)) return signal;
            }
            return null;
        }
    }

    /**
     * The table every crew scores against. Swapped wholesale on {@code /reload}, never mutated,
     * so a crew mid-tick either sees the old table or the new one and never a half-written one.
     */
    private static volatile UtilityWeights active = fallback();

    public static UtilityWeights active() {
        return active;
    }

    /** [action][signal] */
    private final double[][] signalWeights;
    /** [action][doctrine axis] */
    private final double[][] axisWeights;

    private UtilityWeights(double[][] signalWeights, double[][] axisWeights) {
        this.signalWeights = signalWeights;
        this.axisWeights = axisWeights;
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

        return new UtilityWeights(signals, axes);
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
                Action action = Action.byKey(entry.getKey());
                if (action == null) {
                    LOGGER.warn("[sewv] AI weights {}: no such action '{}' — ignored",
                            file.getKey(), entry.getKey());
                    continue;
                }
                applied += readAction(file.getKey(), action, entry.getValue(),
                        signals[action.ordinal()], axes[action.ordinal()]);
            }
        }

        if (applied == 0) {
            LOGGER.error("[sewv] No usable AI weights found — falling back to the built-in minimum. "
                    + "Vehicle crews will fight, but not well.");
            return fallback();
        }
        LOGGER.info("[sewv] Loaded {} AI utility weights from {} file(s)", applied, files.size());
        return new UtilityWeights(signals, axes);
    }

    private static int readAction(ResourceLocation source, Action action, JsonElement element,
                                  double[] signals, double[] axes) {
        JsonObject modifiers;
        try {
            modifiers = GsonHelper.convertToJsonObject(element, action.key);
        } catch (RuntimeException e) {
            LOGGER.error("[sewv] AI weights {}: action '{}' is not an object — skipped",
                    source, action.key);
            return 0;
        }

        // A file that names an action replaces that action's whole row, so a pack tuning "attack"
        // gets exactly the attack it wrote rather than its numbers layered over ours.
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
                        source, action.key, key);
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
                    + "— ignored", source, action.key, key);
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
