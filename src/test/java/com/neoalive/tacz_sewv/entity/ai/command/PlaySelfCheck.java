package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Map;

import net.minecraft.resources.ResourceLocation;

import com.neoalive.tacz_sewv.entity.ai.utility.Signal;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;
import com.neoalive.tacz_sewv.map.BattleFieldMarker;

/**
 * Headless self-check for Stage 4 plays. Run via {@code ./gradlew selfCheckPlay}.
 */
public final class PlaySelfCheck {

    private static final int OUR = 0;
    private static final int ENEMY = 1;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        feasibilityBars();
        frontalRoleSplitGeometry();
        stillValidAbort();
        selectionHysteresis();
        holdDefendAlwaysWinsSomething();
        playWeightsRouteNotIntoActions();

        System.out.println("command play self-check: OK");
    }

    private static void feasibilityBars() {
        BattleField bf = freshBf();
        fillOpposing(bf, 1.2, true, true, 1);
        GroupSnapshot four = group(4, 0, 0);
        GroupSnapshot two = group(2, 0, 0);
        GroupSnapshot one = group(1, 0, 0);

        assertTrue(HoldDefend.INSTANCE.feasible(bf, one), "HoldDefend always feasible");
        assertTrue(FrontalFixAndFlank.INSTANCE.feasible(bf, two), "FFF needs 2+ and a flank");
        assertTrue(!FrontalFixAndFlank.INSTANCE.feasible(bf, one), "FFF infeasible below 2");

        assertTrue(DoubleEnvelopment.INSTANCE.feasible(bf, four), "DE with 4, both flanks, ratio≥1");
        assertTrue(!DoubleEnvelopment.INSTANCE.feasible(bf, two), "DE infeasible below 4");

        bf.openFlankRight = false;
        assertTrue(!DoubleEnvelopment.INSTANCE.feasible(bf, four), "DE infeasible with one flank");
        assertTrue(FrontalFixAndFlank.INSTANCE.feasible(bf, two), "FFF ok with one flank");

        bf.openFlankLeft = false;
        assertTrue(!FrontalFixAndFlank.INSTANCE.feasible(bf, two), "FFF infeasible with no flanks");

        fillOpposing(bf, 0.5, true, true, 1);
        assertTrue(FightingWithdrawal.INSTANCE.feasible(bf, two), "withdrawal when outnumbered");
        assertTrue(!DoubleEnvelopment.INSTANCE.feasible(bf, four), "DE infeasible when outnumbered");
    }

    private static void frontalRoleSplitGeometry() {
        BattleField bf = freshBf();
        // Enemy north (−Z), us south (+Z) — axis enemy→us = +Z.
        // left = (−axisZ, axisX) = (−1, 0) = world −X (west).
        fillOpposing(bf, 1.0, true, false, 1);
        bf.friendlyCentroidX = 0;
        bf.friendlyCentroidZ = 40;
        bf.enemyCentroidX = 0;
        bf.enemyCentroidZ = -40;
        bf.axisX = 0;
        bf.axisZ = 1;
        GroupSnapshot g = new GroupSnapshot(
                new int[]{1, 2, 3},
                new double[]{-20, 0, 20},
                new double[]{40, 40, 40});

        Roles roles = FrontalFixAndFlank.INSTANCE.assignRoles(bf, g);
        assertEq(3, roles.size(), "FFF assigns every member");
        assertTrue(roles.count(Assignment.Role.MANEUVER) >= 1, "FFF has maneuver");
        assertTrue(roles.count(Assignment.Role.BASE_OF_FIRE) >= 1, "FFF has BoF");

        double expectX = BattleFieldMarker.flankMarkX(bf.enemyCentroidX, bf.axisX, bf.axisZ, +1);
        double expectZ = BattleFieldMarker.flankMarkZ(bf.enemyCentroidZ, bf.axisX, bf.axisZ, +1);
        boolean maneuverOnEnemyFlank = false;
        for (Assignment a : roles.assignments) {
            if (a.role != Assignment.Role.MANEUVER) continue;
            assertEq(Assignment.FlankSide.LEFT, a.flankSide, "open flank is LEFT");
            assertNear(expectX, a.destX, 1.0e-9, "maneuver dest = enemy-side flank mark X");
            assertNear(expectZ, a.destZ, 1.0e-9, "er dest = enemy-side flank mark Z");
            assertTrue(Math.abs(a.destZ - bf.enemyCentroidZ) < Math.abs(a.destZ - bf.friendlyCentroidZ),
                    "maneuver mark is on the enemy side of the fight");
            maneuverOnEnemyFlank = true;
        }
        assertTrue(maneuverOnEnemyFlank, "at least one maneuver assignment");
    }

    private static void stillValidAbort() {
        BattleField bf = freshBf();
        fillOpposing(bf, 1.2, true, true, 1);
        GroupSnapshot g = group(4, 0, 0);
        Roles roles = DoubleEnvelopment.INSTANCE.assignRoles(bf, g);
        assertTrue(DoubleEnvelopment.INSTANCE.stillValid(bf, g, roles), "DE valid at start");

        bf.openFlankLeft = false;
        assertTrue(!DoubleEnvelopment.INSTANCE.stillValid(bf, g, roles),
                "DE aborts when a flank closes");

        fillOpposing(bf, 1.2, true, true, 1);
        roles = DoubleEnvelopment.INSTANCE.assignRoles(bf, g);
        GroupSnapshot broken = stripLeftWing(g, roles);
        assertTrue(!DoubleEnvelopment.INSTANCE.stillValid(bf, broken, roles),
                "DE aborts when a wing is broken");

        // FFF aborts when its chosen flank closes.
        fillOpposing(bf, 1.0, true, false, 1);
        GroupSnapshot two = group(2, 0, 0);
        Roles fff = FrontalFixAndFlank.INSTANCE.assignRoles(bf, two);
        assertTrue(FrontalFixAndFlank.INSTANCE.stillValid(bf, two, fff), "FFF valid");
        bf.openFlankLeft = false;
        assertTrue(!FrontalFixAndFlank.INSTANCE.stillValid(bf, two, fff), "FFF aborts on flank close");
    }

    private static void selectionHysteresis() {
        UtilityWeights w = UtilityWeights.fallback();
        // Boost FFF so it wins when feasible.
        String pack = """
                {
                  "play.frontal_fix_and_flank": { "base": 80 },
                  "play.hold_defend": { "base": 15 },
                  "play.fighting_withdrawal": { "base": 10 },
                  "attack": { "base": 1 }
                }
                """;
        w = UtilityWeights.parse(Map.of(new ResourceLocation("tacz_sewv", "weights"),
                com.google.gson.JsonParser.parseString(pack)));

        BattleField bf = freshBf();
        fillOpposing(bf, 1.0, true, false, 1);
        GroupSnapshot g = group(3, 0, 0);

        PlaySelection.Result first = PlaySelection.select(
                bf, g, null, Long.MIN_VALUE, 0, null, 200, 10.0, w);
        assertEq(PlayId.FRONTAL_FIX_AND_FLANK, first.play(), "first commit prefers FFF");

        // Marginally better Hold inside min ticks must NOT preempt.
        String holdBoost = """
                {
                  "play.frontal_fix_and_flank": { "base": 80 },
                  "play.hold_defend": { "base": 85 },
                  "attack": { "base": 1 }
                }
                """;
        UtilityWeights w2 = UtilityWeights.parse(Map.of(new ResourceLocation("tacz_sewv", "weights"),
                com.google.gson.JsonParser.parseString(holdBoost)));
        PlaySelection.Result held = PlaySelection.select(
                bf, g, PlayId.FRONTAL_FIX_AND_FLANK, 0, 50, first.roles(), 200, 10.0, w2);
        assertEq(PlayId.FRONTAL_FIX_AND_FLANK, held.play(), "inside min ticks: keep incumbent");
        assertTrue(!held.switched(), "no switch inside min ticks");

        // !stillValid aborts immediately even inside min ticks.
        bf.openFlankLeft = false;
        bf.openFlankRight = false;
        PlaySelection.Result aborted = PlaySelection.select(
                bf, g, PlayId.FRONTAL_FIX_AND_FLANK, 0, 50, first.roles(), 200, 10.0, w2);
        assertTrue(aborted.aborted(), "stillValid false aborts immediately");
        assertTrue(aborted.play() != PlayId.FRONTAL_FIX_AND_FLANK
                        || !FrontalFixAndFlank.INSTANCE.feasible(bf, g),
                "abort leaves infeasible FFF");
        assertTrue(HoldDefend.INSTANCE.feasible(bf, g), "Hold still feasible after abort");
    }

    private static void holdDefendAlwaysWinsSomething() {
        UtilityWeights w = UtilityWeights.fallback();
        BattleField bf = freshBf();
        // Empty / no flanks / no pockets — only Hold is feasible.
        bf.populated = true;
        bf.friendlyCount = 1;
        bf.enemyCount = 0;
        bf.forceBalance = 1.0;
        GroupSnapshot g = group(1, 0, 0);
        PlaySelection.Result r = PlaySelection.select(
                bf, g, null, Long.MIN_VALUE, 0, null, 200, 10.0, w);
        assertEq(PlayId.HOLD_DEFEND, r.play(), "fallback menu → HoldDefend");
        assertTrue(w.scorePlay(PlayId.HOLD_DEFEND, baseSignals(),
                com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.NEUTRAL) > 0,
                "fallback HoldDefend BASE > 0");
    }

    private static void playWeightsRouteNotIntoActions() {
        String json = """
                {
                  "play.hold_defend": { "base": 42 },
                  "play.not_a_real_play": { "base": 99 },
                  "attack": { "base": 7 }
                }
                """;
        UtilityWeights w = UtilityWeights.parse(Map.of(
                new ResourceLocation("tacz_sewv", "weights"),
                com.google.gson.JsonParser.parseString(json)));
        double[] s = baseSignals();
        assertNear(42.0, w.scorePlay(PlayId.HOLD_DEFEND, s,
                        com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.NEUTRAL),
                1.0e-9, "play.hold_defend routes to play storage");
        // Attack row is independent — play keys must not land in Action rows.
        assertNear(7.0, w.score(com.neoalive.tacz_sewv.entity.ai.utility.Action.ATTACK, s,
                        com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.NEUTRAL),
                1.0e-9, "action row unchanged by play.* keys");

        // Second file replaces hold_defend wholesale (atomic apply of complete table).
        String second = """
                {
                  "play.hold_defend": { "base": 11 },
                  "attack": { "base": 7 }
                }
                """;
        UtilityWeights w2 = UtilityWeights.parse(Map.of(
                new ResourceLocation("tacz_sewv", "a"),
                com.google.gson.JsonParser.parseString(json),
                new ResourceLocation("tacz_sewv", "b"),
                com.google.gson.JsonParser.parseString(second)));
        assertNear(11.0, w2.scorePlay(PlayId.HOLD_DEFEND, s,
                        com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.NEUTRAL),
                1.0e-9, "later file replaces play row wholesale");
    }

    // ---- fixtures ----

    private static BattleField freshBf() {
        BattleField bf = new BattleField();
        bf.clear();
        return bf;
    }

    private static void fillOpposing(BattleField bf, double force, boolean left, boolean right, int pockets) {
        bf.populated = true;
        bf.friendlyCount = 4;
        bf.enemyCount = Math.max(1, (int) Math.round(4 / Math.max(force, 0.01)));
        bf.forceBalance = force;
        bf.friendlyCentroidX = 0;
        bf.friendlyCentroidZ = 40;
        bf.enemyCentroidX = 0;
        bf.enemyCentroidZ = -40;
        bf.axisX = 0;
        bf.axisZ = 1;
        bf.openFlankLeft = left;
        bf.openFlankRight = right;
        bf.pocketCount = pockets;
        if (pockets > 0) {
            bf.pocketX[0] = bf.enemyCentroidX;
            bf.pocketZ[0] = bf.enemyCentroidZ;
        }
    }

    private static GroupSnapshot group(int n, double x0, double z0) {
        int[] ids = new int[n];
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i + 1;
            xs[i] = x0 + i * 5;
            zs[i] = z0;
        }
        return new GroupSnapshot(ids, xs, zs);
    }

    private static GroupSnapshot stripLeftWing(GroupSnapshot g, Roles roles) {
        int keep = 0;
        for (Assignment a : roles.assignments) {
            if (a.role == Assignment.Role.MANEUVER && a.flankSide == Assignment.FlankSide.LEFT) continue;
            keep++;
        }
        int[] ids = new int[keep];
        double[] xs = new double[keep];
        double[] zs = new double[keep];
        int j = 0;
        for (int i = 0; i < g.size(); i++) {
            boolean leftWing = false;
            for (Assignment a : roles.assignments) {
                if (a.unitId == g.memberIds[i]
                        && a.role == Assignment.Role.MANEUVER
                        && a.flankSide == Assignment.FlankSide.LEFT) {
                    leftWing = true;
                    break;
                }
            }
            if (leftWing) continue;
            ids[j] = g.memberIds[i];
            xs[j] = g.x[i];
            zs[j] = g.z[i];
            j++;
        }
        return new GroupSnapshot(ids, xs, zs);
    }

    private static double[] baseSignals() {
        double[] s = new double[Signal.VALUES.length];
        s[Signal.BASE.ordinal()] = 1.0;
        return s;
    }

    private static void assertTrue(boolean c, String m) {
        assert c : m;
    }

    private static void assertEq(int exp, int act, String m) {
        assert act == exp : m + ": expected " + exp + " got " + act;
    }

    private static void assertEq(PlayId exp, PlayId act, String m) {
        assert act == exp : m + ": expected " + exp + " got " + act;
    }

    private static void assertEq(Assignment.FlankSide exp, Assignment.FlankSide act, String m) {
        assert act == exp : m + ": expected " + exp + " got " + act;
    }

    private static void assertNear(double exp, double act, double tol, String m) {
        assert Math.abs(act - exp) <= tol : m + ": expected ~" + exp + " got " + act;
    }
}
