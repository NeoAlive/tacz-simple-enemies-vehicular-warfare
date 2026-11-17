package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Every input that does something on the current page of the wheel, as keycap rows in the bottom
 * right corner (clear of the hotbar and the chat). Built fresh each frame by the screen, so it
 * always matches what the hovered entry and the open submenu actually accept.
 */
final class WheelLegendDraw {

    record Row(String key, String action) {}

    private static final int KEY = 0xFFFFFFFF;
    private static final int KEYCAP = 0x40FFFFFF;
    private static final int ACTION = 0xFFB4BEC8;
    private static final int PAD = 5;
    private static final int CAP_PAD = 2;
    private static final int GAP = 5;
    private static final int MARGIN = 8;
    /** Kept above the hotbar's height so the bottom row never sits on it at narrow widths. */
    private static final int BOTTOM_CLEARANCE = 26;

    private WheelLegendDraw() {}

    static void render(GuiGraphics g, Font font, int screenW, int screenH, float alpha, List<Row> rows,
                       int panelArgb) {
        float a = Mth.clamp(alpha, 0.0f, 1.0f);
        if (a < 0.05f || rows.isEmpty()) return;
        int keyW = 0;
        int actW = 0;
        for (Row r : rows) {
            keyW = Math.max(keyW, font.width(r.key()));
            actW = Math.max(actW, font.width(r.action()));
        }
        int rowH = font.lineHeight + 4;
        int w = PAD * 2 + CAP_PAD * 2 + keyW + GAP + actW;
        int h = PAD * 2 + rows.size() * rowH - 4;
        int right = screenW - MARGIN;
        int bottom = screenH - BOTTOM_CLEARANCE;
        int left = right - w;
        int top = bottom - h;
        g.fill(left, top, right, bottom, FormationPreviewDraw.scaleAlpha(panelArgb, a));

        int y = top + PAD;
        int capRight = left + PAD + CAP_PAD * 2 + keyW;
        for (Row r : rows) {
            int kw = font.width(r.key());
            int capLeft = capRight - kw - CAP_PAD * 2;
            g.fill(capLeft, y - CAP_PAD, capRight, y + font.lineHeight + 1, FormationPreviewDraw.scaleAlpha(KEYCAP, a));
            g.drawString(font, r.key(), capLeft + CAP_PAD, y, FormationPreviewDraw.scaleAlpha(KEY, a), true);
            g.drawString(font, r.action(), capRight + GAP, y, FormationPreviewDraw.scaleAlpha(ACTION, a), true);
            y += rowH;
        }
    }
}
