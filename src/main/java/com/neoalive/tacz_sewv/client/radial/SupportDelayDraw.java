package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

/**
 * Mortar delay readout for the Quick Wheel Support submenu: one compact card right of the ring.
 *
 * <pre>
 * ▌☄ Delay        40s
 * ▌Mortar only
 * ▔▔▔▔▔▔▔▔▔▔ (fill = delay / max)
 *   Ctrl+Scroll
 * </pre>
 *
 * Placed past the WIDEST label on screen, not at a fixed gap: Support labels ("Anti Ground
 * Missile") run far wider than Formation's, and a fixed offset sat the card on top of them. The
 * delay only applies to Mortar Shelling, so the card fades whenever another leaf is hovered and
 * its accent stripe and tag go grey — it never looks like a live setting of the hovered order.
 */
final class SupportDelayDraw {

    private static final int PAD = 4;
    private static final int STRIPE = 2;
    private static final int LINE = 9;
    private static final int BAR = 2;
    /** Gap between the widest wheel label and the card. */
    private static final int LABEL_CLEARANCE = 10;
    private static final int SCREEN_MARGIN = 4;
    private static final int MAX_DELAY = 120;
    private static final float INACTIVE_ALPHA = 0.4f;
    private static final int MUTED = 0xFF8B98A5;
    private static final int GREY = 0xFF5A6470;

    private SupportDelayDraw() {}

    static void render(GuiGraphics g, Font font, int cx, int cy, int screenW, int outerR,
                       int widestLabel, float menuAlpha, int delaySeconds, boolean mortarHot,
                       int accentRgb, int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f) * (mortarHot ? 1.0f : INACTIVE_ALPHA);
        if (a <= 0.01f) return;

        String title = "\u2604 " + I18n.get("gui.tacz_sewv.support.delay.label");
        String value = delaySeconds <= 0
                ? I18n.get("gui.tacz_sewv.support.delay.off")
                : I18n.get("gui.tacz_sewv.support.delay.seconds", delaySeconds);
        String tag = I18n.get("gui.tacz_sewv.support.delay.mortar_only");

        // Sized for the widest value, so stepping OFF -> 120s never resizes the card under the cursor.
        int valueW = Math.max(font.width(value),
                font.width(I18n.get("gui.tacz_sewv.support.delay.seconds", MAX_DELAY)));
        int innerW = Math.max(font.width(title) + 8 + valueW, font.width(tag));
        int w = STRIPE + PAD * 2 + innerW;
        int h = PAD + LINE + 2 + LINE + PAD;

        int left = Math.min(cx + RadialWheelDraw.labelReach(outerR) + widestLabel + LABEL_CLEARANCE,
                screenW - w - SCREEN_MARGIN);
        int top = cy - h / 2;
        int accent = mortarHot ? 0xFF000000 | accentRgb : GREY;

        g.fill(left, top, left + w, top + h, scale(panelArgb, a));
        g.fill(left, top, left + STRIPE, top + h, scale(accent, a));

        int textX = left + STRIPE + PAD;
        int right = left + w - PAD;
        g.drawString(font, title, textX, top + PAD, scale(MUTED, a), false);
        g.drawString(font, value, right - font.width(value), top + PAD,
                scale(delaySeconds > 0 ? labelArgb : MUTED, a), false);
        g.drawString(font, tag, textX, top + PAD + LINE + 2, scale(accent, a), false);

        // Delay as a fraction of the maximum, along the bottom edge.
        int barLeft = left + STRIPE;
        int barW = w - STRIPE;
        g.fill(barLeft, top + h - BAR, barLeft + barW, top + h, scale(0x40FFFFFF, a));
        int fill = Math.round(barW * Mth.clamp(delaySeconds / (float) MAX_DELAY, 0.0f, 1.0f));
        if (fill > 0) {
            g.fill(barLeft, top + h - BAR, barLeft + fill, top + h, scale(accent, a));
        }

        if (mortarHot) {
            g.drawString(font, I18n.get("gui.tacz_sewv.support.delay.hint"),
                    textX, top + h + 3, scale(MUTED, a), false);
        }
    }

    private static int scale(int argb, float a) {
        return FormationPreviewDraw.scaleAlpha(argb, a);
    }
}
