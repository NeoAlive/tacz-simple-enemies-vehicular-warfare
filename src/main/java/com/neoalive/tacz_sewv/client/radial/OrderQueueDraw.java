package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

/** Queue list box (left of the wheel, mirroring the formation preview) + the ON/OFF flash. */
final class OrderQueueDraw {

    private static final int W = FormationPreviewDraw.PANEL_W;
    private static final int HEADER_H = FormationPreviewDraw.FILTER_H;
    private static final int LIST_H = FormationPreviewDraw.PANEL_H;
    private static final int PAD = 6;
    private static final int CURRENT = 0xFFFFD84A;
    private static final int MUTED = 0xFF8B98A5;

    private static final long FADE_MS = 250;

    private OrderQueueDraw() {}

    static void renderBox(GuiGraphics g, Font font, int cx, int cy, int outerR, float menuAlpha,
                          List<String> labels, int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (a <= 0.01f) return;
        int left = cx - outerR - FormationPreviewDraw.RIGHT_OF_RING_GAP - W;
        int top = cy - (HEADER_H + LIST_H) / 2;
        int fill = FormationPreviewDraw.scaleAlpha(panelArgb, a);
        int muted = FormationPreviewDraw.scaleAlpha(MUTED, a);

        g.fill(left, top, left + W, top + HEADER_H, fill);
        g.drawCenteredString(font, I18n.get("gui.tacz_sewv.queue.title"),
                left + W / 2, top + (HEADER_H - 8) / 2, FormationPreviewDraw.scaleAlpha(labelArgb, a));

        int listTop = top + HEADER_H;
        g.fill(left, listTop, left + W, listTop + LIST_H, fill);
        int rowH = font.lineHeight + 2;
        int rows = (LIST_H - PAD * 2) / rowH;
        if (labels.isEmpty()) {
            g.drawCenteredString(font, I18n.get("gui.tacz_sewv.queue.empty"),
                    left + W / 2, listTop + PAD, muted);
        }
        for (int i = 0; i < labels.size() && i < rows; i++) {
            boolean last = i == rows - 1 && labels.size() > rows;
            String text = last ? "  +" + (labels.size() - i) + "..."
                    : (i == 0 ? "> " : "  ") + labels.get(i);
            int color = i == 0 ? CURRENT : labelArgb;
            g.drawString(font, font.plainSubstrByWidth(text, W - PAD * 2),
                    left + PAD, listTop + PAD + i * rowH, FormationPreviewDraw.scaleAlpha(color, a));
        }

        int hintY = listTop + LIST_H + 4;
        for (String line : I18n.get("gui.tacz_sewv.queue.hint").split("\n")) {
            g.drawCenteredString(font, line, left + W / 2, hintY, muted);
            hintY += font.lineHeight + 1;
        }
    }

    /**
     * "Queue Mode: ON/OFF", always shown above the top wedge label ({@code labelReach} out from the
     * centre); fades in with the wheel and again on each toggle.
     */
    static void renderModeText(GuiGraphics g, Font font, int cx, int cy, int labelReach,
                               float menuAlpha, boolean on, long toggledAtMs) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f)
                * Mth.clamp((System.currentTimeMillis() - toggledAtMs) / (float) FADE_MS, 0.0f, 1.0f);
        // Text with an alpha under ~4 is drawn opaque by vanilla, so stop before that.
        if (a < 0.05f) return;
        String text = I18n.get(on ? "gui.tacz_sewv.queue.on" : "gui.tacz_sewv.queue.off");
        int labelTop = cy - labelReach - 4;
        g.drawCenteredString(font, text, cx, labelTop - font.lineHeight - 6,
                FormationPreviewDraw.scaleAlpha(on ? CURRENT : MUTED, a));
    }
}
