package com.neoalive.tacz_sewv.entity.ai.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.Signal;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Stage-5 assignment→Signal seam self-check. Run with {@code ./gradlew selfCheckSeam}.
 *
 * <p>Protects: the correct {@code TASKED_*} raises from a published role; flank side overrides
 * id-parity; and mission-command initiative — a tasked crew in genuine local trouble still
 * picks self-preservation over its tasking (shipped <b>and</b> fallback weights).
 */
public final class SeamSelfCheck {

    /** Same magnitude as {@code TacticalBrain.FLANK_TIEBREAK} — the scorer's coin-flip nudge. */
    private static final double FLANK_TIEBREAK = 0.5;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        taskedSignalsRaiseCorrectly();
        taskedFlankOverridesIdParity();
        initiativeSurvivesShipped();
        initiativeSurvivesFallback();

        CrewAssignment.clearAll();
        System.out.println("command-tier seam self-check: OK");
    }

    /** Each assignment role raises exactly its matching TASKED_* (and nothing else). */
    private static void taskedSignalsRaiseCorrectly() {
        CrewAssignment.clearAll();
        assertTasked(Assignment.Role.BASE_OF_FIRE, null, Signal.TASKED_BASE_OF_FIRE);
        assertTasked(Assignment.Role.MANEUVER, Assignment.FlankSide.LEFT, Signal.TASKED_FLANK);
        assertTasked(Assignment.Role.MANEUVER, Assignment.FlankSide.RIGHT, Signal.TASKED_FLANK);
        assertTasked(Assignment.Role.MANEUVER, null, Signal.TASKED_ADVANCE);
        assertTasked(Assignment.Role.OVERWATCH, null, Signal.TASKED_HOLD);
        assertTasked(Assignment.Role.RESERVE, null, Signal.TASKED_HOLD);
        assertTasked(Assignment.Role.HOLD, null, Signal.TASKED_HOLD);
        assertTasked(Assignment.Role.WITHDRAW, null, Signal.TASKED_WITHDRAW);
    }

    private static void assertTasked(Assignment.Role role, Assignment.FlankSide side, Signal expected) {
        int id = 100 + role.ordinal() * 10 + (side == null ? 0 : side.ordinal() + 1);
        CrewAssignment.clear(id);
        CrewAssignment.publish(new Assignment(id, role, null, side, 0.0, 0.0));
        double[] s = new double[Signal.VALUES.length];
        CrewAssignment.raiseTaskSignals(id, s);
        for (Signal sig : Signal.VALUES) {
            if (!sig.key.startsWith("tasked")) continue;
            double got = s[sig.ordinal()];
            if (sig == expected) {
                assert got == 1.0 : role + "/" + side + " should raise " + expected.key + ", was " + got;
            } else {
                assert got == 0.0 : role + "/" + side + " must not raise " + sig.key + ", was " + got;
            }
        }
    }

    /**
     * Odd entity id prefers {@link Action#FLANK_RIGHT} by parity; a LEFT flank assignment must
     * make {@link Action#FLANK_LEFT} win the flank pair.
     */
    private static void taskedFlankOverridesIdParity() {
        CrewAssignment.clearAll();
        int oddId = 7; // (7 & 1) == 1 → id-parity prefers RIGHT
        assert TacticalBrain.preferredFlankOf(oddId) == Action.FLANK_RIGHT
                : "sanity: odd id without assignment prefers FLANK_RIGHT";

        CrewAssignment.publish(new Assignment(oddId, Assignment.Role.MANEUVER, null,
                Assignment.FlankSide.LEFT, 10.0, 10.0));
        assert TacticalBrain.preferredFlankOf(oddId) == Action.FLANK_LEFT
                : "TASKED_FLANK LEFT must override id-parity FLANK_RIGHT";

        UtilityWeights weights = shipped();
        double[] s = calmEngagement();
        s[Signal.TASKED_FLANK.ordinal()] = 1.0;
        Action preferred = TacticalBrain.preferredFlankOf(oddId);
        double left = weights.score(Action.FLANK_LEFT, s, Doctrine.NEUTRAL);
        double right = weights.score(Action.FLANK_RIGHT, s, Doctrine.NEUTRAL);
        if (preferred == Action.FLANK_LEFT) left += FLANK_TIEBREAK;
        if (preferred == Action.FLANK_RIGHT) right += FLANK_TIEBREAK;
        assert left > right
                : "tasked LEFT must score FLANK_LEFT over FLANK_RIGHT: L=" + left + " R=" + right;
    }

    /** Shipped table: tasked + dying still picks retreat/smoke, not the tasking. */
    private static void initiativeSurvivesShipped() {
        assertInitiativeSurvives(shipped(), "shipped");
    }

    /** Fallback table: same initiative contract when the datapack is broken. */
    private static void initiativeSurvivesFallback() {
        assertInitiativeSurvives(UtilityWeights.fallback(), "fallback");
    }

    private static void assertInitiativeSurvives(UtilityWeights weights, String label) {
        // Hurt BoF-tasked crew — attack is the tasking's natural home.
        double[] hurtBoF = troubleEngagement();
        hurtBoF[Signal.TASKED_BASE_OF_FIRE.ordinal()] = 1.0;
        Action pickBoF = bestCombat(weights, hurtBoF, 2);
        assert isSelfPreservation(pickBoF)
                : label + " BoF-tasked hurt crew must retreat/smoke, picked " + pickBoF.key;

        // Hurt flank-tasked crew — flank is strongly biased by TASKED_FLANK.
        double[] hurtFlank = troubleEngagement();
        hurtFlank[Signal.TASKED_FLANK.ordinal()] = 1.0;
        Action pickFlank = bestCombat(weights, hurtFlank, 3);
        assert isSelfPreservation(pickFlank)
                : label + " flank-tasked hurt crew must retreat/smoke, picked " + pickFlank.key;

        // Hurt withdraw-tasked is fine either way (tasking agrees with preservation), but
        // advance-tasked must not push into the fight.
        double[] hurtAdvance = troubleEngagement();
        hurtAdvance[Signal.TASKED_ADVANCE.ordinal()] = 1.0;
        Action pickAdvance = bestCombat(weights, hurtAdvance, 4);
        assert isSelfPreservation(pickAdvance)
                : label + " advance-tasked hurt crew must retreat/smoke, picked " + pickAdvance.key;
    }

    private static boolean isSelfPreservation(Action a) {
        return a == Action.RETREAT || a == Action.DEPLOY_SMOKE;
    }

    /**
     * Combat-set winner under the same flank tiebreak the live scorer uses. Feasibility is
     * assumed (smoke ready, target held) — this is a weight-table contract check.
     */
    private static Action bestCombat(UtilityWeights weights, double[] signals, int unitId) {
        Action preferred = TacticalBrain.preferredFlankOf(unitId);
        Action best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Action action : new Action[]{
                Action.ATTACK, Action.ADVANCE, Action.HOLD, Action.RETREAT, Action.DEPLOY_SMOKE,
                Action.FLANK_LEFT, Action.FLANK_RIGHT
        }) {
            double score = weights.score(action, signals, Doctrine.NEUTRAL);
            if (action == preferred) score += FLANK_TIEBREAK;
            if (best == null || score > bestScore) {
                best = action;
                bestScore = score;
            }
        }
        return best;
    }

    private static double[] calmEngagement() {
        double[] s = zeroSignals();
        s[Signal.BASE.ordinal()] = 1.0;
        s[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = 1.0;
        s[Signal.OPEN.ordinal()] = 1.0;
        return s;
    }

    /** High LOW_HEALTH + RECENTLY_HIT + smoke available — genuine local trouble. */
    private static double[] troubleEngagement() {
        double[] s = calmEngagement();
        s[Signal.LOW_HEALTH.ordinal()] = 1.0;
        s[Signal.RECENTLY_HIT.ordinal()] = 1.0;
        s[Signal.SMOKE_READY.ordinal()] = 1.0;
        s[Signal.CONFIDENCE.ordinal()] = -0.8;
        return s;
    }

    private static double[] zeroSignals() {
        return new double[Signal.VALUES.length];
    }

    private static UtilityWeights shipped() {
        return UtilityWeights.parse(Map.of(
                new ResourceLocation("tacz_sewv", "weights"), shippedWeights()));
    }

    private static JsonElement shippedWeights() {
        try (var in = SeamSelfCheck.class.getResourceAsStream(
                "/data/tacz_sewv/sewv/ai/weights.json")) {
            if (in == null) throw new AssertionError("shipped weights.json is not on the classpath");
            return JsonParser.parseReader(new java.io.InputStreamReader(in));
        } catch (java.io.IOException e) {
            throw new AssertionError("shipped weights.json unreadable", e);
        }
    }

    private SeamSelfCheck() {}
}
