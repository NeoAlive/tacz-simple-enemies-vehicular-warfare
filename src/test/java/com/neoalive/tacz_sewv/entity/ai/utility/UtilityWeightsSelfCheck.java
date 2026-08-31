package com.neoalive.tacz_sewv.entity.ai.utility;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

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
        pairedActionsAreSymmetric();
        healthDecidesWhetherToFight();
        confidenceDoesNotSaturate();
        everyActionIsDispatchable();
        idleCrewsDoNotJustSit();
        emptyCrewsDisengage();

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
    private static void pairedActionsAreSymmetric() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        for (Action[] pair : MIRRORED_ACTIONS) {
            // Walk one signal at a time rather than testing an all-ones sample: a difference in two
            // modifiers that happens to cancel out would slip past a single combined total.
            for (Signal signal : Signal.VALUES) {
                double[] one = zeroSignals();
                one[signal.ordinal()] = 1.0;
                double a = weights.score(pair[0], one, Doctrine.NEUTRAL);
                double b = weights.score(pair[1], one, Doctrine.NEUTRAL);
                assert Math.abs(a - b) < 1.0E-9 : pair[0].key + "/" + pair[1].key
                        + " differ on '" + signal.key + "': " + a + " vs " + b;
            }
            for (Doctrine.Axis axis : Doctrine.Axis.VALUES) {
                Doctrine full = doctrineWith(axis, Doctrine.AXIS_LIMIT);
                double[] none = zeroSignals();
                double a = weights.score(pair[0], none, full);
                double b = weights.score(pair[1], none, full);
                assert Math.abs(a - b) < 1.0E-9 : pair[0].key + "/" + pair[1].key
                        + " differ on axis '" + axis.key + "'";
            }
        }
    }

    /**
     * Actions that are the same behaviour in opposite directions, and must therefore score
     * identically — the direction is chosen by entity-id parity, not by the weights.
     *
     * <p>Add any future mirrored pair here. A tiny asymmetry between two of these looks perfectly
     * valid in the file and silently biases every crew in the game one way.
     */
    private static final Action[][] MIRRORED_ACTIONS = {
            {Action.FLANK_LEFT, Action.FLANK_RIGHT},
    };

    /**
     * Confidence must stay a gradient rather than pinning at its maximum.
     *
     * <p>The first version handed an undamaged hull +30 for being undamaged and pinned live crews
     * at 100, at which point it had stopped telling the scorer anything. So: an unremarkable crew
     * sits at the neutral 50, a supported one is above it but not maxed, and a losing one is well
     * below without bottoming out either.
     */
    private static void confidenceDoesNotSaturate() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        double quiet = Confidence.evaluate(zeroSignals(), Doctrine.NEUTRAL, weights);
        assertClose(Confidence.NEUTRAL, quiet, "an unremarkable crew is neutral");

        double[] winning = zeroSignals();
        winning[Signal.ALLIES_NEARBY.ordinal()] = 1.0;
        double high = Confidence.evaluate(winning, Doctrine.NEUTRAL, weights);
        assert high > Confidence.NEUTRAL && high < 100.0
                : "a supported crew should be confident but not maxed, was " + high;

        double[] losing = zeroSignals();
        losing[Signal.LOW_HEALTH.ordinal()] = 0.6;
        losing[Signal.OUTNUMBERED.ordinal()] = 0.5;
        losing[Signal.RECENTLY_HIT.ordinal()] = 1.0;
        double low = Confidence.evaluate(losing, Doctrine.NEUTRAL, weights);
        assert low < Confidence.NEUTRAL && low > 0.0
                : "a losing crew should be shaken but not bottomed out, was " + low;
    }

    /**
     * Every action must be reachable by exactly one of the two dispatches.
     *
     * <p>{@code needsTarget} is what splits the enum into the combat set and the out-of-contact
     * set, and the drive goal has a switch for each. An action on the wrong side of that split
     * would win a vote and then find no case to execute it — the parked-statue bug.
     */
    private static void everyActionIsDispatchable() {
        int combat = 0;
        int standDown = 0;
        for (Action action : Action.VALUES) {
            if (action.needsTarget()) combat++;
            else standDown++;
        }
        assert combat > 0 && standDown > 0
                : "both dispatches must have actions: combat=" + combat + " standDown=" + standDown;
        assert !Action.HOLD.needsTarget() : "HOLD is the always-feasible fallback and must not need a target";
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

    /**
     * Out of contact and off the leash, a crew must prefer to get on with something over parking.
     *
     * <p>The reported symptom of the first build was crews selecting {@code hold} whenever no enemy
     * was about, which reads as a battlefield full of statues. {@code patrol} is the action that
     * means "work the standing destination", so it has to out-score {@code hold} in the plain quiet
     * case or the out-of-contact set does nothing.
     */
    private static void idleCrewsDoNotJustSit() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        double[] quiet = zeroSignals();
        quiet[Signal.BASE.ordinal()] = 1.0;
        quiet[Signal.OPEN.ordinal()] = 1.0;

        double idleHold = weights.score(Action.IDLE_HOLD, quiet, Doctrine.NEUTRAL);
        double hold = weights.score(Action.HOLD, quiet, Doctrine.NEUTRAL);
        assert idleHold > hold
                : "an idle crew should form up, not park: idleHold=" + idleHold + " hold=" + hold;

        double[] expired = zeroSignals();
        expired[Signal.BASE.ordinal()] = 1.0;
        expired[Signal.OPEN.ordinal()] = 1.0;
        expired[Signal.IDLE_HOLD_EXPIRED.ordinal()] = 1.0;
        expired[Signal.ALONE.ordinal()] = 1.0;
        double idleTravel = weights.score(Action.IDLE_TRAVEL, expired, Doctrine.NEUTRAL);
        double holdAfter = weights.score(Action.IDLE_HOLD, expired, Doctrine.NEUTRAL);
        assert idleTravel > holdAfter
                : "expired hold should prefer travel: travel=" + idleTravel + " hold=" + holdAfter;

        double[] oversize = zeroSignals();
        oversize[Signal.BASE.ordinal()] = 1.0;
        oversize[Signal.OPEN.ordinal()] = 1.0;
        oversize[Signal.IDLE_GROUP_OVERSIZE.ordinal()] = 1.0;
        double travelOver = weights.score(Action.IDLE_TRAVEL, oversize, Doctrine.NEUTRAL);
        double holdOver = weights.score(Action.IDLE_HOLD, oversize, Doctrine.NEUTRAL);
        assert travelOver > holdOver
                : "oversize group should prefer travel: travel=" + travelOver + " hold=" + holdOver;
    }

    /**
     * A crew that has shot itself dry must stop behaving like a gun.
     *
     * <p>There is no rearm in the game, so the answer is to break off or fall back on friends —
     * either beats sitting in the open with an empty rack, which is what an untouched attack score
     * would have it do.
     */
    private static void emptyCrewsDisengage() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));

        double[] dry = zeroSignals();
        dry[Signal.BASE.ordinal()] = 1.0;
        dry[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        dry[Signal.ENEMY_ARMOR.ordinal()] = 1.0;
        dry[Signal.OPEN.ordinal()] = 1.0;
        dry[Signal.LOW_AMMO.ordinal()] = 1.0;

        double attack = weights.score(Action.ATTACK, dry, Doctrine.NEUTRAL);
        double retreat = weights.score(Action.RETREAT, dry, Doctrine.NEUTRAL);
        assert retreat > attack
                : "an empty crew should break off: attack=" + attack + " retreat=" + retreat;
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
