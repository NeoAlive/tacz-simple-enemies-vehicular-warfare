package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.invasion.InvasionHud;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Client store for the invasion match HUD. Entrance animation state lives here;
 * drawing is {@link InvasionHudOverlay}.
 */
public final class InvasionHudClient {

    private static final float SETTLE_EPS = 0.5f;

    @Nullable
    private static InvasionHud.Snapshot snapshot;
    @Nullable
    private static float[] currentX;
    @Nullable
    private static float[] targetX;
    private static boolean entranceDone;
    private static long lastFrameNanos;

    private InvasionHudClient() {}

    public static void accept(InvasionHud.Snapshot incoming) {
        boolean first = snapshot == null;
        snapshot = incoming;
        int n = incoming.slots().size();
        if (first || currentX == null || currentX.length != n) {
            currentX = new float[n];
            targetX = new float[n];
            entranceDone = false;
            lastFrameNanos = 0L;
            // Start stacked at centre; overlay fills targetX then lerps out.
            Arrays.fill(currentX, Float.NaN);
        }
    }

    public static void clear() {
        snapshot = null;
        currentX = null;
        targetX = null;
        entranceDone = false;
        lastFrameNanos = 0L;
    }

    @Nullable
    public static InvasionHud.Snapshot snapshot() {
        return snapshot;
    }

    @Nullable
    public static float[] currentX() {
        return currentX;
    }

    @Nullable
    public static float[] targetX() {
        return targetX;
    }

    public static boolean entranceDone() {
        return entranceDone;
    }

    public static void markEntranceDone() {
        entranceDone = true;
    }

    /** Seconds since last overlay frame (clamped). First call returns a small default. */
    public static float deltaSeconds() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1f / 60f;
        }
        float dt = (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        return Math.min(0.1f, Math.max(0.001f, dt));
    }

    public static boolean settled(float[] cur, float[] tgt) {
        for (int i = 0; i < cur.length; i++) {
            if (Math.abs(cur[i] - tgt[i]) > SETTLE_EPS) return false;
        }
        return true;
    }
}
