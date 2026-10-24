package com.neoalive.tacz_sewv.config;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Confidence;
import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.Signal;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Self-check for Easy Mode's tuning table. Run with {@code ./gradlew selfCheckEasyMode}.
 *
 * <p>What it protects: every number in {@link EasyTuning} must make the game <i>easier</i>, and
 * none may turn "slower" into "stopped" — a crew too timid to ever pick anything but HOLD is the
 * parked-statue bug again, wearing a difficulty setting.
 */
public final class EasyTuningSelfCheck {

    private EasyTuningSelfCheck() {}

    public static void main(String[] args) {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("run with -ea");

        everyLeverMakesItEasier();
        fireIntervalNeverBeatsTheWeapon();
        mirroredActionsStayMirrored();
        squadSizeShrinksButNeverVanishes();
        timidCrewsStillFight();
        System.out.println("EasyTuningSelfCheck: OK");
    }

    private static void everyLeverMakesItEasier() {
        assert EasyTuning.FIRE_INTERVAL_MULT >= 1.0 && EasyTuning.COOLDOWN_MULT >= 1.0;
        assert EasyTuning.CREW_SERVED_RELOAD_MULT >= 1.0 && EasyTuning.INDIRECT_COOLDOWN_MULT >= 1.0;
        assert EasyTuning.INDIRECT_DISPERSION_MULT >= 1.0 && EasyTuning.SPREAD_MULT >= 1.0;
        assert EasyTuning.PATH_CADENCE_MULT >= 1.0 && EasyTuning.SCAN_INTERVAL_MULT >= 1.0;
        assert EasyTuning.MIN_PLAY_TICKS_MULT >= 1.0 && EasyTuning.EVENT_COOLDOWN_MULT >= 1.0;
        // Enemy capability multipliers shrink — but never to nothing, or a hull stops dead.
        assert inOpenUnit(EasyTuning.TURRET_TRAVERSE_MULT) : "turret traverse";
        assert inOpenUnit(EasyTuning.DRIVE_THRUST_MULT) : "drive thrust";
        assert inOpenUnit(EasyTuning.SCAN_RADIUS_MULT) : "scan radius";
        assert inOpenUnit(EasyTuning.FORCE_BALANCE_MULT) : "force balance";
        assert inOpenUnit(EasyTuning.HOSTILE_HEALTH_MULT) : "hostile health";
        assert inOpenUnit(EasyTuning.INFANTRY_BULLET_MULT) : "infantry bullet";
        assert inOpenUnit(EasyTuning.SQUAD_SIZE_MULT) : "squad size";
        // Damage the player's side takes shrinks; its health grows.
        assert inOpenUnit(EasyTuning.EXPLOSION_EXTRA_MULT) && inOpenUnit(EasyTuning.PMC_DAMAGE_TAKEN);
        assert inOpenUnit(EasyTuning.FRIENDLY_HULL_DAMAGE_MULT);
        assert EasyTuning.PMC_HEALTH_MULT > 1.0;
        assert inOpenUnit(EasyTuning.VEHICLE_TO_PLAYER_MULT) : "vehicle to player";
        // Every-crew x hostile: a hostile turret must still traverse at a usable fraction.
        assert inOpenUnit(EasyTuning.AI_TURRET_TRAVERSE_MULT) : "ai turret traverse";
        assert EasyTuning.AI_TURRET_TRAVERSE_MULT * EasyTuning.TURRET_TRAVERSE_MULT >= 0.25f : "turret too slow to track";
        // SEM's own values: spread ±1.2°, bursts 3-5, pauses 10-15, retreat at 0.35.
        assert EasyTuning.RIFLE_SPREAD_FLOOR_DEG > 1.2f;
        assert EasyTuning.RIFLE_BURST_MIN >= 1 && EasyTuning.RIFLE_BURST_MIN <= EasyTuning.RIFLE_BURST_MAX
                && EasyTuning.RIFLE_BURST_MAX <= 5;
        assert EasyTuning.RIFLE_BURST_COOLDOWN_MIN >= 15
                && EasyTuning.RIFLE_BURST_COOLDOWN_MIN <= EasyTuning.RIFLE_BURST_COOLDOWN_MAX;
        assert EasyTuning.RIFLE_REACTION_MIN > 3 && EasyTuning.RIFLE_REACTION_MIN <= EasyTuning.RIFLE_REACTION_MAX;
        assert EasyTuning.RETREAT_HEALTH_FRACTION > 0.35 && EasyTuning.RETREAT_HEALTH_FRACTION < 1.0;
        assert EasyTuning.AT_AIM_TICKS > 15 && EasyTuning.AT_POST_RELOAD_TICKS >= 0;
        assert EasyTuning.HESITATION_FLOOR_TICKS > 12;
        assert EasyTuning.spread(4.0) >= 4.0 && EasyTuning.spread(0.0) >= EasyTuning.SPREAD_FLOOR_DEG;
        assert EasyTuning.CONFIDENCE_MALUS > 0.0;
    }

    /** Easy may only ever lengthen the gap between shots, and every weapon still fires. */
    private static void fireIntervalNeverBeatsTheWeapon() {
        for (int natural = 1; natural <= 400; natural++) {
            int easy = EasyTuning.fireInterval(natural);
            assert easy >= natural : "easy interval " + easy + " < natural " + natural;
            assert easy >= EasyTuning.FIRE_INTERVAL_FLOOR_TICKS;
            assert easy < Integer.MAX_VALUE / 2 : "an interval this long is a gate, not a pace";
        }
        assert EasyTuning.fireInterval(0) >= EasyTuning.FIRE_INTERVAL_FLOOR_TICKS : "unknown RPM still paces";
    }

    /** Same rule as the weights self-check: drift here makes every crew turn the same way. */
    private static void mirroredActionsStayMirrored() {
        double left = EasyTuning.actionBias(Action.FLANK_LEFT.name());
        double right = EasyTuning.actionBias(Action.FLANK_RIGHT.name());
        assert left == right : "flank biases drifted: left=" + left + " right=" + right;
        assert EasyTuning.actionBias(Action.HOLD.name()) == 0.0
                : "HOLD sits in both dispatches; biasing it outvotes PATROL out of contact";
        for (Action action : Action.VALUES) {
            if (!action.needsTarget() && action != Action.HOLD) {
                assert EasyTuning.actionBias(action.name()) == 0.0
                        : "out-of-contact action " + action + " must not be biased";
            }
        }
    }

    private static void squadSizeShrinksButNeverVanishes() {
        for (int n = 0; n <= 30; n++) {
            int easy = EasyTuning.squadSize(n);
            assert easy <= n : "squad grew: " + n + " -> " + easy;
            assert n < EasyTuning.SQUAD_SIZE_MIN || easy >= EasyTuning.SQUAD_SIZE_MIN : "squad vanished at " + n;
        }
    }

    /**
     * At the shipped weights, a healthy hostile crew under the full Easy morale overlay still picks
     * a fighting action over HOLD and over RETREAT — timid, not a statue — and a badly hurt one
     * still breaks off.
     */
    private static void timidCrewsStillFight() {
        UtilityWeights weights = UtilityWeights.parse(
                Map.of(ResourceLocation.fromNamespaceAndPath("tacz_sewv", "weights"), shippedWeights()));

        double[] healthy = engagement(1.0);
        double best = Double.NEGATIVE_INFINITY;
        Action bestAction = null;
        for (Action action : Action.VALUES) {
            if (!action.needsTarget()) continue;
            double score = easyScore(weights, action, healthy);
            if (score > best) {
                best = score;
                bestAction = action;
            }
        }
        assert bestAction != Action.HOLD && bestAction != Action.RETREAT
                : "a healthy Easy crew must still fight, picked " + bestAction;

        double[] hurt = engagement(0.15);
        assert easyScore(weights, Action.RETREAT, hurt) > easyScore(weights, Action.ATTACK, hurt)
                : "a badly hurt Easy crew must still break off";

        // The malus must leave room on both sides of the clamp: a crew pinned at 0 is a constant.
        double quiet = Confidence.evaluate(new double[Signal.VALUES.length], Doctrine.NEUTRAL, weights,
                EasyTuning.CONFIDENCE_MALUS);
        assert quiet > 0.0 && quiet < Confidence.NEUTRAL : "easy neutral confidence " + quiet;
    }

    private static double easyScore(UtilityWeights weights, Action action, double[] signals) {
        return weights.score(action, signals, Doctrine.NEUTRAL) + EasyTuning.actionBias(action.name());
    }

    /** The weights self-check's engagement, with confidence shifted by the Easy malus. */
    private static double[] engagement(double health) {
        double[] s = new double[Signal.VALUES.length];
        s[Signal.BASE.ordinal()] = 1.0;
        s[Signal.ENEMY_VISIBLE.ordinal()] = 1.0;
        s[Signal.ENEMY_ARMOR.ordinal()] = 1.0;
        s[Signal.OPEN.ordinal()] = 1.0;
        s[Signal.LOW_HEALTH.ordinal()] = Math.max(0.0, Math.min(1.0, 1.0 - health / 0.5));
        double confidence = (health - 0.5) * 1.2 - EasyTuning.CONFIDENCE_MALUS / Confidence.NEUTRAL;
        s[Signal.CONFIDENCE.ordinal()] = Math.max(-1.0, Math.min(1.0, confidence));
        return s;
    }

    private static boolean inOpenUnit(double v) {
        return v > 0.0 && v < 1.0;
    }

    private static JsonElement shippedWeights() {
        try (var in = EasyTuningSelfCheck.class.getResourceAsStream("/data/tacz_sewv/sewv/ai/weights.json")) {
            if (in == null) throw new AssertionError("shipped weights.json is not on the classpath");
            return JsonParser.parseReader(new java.io.InputStreamReader(in));
        } catch (java.io.IOException e) {
            throw new AssertionError("shipped weights.json unreadable", e);
        }
    }
}
