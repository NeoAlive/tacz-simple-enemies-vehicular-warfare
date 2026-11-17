package com.neoalive.tacz_sewv.client.xaero;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Xaero's own look, so everything we draw on the World Map sits beside its menus without a seam.
 * The values are xaerolib's hard-coded {@code DropDownWidget} and {@code Tooltip} constants: there is
 * no theme setting to follow. Names no Xaero type, so any client class may use it.
 */
public final class XaeroTheme {

    public static final int BG = 0xC8000000;
    public static final int TRIM = 0xFFA0A0A0;
    public static final int TRIM_OPEN = 0xFFFFFFFF;
    public static final int ROW_HOVER = 0xFF323232;
    public static final int SEPARATOR = 0xFF323232;
    public static final int GOLD = 0xFFFFD700;
    public static final int GOLD_SOFT = 0x50FFD700;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int DIM = 0xFFA0A0A0;
    public static final int DISABLED = 0xFF555555;
    public static final int SELECT = 0x40FFFFFF;
    public static final int ON_GOLD = 0xFF000000;
    public static final int LINE_H = 11;

    private XaeroTheme() {}

    public static void panel(GuiGraphics g, int x, int y, int w, int h, boolean open) {
        g.fill(x, y, x + w, y + h, BG);
        outline(g, x, y, w, h, open ? TRIM_OPEN : TRIM);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    /** A list row: hover plate, or the selection tint with a white left bar. */
    public static void row(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean selected) {
        if (selected) {
            g.fill(x, y, x + w, y + h, SELECT);
            g.fill(x, y, x + 1, y + h, TRIM_OPEN);
        } else if (hover) {
            g.fill(x, y, x + w, y + h, ROW_HOVER);
        }
    }

    public static void separator(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, SEPARATOR);
    }

    /** Gold title band with black centred text, as Xaero heads its right-click menu. */
    public static void titleRow(GuiGraphics g, Font font, int x, int y, int w, int h, String text) {
        g.fill(x, y, x + w, y + h, GOLD);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2 + 1, ON_GOLD, false);
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.drawString(font, s, x, y, colour, true);
    }

    public static void centred(GuiGraphics g, Font font, String s, int cx, int y, int colour) {
        g.drawString(font, s, cx - font.width(s) / 2, y, colour, true);
    }

    /** A small labelled button: black plate, grey trim, white trim and hover plate when hot. */
    public static void chip(GuiGraphics g, Font font, int x, int y, int w, int h, String label, boolean hover,
                            boolean on) {
        g.fill(x, y, x + w, y + h, on ? GOLD_SOFT : hover ? ROW_HOVER : BG);
        outline(g, x, y, w, h, hover || on ? TRIM_OPEN : TRIM);
        centred(g, font, label, x + w / 2, y + (h - 8) / 2 + 1, on ? GOLD : TEXT);
    }

    /** A keycap glyph for legends; answers its width. */
    public static int keycap(GuiGraphics g, Font font, int x, int y, String key) {
        int w = Math.max(11, font.width(key) + 6);
        g.fill(x, y, x + w, y + 11, ROW_HOVER);
        outline(g, x, y, w, 11, TRIM);
        g.drawString(font, key, x + (w - font.width(key)) / 2, y + 2, TEXT, true);
        return w;
    }

    /** Xaero-style tooltip: black box, white text, kept on screen. Drawn above everything. */
    public static void tooltip(GuiGraphics g, Font font, java.util.List<String> lines, int mx, int my, int sw, int sh) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 8;
        int h = lines.size() * 10 + 6;
        int x = mx + 12;
        int y = my - 10 - h;
        if (x + w > sw - 2) x = mx - 12 - w;
        if (y < 2) y = my + 12;
        y = Math.min(y, sh - h - 2);
        g.pose().pushPose();
        g.pose().translate(0, 0, 420);
        g.fill(x, y, x + w, y + h, BG);
        outline(g, x, y, w, h, TRIM);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 4, y + 4 + i * 10, i == 0 ? TEXT : DIM, true);
        }
        g.pose().popPose();
    }

    public static String fit(Font font, String s, int w) {
        if (font.width(s) <= w) return s;
        String dots = "...";
        int keep = s.length();
        while (keep > 0 && font.width(s.substring(0, keep) + dots) > w) keep--;
        return s.substring(0, keep) + dots;
    }
}
