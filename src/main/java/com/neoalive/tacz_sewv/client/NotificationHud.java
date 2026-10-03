package com.neoalive.tacz_sewv.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.notify.NotificationKind;

/**
 * Client queue + animation for the top-center flag-card stack. Overlay draws; this owns state.
 * Up to {@code notificationMaxVisible} tickets show at once; further pushes sit in a backlog.
 * Newest sits in front (flush top); older tickets peek a few px below like Windows tabs.
 */
public final class NotificationHud {

    /** Pre-scale ticket width (then × {@link #DISPLAY_SCALE}). */
    public static final int TICKET_W = 280;
    /** Pre-scale ticket height: rail + title + two body lines. */
    public static final int TICKET_H = 40;
    /** Kind rail wide enough for the Unicode icon. */
    public static final int RAIL_W = 16;
    /** How far each back ticket peeks below the one in front. */
    public static final int PEEK = 5;
    public static final int TITLE_PAD_X = 6;
    public static final int BODY_PAD_X = 6;
    public static final int BODY_PAD_Y = 2;
    public static final int MAX_BODY_LINES = 2;

    /** On-screen size relative to layout pixels — half size for large-GUI readability. */
    public static final float DISPLAY_SCALE = 0.5f;

    public static final int BODY_BG = 0xCC0B0F14;
    public static final int SHADOW = 0x66000000;
    public static final int TITLE_COLOR = 0xFFFFFFF0;
    public static final int BODY_COLOR = 0xFFD1D5DB;
    public static final int ICON_COLOR = 0xFFFFFFFF;

    private static final int MAX_BACKLOG = 16;
    private static final long ANIM_MS = 250L;
    /** Title/body fade-in after the card finishes enter. */
    static final long TEXT_FADE_MS = 180L;
    private static final long DEFAULT_SCREEN_MS = 5_000L;
    private static final int DEFAULT_MAX_VISIBLE = 3;

    private static volatile long screenTimeMs = DEFAULT_SCREEN_MS;
    private static volatile int maxVisible = DEFAULT_MAX_VISIBLE;

    private static final ArrayDeque<Item> backlog = new ArrayDeque<>();
    private static final List<Slot> slots = new ArrayList<>();
    private static long lastWallMs;

    private NotificationHud() {}

    public static void refreshScreenTimeCache() {
        int seconds = 5;
        int visible = DEFAULT_MAX_VISIBLE;
        try {
            seconds = Mth.clamp(ClientConfig.NOTIFICATION_SCREEN_SECONDS.get(), 1, 30);
            visible = Mth.clamp(ClientConfig.NOTIFICATION_MAX_VISIBLE.get(), 1, 8);
        } catch (IllegalStateException ignored) {
            // Spec not baked yet.
        }
        screenTimeMs = seconds * 1000L;
        maxVisible = visible;
        while (slots.size() > maxVisible) {
            Slot removed = slots.remove(slots.size() - 1);
            if (removed.item != null && removed.phase != Phase.EXIT) {
                backlog.addFirst(removed.item);
                while (backlog.size() > MAX_BACKLOG) backlog.removeLast();
            }
        }
        promote();
    }

    public static void push(Component title, Component body, NotificationKind kind) {
        if (!ClientConfig.flag(ClientConfig.NOTIFICATIONS_ENABLED)) return;
        if (title == null) title = Component.empty();
        if (body == null) body = Component.empty();
        if (kind == null) kind = NotificationKind.GENERIC;
        Item item = new Item(title, body, kind);
        if (activeShowingOrEntering() < maxVisible) {
            slots.add(Slot.enter(item));
        } else if (backlog.size() < MAX_BACKLOG) {
            backlog.addLast(item);
        }
    }

    /**
     * Skip remaining hold on the frontmost (newest) showing ticket (or finish its enter into showing).
     *
     * @return true if a live ticket's delay was skipped
     */
    public static boolean dismiss() {
        for (int i = slots.size() - 1; i >= 0; i--) {
            Slot slot = slots.get(i);
            if (slot.phase == Phase.ENTER) {
                slot.animElapsedMs = ANIM_MS;
                slot.phase = Phase.SHOWING;
                slot.beginFrontTimer();
                slot.frontElapsedMs = slot.frontDurationMs;
                return true;
            }
            if (slot.phase == Phase.SHOWING) {
                slot.frontElapsedMs = slot.frontDurationMs;
                return true;
            }
        }
        return false;
    }

    public static boolean isActive() {
        return !slots.isEmpty();
    }

    static void tick(boolean paused, long nowMs) {
        if (lastWallMs == 0L) lastWallMs = nowMs;
        long dt = nowMs - lastWallMs;
        lastWallMs = nowMs;
        if (paused || dt < 0L) dt = 0L;
        if (dt > 250L) dt = 250L;

        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            slot.tick(dt);
            if (slot.phase == Phase.IDLE) {
                slots.remove(i);
                i--;
            }
        }
        promote();
    }

    static boolean visible() {
        return !slots.isEmpty();
    }

    static List<Slot> slots() {
        return slots;
    }

    /**
     * Rest Y for stack index. Newest (last index) is flush at 0; each older ticket peeks
     * {@link #PEEK} px further down.
     */
    static int restY(int index, int stackSize) {
        int fromFront = Math.max(0, stackSize - 1 - index);
        return fromFront * PEEK;
    }

    /** Slots that still occupy a stack position (enter / show / exit). */
    private static int activeShowingOrEntering() {
        int n = 0;
        for (Slot slot : slots) {
            if (slot.phase != Phase.IDLE) n++;
        }
        return n;
    }

    private static void promote() {
        // Exiting tickets still occupy a row until IDLE — do not overfill the stack.
        while (slots.size() < maxVisible && !backlog.isEmpty()) {
            slots.add(Slot.enter(backlog.pollFirst()));
        }
    }

    public static final class Item {
        public final Component title;
        public final Component body;
        public final NotificationKind kind;

        Item(Component title, Component body, NotificationKind kind) {
            this.title = title;
            this.body = body;
            this.kind = kind;
        }
    }

    static final class Slot {
        @Nullable Item item;
        Phase phase = Phase.IDLE;
        long animElapsedMs;
        long frontElapsedMs;
        long frontDurationMs = DEFAULT_SCREEN_MS;

        static Slot enter(Item item) {
            Slot slot = new Slot();
            slot.item = item;
            slot.phase = Phase.ENTER;
            slot.animElapsedMs = 0L;
            return slot;
        }

        void beginFrontTimer() {
            this.frontElapsedMs = 0L;
            this.frontDurationMs = screenTimeMs;
        }

        void tick(long dt) {
            switch (this.phase) {
                case IDLE -> {}
                case ENTER -> {
                    this.animElapsedMs += dt;
                    if (this.animElapsedMs >= ANIM_MS) {
                        this.animElapsedMs = ANIM_MS;
                        this.phase = Phase.SHOWING;
                        beginFrontTimer();
                    }
                }
                case SHOWING -> {
                    this.frontElapsedMs += dt;
                    if (this.frontElapsedMs >= this.frontDurationMs) {
                        this.phase = Phase.EXIT;
                        this.animElapsedMs = 0L;
                    }
                }
                case EXIT -> {
                    this.animElapsedMs += dt;
                    if (this.animElapsedMs >= ANIM_MS) {
                        this.phase = Phase.IDLE;
                        this.item = null;
                        this.animElapsedMs = 0L;
                    }
                }
            }
        }

        /** Layout Y for this slot at stack index {@code index}. */
        int drawY(int index, int stackSize) {
            float t = ANIM_MS <= 0L ? 1f : Mth.clamp(this.animElapsedMs / (float) ANIM_MS, 0f, 1f);
            int rest = restY(index, stackSize);
            int hidden = rest - TICKET_H;
            return switch (this.phase) {
                case IDLE -> hidden;
                case ENTER -> Math.round(Mth.lerp(easeOutCubic(t), hidden, rest));
                case SHOWING -> rest;
                case EXIT -> Math.round(Mth.lerp(easeInCubic(t), rest, hidden));
            };
        }

        /** 0 = full timer, 1 = empty. */
        float barT() {
            return switch (this.phase) {
                case IDLE, ENTER -> 0f;
                case SHOWING -> this.frontDurationMs <= 0L ? 1f
                        : Mth.clamp(this.frontElapsedMs / (float) this.frontDurationMs, 0f, 1f);
                case EXIT -> 1f;
            };
        }

        /**
         * Title/body alpha: 0 during enter, ease-in after landing on SHOWING, full during exit.
         */
        float textAlpha() {
            return switch (this.phase) {
                case IDLE, ENTER -> 0f;
                case SHOWING -> {
                    if (TEXT_FADE_MS <= 0L) yield 1f;
                    float t = Mth.clamp(this.frontElapsedMs / (float) TEXT_FADE_MS, 0f, 1f);
                    yield easeInCubic(t);
                }
                case EXIT -> 1f;
            };
        }
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeInCubic(float t) {
        return t * t * t;
    }

    private enum Phase { IDLE, ENTER, SHOWING, EXIT }
}
