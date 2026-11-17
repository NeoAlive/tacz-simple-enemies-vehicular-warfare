package com.neoalive.tacz_sewv.entity.ai.support;

import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

/**
 * What a player's SEM order does to a standing formation — the one table every order entry point asks
 * ({@code MixinPacketIssueOrder}, {@code QuickSemOrders}), so SEM's own menu, the wheel, the TDT and the world
 * map can never disagree about it.
 *
 * <p>A formation laid around the PLAYER survives exactly the two orders that are about following or
 * relocating it (FOLLOW_COMMANDER, MOVE_TO_POSITION) and is dropped by everything else — the rule that used to
 * be written out inline at each entry point. A formation laid around a UNIT survives every order: the
 * followers' orders go to the leader, the leader runs its own order and stays the leader, and only Cancel
 * ({@link FormationSubjects#release}) ends it. Pure — no world state — so {@code FormationSubjectSelfCheck}
 * pins the whole table headless.
 */
public final class FormationOrderPolicy {

    private FormationOrderPolicy() {}

    /** Where the ordered unit stands in a formation. */
    public enum Role {
        /** In no formation (or a plain SEM one, which this mod does not own). */
        NONE,
        /** On a slot of a PLAYER- or POSITION-anchored formation. */
        PLAYER_MEMBER,
        /** On a slot of a UNIT-anchored formation. */
        FOLLOWER,
        /** The unit a UNIT-anchored formation is laid around. */
        LEADER
    }

    public enum Decision {
        /** Run the order as given; nothing about formations to do. */
        APPLY,
        /** Run the order and keep the formation (the shape travels with it). */
        CARRY,
        /** Run the order and drop the formation. */
        CLEAR,
        /** Do not run the order on this unit: hand it to the formation's leader instead. */
        ROUTE_TO_LEADER
    }

    public static Decision decide(Role role, OrderType order) {
        return switch (role) {
            case NONE -> Decision.APPLY;
            case PLAYER_MEMBER -> order == OrderType.FOLLOW_COMMANDER || order == OrderType.MOVE_TO_POSITION
                    ? Decision.CARRY : Decision.CLEAR;
            case FOLLOWER -> Decision.ROUTE_TO_LEADER;
            case LEADER -> Decision.CARRY;
        };
    }

    /**
     * The fire posture an order sets on a UNIT formation's followers, who keep their formation order and so
     * never see CEASE_FIRE themselves: TRUE to hold fire, FALSE to fire freely, null to leave it as it is.
     */
    public static Boolean followerHoldFire(OrderType order) {
        return switch (order) {
            case CEASE_FIRE -> Boolean.TRUE;
            case FREE_FIRE -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Whether an order the leader takes also turns the formation to face along the leader's new course. */
    public static boolean reheads(OrderType order) {
        return order == OrderType.MOVE_TO_POSITION;
    }

    /**
     * Index of the leader among {@code candidates}: the first commander if any is present, otherwise
     * {@code roll} (any int, folded into range) picks one. -1 for an empty list. The caller decides who counts
     * as a commander — a commander riding a hull stands for that hull's driver.
     */
    public static int electIndex(boolean[] commander, int roll) {
        int n = commander.length;
        if (n == 0) return -1;
        for (int i = 0; i < n; i++) {
            if (commander[i]) return i;
        }
        return Math.floorMod(roll, n);
    }

    /** Minecraft yaw (0 = +Z) from one point to another, snapped to 45°; NaN when they coincide. */
    public static float bearingYaw(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx * dx + dz * dz < 1.0e-4) return Float.NaN;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return snapYaw(yaw);
    }

    /** The wheel's heading rule: nearest 45°, wrapped to (-180, 180]. */
    public static float snapYaw(float yaw) {
        float snapped = Math.round(yaw / 45.0f) * 45.0f;
        snapped = snapped % 360.0f;
        if (snapped <= -180.0f) snapped += 360.0f;
        if (snapped > 180.0f) snapped -= 360.0f;
        return snapped;
    }
}
