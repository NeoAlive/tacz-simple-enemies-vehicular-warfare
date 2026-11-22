package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData;

/** Client copy of the player's doctrine and point budget, from {@code PacketDoctrineSync}. Display only. */
public final class DoctrineClient {

    private static volatile boolean has;
    private static volatile int[] axes = new int[Doctrine.Axis.VALUES.length];
    private static volatile int budget = PlayerDoctrineData.BASE_BUDGET;

    private DoctrineClient() {}

    public static void accept(boolean hasDoctrine, int[] savedAxes, int pointBudget) {
        axes = savedAxes.clone();
        budget = Math.max(1, pointBudget);
        has = hasDoctrine;
    }

    public static void clear() {
        has = false;
        axes = new int[Doctrine.Axis.VALUES.length];
        budget = PlayerDoctrineData.BASE_BUDGET;
    }

    /** True once the server has said this player has a saved doctrine. */
    public static boolean has() {
        return has;
    }

    /** A copy of the saved axes, all zero when there is none. */
    public static int[] axes() {
        return axes.clone();
    }

    public static int budget() {
        return budget;
    }
}
