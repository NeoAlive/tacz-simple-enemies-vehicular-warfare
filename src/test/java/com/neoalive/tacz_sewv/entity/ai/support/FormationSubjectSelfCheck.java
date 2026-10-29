package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import com.neoalive.tacz_sewv.entity.ai.support.FormationOrderPolicy.Decision;
import com.neoalive.tacz_sewv.entity.ai.support.FormationOrderPolicy.Role;

/**
 * Headless check of formation subjects: the order policy table (a player formation keeps today's
 * carry/clear rule, a UNIT formation survives every order), the leader election rule, the re-head
 * bearing, and the anchor-mode wire id. Run via {@code ./gradlew selfCheckFormationSubject}.
 */
public final class FormationSubjectSelfCheck {

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        playerFormationKeepsTodaysRule();
        unitFormationSurvivesEveryOrder();
        firePosture();
        election();
        bearing();
        wireId();
        System.out.println("FormationSubjectSelfCheck OK");
    }

    private static void playerFormationKeepsTodaysRule() {
        for (OrderType order : OrderType.values()) {
            Decision d = FormationOrderPolicy.decide(Role.PLAYER_MEMBER, order);
            boolean carries = order == OrderType.FOLLOW_COMMANDER || order == OrderType.MOVE_TO_POSITION;
            assert d == (carries ? Decision.CARRY : Decision.CLEAR) : "player formation under " + order + ": " + d;
            assert FormationOrderPolicy.decide(Role.NONE, order) == Decision.APPLY : "no formation under " + order;
        }
    }

    private static void unitFormationSurvivesEveryOrder() {
        for (OrderType order : OrderType.values()) {
            assert FormationOrderPolicy.decide(Role.FOLLOWER, order) == Decision.ROUTE_TO_LEADER
                    : "a follower's " + order + " must go to its leader";
            assert FormationOrderPolicy.decide(Role.LEADER, order) == Decision.CARRY
                    : "a leader keeps leading under " + order;
        }
        assert FormationOrderPolicy.reheads(OrderType.MOVE_TO_POSITION);
        assert !FormationOrderPolicy.reheads(OrderType.HOLD_POSITION);
    }

    private static void firePosture() {
        assert FormationOrderPolicy.followerHoldFire(OrderType.CEASE_FIRE) == Boolean.TRUE;
        assert FormationOrderPolicy.followerHoldFire(OrderType.FREE_FIRE) == Boolean.FALSE;
        assert FormationOrderPolicy.followerHoldFire(OrderType.MOVE_TO_POSITION) == null;
    }

    private static void election() {
        assert FormationOrderPolicy.electIndex(new boolean[0], 7) == -1;
        assert FormationOrderPolicy.electIndex(new boolean[] {false, false, true, true}, 1) == 2
                : "the first commander leads, whatever the roll";
        boolean[] none = {false, false, false};
        for (int roll = -10; roll <= 10; roll++) {
            int i = FormationOrderPolicy.electIndex(none, roll);
            assert i >= 0 && i < 3 : "roll " + roll + " out of range: " + i;
            assert i == FormationOrderPolicy.electIndex(none, roll) : "election must be a function of the roll";
        }
    }

    private static void bearing() {
        // Minecraft yaw: 0 faces +Z (south), 90 faces -X (west), 180 north, -90 east.
        assert FormationOrderPolicy.bearingYaw(0, 0, 0, 10) == 0.0f;
        assert FormationOrderPolicy.bearingYaw(0, 0, -10, 0) == 90.0f;
        assert Math.abs(FormationOrderPolicy.bearingYaw(0, 0, 0, -10)) == 180.0f;
        assert FormationOrderPolicy.bearingYaw(0, 0, 10, 0) == -90.0f;
        assert FormationOrderPolicy.bearingYaw(0, 0, -10, 9) == 45.0f : "snaps to the nearest 45°";
        assert Float.isNaN(FormationOrderPolicy.bearingYaw(3, 3, 3, 3)) : "no bearing to where it stands";
        float yaw = FormationOrderPolicy.bearingYaw(0, 0, 7, -3);
        assert yaw % 45.0f == 0.0f && yaw > -180.0f && yaw <= 180.0f : "bearing " + yaw;
    }

    private static void wireId() {
        assert FormationAnchorMode.byId(0) == FormationAnchorMode.PLAYER;
        assert FormationAnchorMode.byId(1) == FormationAnchorMode.POSITION;
        assert FormationAnchorMode.byId(2) == FormationAnchorMode.UNIT : "UNIT is appended, never reordered";
        assert FormationAnchorMode.byId(3) == FormationAnchorMode.PLAYER;
    }
}
