package com.neoalive.tacz_sewv.client.gui;

import net.minecraft.util.Mth;

/**
 * Keeps fixed-pixel GUI layouts inside the GUI-scaled screen. Minecraft's {@code Screen.width}/
 * {@code height} are already GUI-scaled; a 400px panel still overflows when scale 3–4 leaves only
 * ~480×270 (1080p) or less.
 */
public final class GuiFit {

    private static final int MARGIN = 16;

    private GuiFit() {}

    /** Preferred panel width, capped to the scaled screen with a small side margin. */
    public static int panelW(int preferred, int screenW) {
        return Math.min(preferred, Math.max(0, screenW - MARGIN));
    }

    /**
     * Fractional panel width with min/max, still never wider than the scaled screen. A bare
     * {@code clamp(width * frac, min, max)} forces {@code min} even when {@code screenW < min}.
     */
    public static int panelW(int screenW, float frac, int min, int max) {
        int cap = Math.max(0, screenW - MARGIN);
        return Mth.clamp((int) (screenW * frac), Math.min(min, cap), Math.min(max, cap));
    }

    /** Uniform downscale so {@code contentW} fits the scaled screen; never upscales. */
    public static float fitScale(int contentW, int screenW) {
        if (contentW <= 0 || screenW <= 0) return 1.0f;
        return Math.min(1.0f, (float) Math.max(0, screenW - MARGIN) / (float) contentW);
    }

    /** How many list rows fit given vertical budget ({@code reserved} = chrome outside the list). */
    public static int fitRows(int preferred, int rowH, int screenH, int reserved) {
        if (rowH <= 0) return preferred;
        return Mth.clamp((screenH - reserved) / rowH, 1, preferred);
    }
}
