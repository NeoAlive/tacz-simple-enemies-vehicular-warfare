package com.neoalive.tacz_sewv.client;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Client queue + animation for the top-center HUD banner. Overlay draws; this owns state.
 * Screen time is a cached millisecond value, not a per-frame config read.
 */
public final class NotificationHud {

    static final int TEX_W = 320;
    static final int TEX_H = 82;
    /** Flush with the top edge of the screen — no margin. */
    static final int REST_Y = 0;
    static final int BAR_X = 7;
    static final int BAR_Y = 54;
    static final int BAR_H = 3;
    static final int BAR_W_FULL = 306;
    static final int BAR_W_EMPTY = 1;
    static final int TEXT_X = 71;
    static final int TEXT_Y = 20;
    static final int TEXT_MAX_W = TEX_W - TEXT_X - 8;
    /** Matches the cyan on {@code notification_*.png} (border / warning icon). */
    static final int CYAN = 0x4FD1C5;
    static final int BAR_COLOR = 0xFF4FD1C5;
    static final int BODY_COLOR = 0xFFFFFF;
    /** Halfway between 1× (body) and the previous 2× title. */
    static final float TITLE_SCALE = 1.3f;

    private static final int MAX_QUEUE = 16;
    private static final long ANIM_MS = 250L;
    private static final long DEFAULT_SCREEN_MS = 5_000L;

    private static final ResourceLocation TEX_ONE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/notification/notification_one.png");
    private static final ResourceLocation TEX_TWO =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/notification/notification_two.png");
    private static final ResourceLocation TEX_THREE =
            new ResourceLocation(TaczSewv.MODID, "textures/gui/notification/notification_three.png");

    private static volatile long screenTimeMs = DEFAULT_SCREEN_MS;

    private static final ArrayDeque<Item> queue = new ArrayDeque<>();
    private static Phase phase = Phase.IDLE;
    private static long animElapsedMs;
    private static long frontElapsedMs;
    private static long frontDurationMs = DEFAULT_SCREEN_MS;
    private static long lastWallMs;
    @Nullable private static Item exiting;

    private NotificationHud() {}

    public static void refreshScreenTimeCache() {
        int seconds = 5;
        try {
            seconds = Mth.clamp(ClientConfig.NOTIFICATION_SCREEN_SECONDS.get(), 1, 30);
        } catch (IllegalStateException ignored) {
            // Spec not baked yet (very early load); keep previous / default.
        }
        screenTimeMs = seconds * 1000L;
    }

    public static void push(Component title, Component body) {
        if (title == null) title = Component.empty();
        if (body == null) body = Component.empty();
        if (queue.size() >= MAX_QUEUE) return;
        queue.addLast(new Item(title, body));
        if (phase == Phase.IDLE) {
            phase = Phase.ENTER;
            animElapsedMs = 0L;
        } else if (phase == Phase.EXIT) {
            phase = Phase.ENTER;
            animElapsedMs = Math.max(0L, ANIM_MS - animElapsedMs);
            exiting = null;
        }
    }

    static void tick(boolean paused, long nowMs) {
        if (lastWallMs == 0L) lastWallMs = nowMs;
        long dt = nowMs - lastWallMs;
        lastWallMs = nowMs;
        if (paused || dt < 0L) dt = 0L;
        if (dt > 250L) dt = 250L;

        switch (phase) {
            case IDLE -> {}
            case ENTER -> {
                animElapsedMs += dt;
                if (animElapsedMs >= ANIM_MS) {
                    animElapsedMs = ANIM_MS;
                    phase = Phase.SHOWING;
                    beginFrontTimer();
                }
            }
            case SHOWING -> {
                frontElapsedMs += dt;
                if (frontElapsedMs >= frontDurationMs) {
                    Item done = queue.pollFirst();
                    if (queue.isEmpty()) {
                        exiting = done;
                        phase = Phase.EXIT;
                        animElapsedMs = 0L;
                    } else {
                        beginFrontTimer();
                    }
                }
            }
            case EXIT -> {
                animElapsedMs += dt;
                if (animElapsedMs >= ANIM_MS) {
                    phase = Phase.IDLE;
                    animElapsedMs = 0L;
                    exiting = null;
                }
            }
        }
    }

    static boolean visible() {
        return phase != Phase.IDLE && (phase == Phase.EXIT || !queue.isEmpty());
    }

    static int drawY() {
        float t = ANIM_MS <= 0L ? 1f : Mth.clamp(animElapsedMs / (float) ANIM_MS, 0f, 1f);
        int hiddenY = -TEX_H;
        return switch (phase) {
            case IDLE -> hiddenY;
            case ENTER -> Math.round(Mth.lerp(easeOutCubic(t), hiddenY, REST_Y));
            case SHOWING -> REST_Y;
            case EXIT -> Math.round(Mth.lerp(easeInCubic(t), REST_Y, hiddenY));
        };
    }

    static ResourceLocation texture() {
        int n = Math.max(queue.size(), phase == Phase.EXIT ? 1 : 0);
        if (n >= 3) return TEX_THREE;
        if (n == 2) return TEX_TWO;
        return TEX_ONE;
    }

    @Nullable
    static Item front() {
        Item head = queue.peekFirst();
        return head != null ? head : exiting;
    }

    /** 0 = full bar, 1 = empty (1px). Frozen during enter; EXIT holds empty. */
    static float barT() {
        return switch (phase) {
            case IDLE, ENTER -> 0f;
            case SHOWING -> frontDurationMs <= 0L ? 1f
                    : Mth.clamp(frontElapsedMs / (float) frontDurationMs, 0f, 1f);
            case EXIT -> 1f;
        };
    }

    private static void beginFrontTimer() {
        frontElapsedMs = 0L;
        frontDurationMs = screenTimeMs;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeInCubic(float t) {
        return t * t * t;
    }

    record Item(Component title, Component body) {}

    private enum Phase { IDLE, ENTER, SHOWING, EXIT }
}
