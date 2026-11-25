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

    // ---- live preview ----------------------------------------------------------------------------------------

    private static volatile int[] previewAxes = new int[0];
    private static volatile byte[] previewActions = new byte[0];
    private static volatile byte[] previewConfidence = new byte[0];

    public static void acceptPreview(int[] forAxes, byte[] actions, byte[] confidence) {
        previewAxes = forAxes.clone();
        previewActions = actions.clone();
        previewConfidence = confidence.clone();
    }

    /** True when the last answer was for exactly these axes, so the editor never shows a stale verdict. */
    public static boolean previewMatches(int[] axesNow) {
        return java.util.Arrays.equals(previewAxes, axesNow);
    }

    public static byte[] previewActions() {
        return previewActions;
    }

    public static byte[] previewConfidence() {
        return previewConfidence;
    }
}
