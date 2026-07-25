package com.neoalive.tacz_sewv.entity.ai.utility;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights.Signal;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-check for the utility scorer. Run with {@code ./gradlew selfCheck}.
 *
 * <p>What it protects: the weights file is a hundred numbers keyed by name, and a key that lands in
 * the wrong slot does not crash — it silently gives every crew in the game the wrong behaviour
 * forever. So this asserts the arithmetic and the key mapping, which is where a real bug hides.
 *
 * <p>Deliberately a plain {@code main} with {@code assert}: adding a test framework to a mod with no
 * test suite is a dependency for four assertions.
 */
public final class UtilityWeightsSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        signalWeightsAreSummed();
        doctrineAxesAreNormalised();
        unknownKeysAreIgnoredNotFatal();
        laterFilesReplaceAnActionWholesale();
        shippedWeightsLoad();
        flanksAreSymmetric();
        healthDecidesWhetherToFight();

        System.out.println("utility scorer self-check: OK");
    }

    /** A score is the plain sum of weight x signal over every named modifier. */
    private static void signalWeightsAreSummed() {
        UtilityWeights weights = parse("""
                { "attack": { "base": 10, "enemyVisible": 30, "lowHealth": -40 } }
                """);

        double[] signals = zeroSignals();
        signals[Signal.BASE.ordinal()] = 1.0;
        signals[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        signals[Signal.LOW_HEALTH.ordinal()] = 0.5;

        // 10*1 + 30*1 + -40*0.5 = 20
        double score = weights.score(Action.ATTACK, signals, Doctrine.NEUTRAL);
        assertClose(20.0, score, "attack score");

        // An action nobody wrote weights for scores a flat zero rather than misreading someone
        // else's row — the check that the per-action arrays are actually separate.
        assertClose(0.0, weights.score(Action.RETREAT, signals, Doctrine.NEUTRAL), "unwritten action");
    }

    /**
     * A doctrine axis is a modifier key like any other, and it arrives normalised: a weight is
     * "points at FULL doctrine", so a +5 axis contributes the whole weight and +1 contributes a
     * fifth of it. Getting this wrong scales every doctrine effect by five.
     */
    private static void doctrineAxesAreNormalised() {
        UtilityWeights weights = parse("""
                { "retreat": { "preservation": 25 } }
                """);
        double[] signals = zeroSignals();

        assertClose(0.0, weights.score(Action.RETREAT, signals, Doctrine.NEUTRAL), "neutral doctrine");

        // Doctrine has no public constructor by design, so drive it through the real config-free
        // path: NEUTRAL is all-zero, and a full-scale axis is what the weight is calibrated to.
        Doctrine maxPreservation = doctrineWith(Doctrine.Axis.PRESERVATION, Doctrine.AXIS_LIMIT);
        assertClose(25.0, weights.score(Action.RETREAT, signals, maxPreservation), "full preservation");

        Doctrine minPreservation = doctrineWith(Doctrine.Axis.PRESERVATION, -Doctrine.AXIS_LIMIT);
        assertClose(-25.0, weights.score(Action.RETREAT, signals, minPreservation), "negative preservation");
    }

    /** A datapack typo must cost one modifier, never the whole AI. */
    private static void unknownKeysAreIgnoredNotFatal() {
        UtilityWeights weights = parse("""
                { "attack": { "base": 7, "notARealSignal": 999 },
                  "notARealAction": { "base": 999 } }
                """);
        double[] signals = zeroSignals();
        signals[Signal.BASE.ordinal()] = 1.0;
        assertClose(7.0, weights.score(Action.ATTACK, signals, Doctrine.NEUTRAL), "typo tolerated");
    }

    /**
     * A pack that names an action gets exactly the action it wrote — its numbers must not be
     * layered on top of ours, or a pack "lowering" a weight would inherit whatever it omitted.
     */
    private static void laterFilesReplaceAnActionWholesale() {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(new ResourceLocation("tacz_sewv", "weights"),
                JsonParser.parseString("{ \"attack\": { \"base\": 10, \"enemyVisible\": 30 } }"));
        files.put(new ResourceLocation("zzpack", "weights"),
                JsonParser.parseString("{ \"attack\": { \"base\": 1 } }"));

        UtilityWeights weights = UtilityWeights.parse(files);
        double[] signals = zeroSignals();
        signals[Signal.BASE.ordinal()] = 1.0;
        signals[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;

        // 1, not 31: the override dropped enemyVisible with the rest of the row.
        assertClose(1.0, weights.score(Action.ATTACK, signals, Doctrine.NEUTRAL), "wholesale override");
    }

    /**
     * The shipped weights file parses, and every action in the enum has a row in it. An action with
     * no weights scores a flat zero everywhere and can only ever win by accident.
     */
    private static void shippedWeightsLoad() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        double[] loud = zeroSignals();
        java.util.Arrays.fill(loud, 1.0);
        for (Action action : Action.VALUES) {
            double score = weights.score(action, loud, Doctrine.NEUTRAL);
            assert score != 0.0 : "shipped weights have no row for action '" + action.key + "'";
        }
    }

    /**
     * The two flanks must score identically on the shipped weights.
     *
     * <p>Which way a crew goes round is decided by entity-id parity so a platoon splits both ways.
     * If one flank's weight row drifts from the other's — a modifier added to one and forgotten on
     * the other — that split silently becomes "everyone goes right", and nothing about it looks
     * wrong in game. This caught exactly that during development.
     */
    private static void flanksAreSymmetric() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        // Walk one signal at a time rather than testing an all-ones sample: a difference in two
        // modifiers that happens to cancel out would slip past a single combined total.
        for (Signal signal : Signal.VALUES) {
            double[] one = zeroSignals();
            one[signal.ordinal()] = 1.0;
            double left = weights.score(Action.FLANK_LEFT, one, Doctrine.NEUTRAL);
            double right = weights.score(Action.FLANK_RIGHT, one, Doctrine.NEUTRAL);
            assert Math.abs(left - right) < 1.0E-9
                    : "flank weights differ on '" + signal.key + "': left=" + left + " right=" + right;
        }
        for (Doctrine.Axis axis : Doctrine.Axis.VALUES) {
            Doctrine full = doctrineWith(axis, Doctrine.AXIS_LIMIT);
            double[] none = zeroSignals();
            double left = weights.score(Action.FLANK_LEFT, none, full);
            double right = weights.score(Action.FLANK_RIGHT, none, full);
            assert Math.abs(left - right) < 1.0E-9
                    : "flank weights differ on axis '" + axis.key + "'";
        }
    }

    /**
     * The one behaviour the shipped weights must actually produce: a healthy crew fights, and a
     * badly hurt one breaks off.
     *
     * <p>This is what the hand-written doctrine did with a hard {@code health < 0.25} threshold, and
     * the whole point of the utility layer is that the same behaviour now falls out of the numbers.
     * If a weights edit inverts it, every vehicle in the game either suicides or refuses to fight —
     * so it is worth one assertion even though tuning is otherwise nobody's business but the
     * datapack's.
     */
    private static void healthDecidesWhetherToFight() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        double healthyAttack = weights.score(Action.ATTACK, engagement(1.0), Doctrine.NEUTRAL);
        double healthyRetreat = weights.score(Action.RETREAT, engagement(1.0), Doctrine.NEUTRAL);
        assert healthyAttack > healthyRetreat
                : "a healthy crew must fight: attack=" + healthyAttack + " retreat=" + healthyRetreat;

        double hurtAttack = weights.score(Action.ATTACK, engagement(0.15), Doctrine.NEUTRAL);
        double hurtRetreat = weights.score(Action.RETREAT, engagement(0.15), Doctrine.NEUTRAL);
        assert hurtRetreat > hurtAttack
                : "a badly hurt crew must break off: attack=" + hurtAttack + " retreat=" + hurtRetreat;
    }

    /** A crew in contact on its preferred ring at the given health, with everything else neutral. */
    private static double[] engagement(double health) {
        double[] s = zeroSignals();
        s[Signal.BASE.ordinal()] = 1.0;
        s[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = 1.0;
        s[Signal.OPEN.ordinal()] = 1.0;
        // The same ramps TacticalBrain.sample derives from Facts: low health builds from half a
        // tank downwards, and confidence tracks it.
        s[Signal.LOW_HEALTH.ordinal()] = Math.max(0.0, Math.min(1.0, 1.0 - health / 0.5));
        s[Signal.CONFIDENCE.ordinal()] = Math.max(-1.0, Math.min(1.0, (health - 0.5) * 1.2));
        return s;
    }

    // ---- helpers ----

    private static JsonElement shippedWeights() {
        try (var in = UtilityWeightsSelfCheck.class.getResourceAsStream(
                "/data/tacz_sewv/sewv/ai/weights.json")) {
            if (in == null) throw new AssertionError("shipped weights.json is not on the classpath");
            return JsonParser.parseReader(new java.io.InputStreamReader(in));
        } catch (java.io.IOException e) {
            throw new AssertionError("shipped weights.json unreadable", e);
        }
    }

    private static UtilityWeights parse(String json) {
        return UtilityWeights.parse(Map.of(
                new ResourceLocation("tacz_sewv", "weights"), JsonParser.parseString(json)));
    }

    private static double[] zeroSignals() {
        return new double[Signal.VALUES.length];
    }

    /**
     * A doctrine with one axis at a chosen value. Built through the config-free path so the check
     * needs no Forge config baked: the presets are null until a server starts, and this only ever
     * needs a hand-made one.
     */
    private static Doctrine doctrineWith(Doctrine.Axis axis, int value) {
        int[] axes = new int[Doctrine.Axis.VALUES.length];
        axes[axis.ordinal()] = value;
        return Doctrine.ofAxes(axes);
    }

    private static void assertClose(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1.0E-9
                : what + ": expected " + expected + " but was " + actual;
    }

    private UtilityWeightsSelfCheck() {}
}
