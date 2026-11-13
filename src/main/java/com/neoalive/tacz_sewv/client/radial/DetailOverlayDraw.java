package com.neoalive.tacz_sewv.client.radial;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * Hold Ctrl over the wheel: the screen goes dark and the hovered order is explained in plain words.
 * The fade is eased in and out, and the text rises a few pixels as it comes in so it reads as one
 * motion rather than a cut.
 */
final class DetailOverlayDraw {

    /** Full-strength background. Not quite opaque, so the wheel's position is still felt behind it. */
    private static final int BACKDROP = 0xE6000000;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int BODY = 0xFFC9D1D9;
    private static final int RULE = 0x60FFFFFF;
    private static final float TITLE_SCALE = 2.0f;
    private static final int MAX_BODY_W = 300;
    private static final int RISE = 6;

    private DetailOverlayDraw() {}

    /** Smoothstep of a linear 0..1 progress. */
    static float ease(float t) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    static void render(GuiGraphics g, Font font, int screenW, int screenH, float eased, OrderInfo.Text text) {
        if (eased <= 0.01f) return;
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, 400.0f);
        g.fill(0, 0, screenW, screenH, FormationPreviewDraw.scaleAlpha(BACKDROP, eased));

        // Text fades in a beat behind the backdrop so it never sits on a half-dark screen.
        float ta = ease((eased - 0.35f) / 0.65f);
        if (ta < 0.05f) {
            g.pose().popPose();
            return;
        }
        int bodyW = Math.min(MAX_BODY_W, screenW - 48);
        List<FormattedCharSequence> lines = font.split(Component.literal(text.body()), bodyW);
        int lineH = font.lineHeight + 2;
        int titleH = Math.round(font.lineHeight * TITLE_SCALE);
        int blockH = titleH + 10 + lines.size() * lineH;
        int top = (screenH - blockH) / 2 + Math.round((1.0f - ta) * RISE);
        int cx = screenW / 2;

        g.pose().pushPose();
        g.pose().translate(cx, top, 0.0f);
        g.pose().scale(TITLE_SCALE, TITLE_SCALE, 1.0f);
        g.drawCenteredString(font, text.title(), 0, 0, FormationPreviewDraw.scaleAlpha(TITLE, ta));
        g.pose().popPose();

        int ruleY = top + titleH + 4;
        int ruleHalf = Math.min(bodyW / 2, Math.max(24, font.width(text.title())));
        g.fill(cx - ruleHalf, ruleY, cx + ruleHalf, ruleY + 1, FormationPreviewDraw.scaleAlpha(RULE, ta));

        int y = ruleY + 6;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, cx - font.width(line) / 2, y, FormationPreviewDraw.scaleAlpha(BODY, ta), false);
            y += lineH;
        }
        g.pose().popPose();
    }
}
