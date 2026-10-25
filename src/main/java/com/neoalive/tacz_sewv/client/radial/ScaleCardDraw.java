package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

/**
 * A Ctrl+scroll value readout for the Quick Wheel: one compact card right of the ring. Shared by
 * every leaf that carries a value (mortar delay, patrol / sweep radius, cruise altitude).
 *
 * <pre>
 * ▌☄ Delay        40s
 * ▌Mortar only
 * ▔▔▔▔▔▔▔▔▔▔ (fill = fraction)
 *   Ctrl+Scroll
 * </pre>
 *
 * Placed past the labels' measured right edge, not at a fixed gap: labels like "Anti Ground Missile"
 * run far wider than Formation's, and a fixed offset sat the card on top of them. A value only
 * applies to its own leaf, so the card fades whenever another leaf is hovered and its accent stripe
 * and tag go grey — it never looks like a live setting of the hovered order.
 */
final class ScaleCardDraw {

    /**
     * @param widestValue the widest string the value can take, so stepping never resizes the card
     *                    under the cursor
     */
    record Card(String title, String value, String widestValue, String tag, float fraction, boolean live) {}

    private static final int PAD = 4;
    private static final int STRIPE = 2;
    private static final int LINE = 9;
    private static final int BAR = 2;
    /** Gap between the widest wheel label and the card. */
    private static final int LABEL_CLEARANCE = 10;
    private static final int SCREEN_MARGIN = 4;
    private static final float INACTIVE_ALPHA = 0.4f;
    private static final int MUTED = 0xFF8B98A5;
    private static final int GREY = 0xFF5A6470;

    private ScaleCardDraw() {}

    /**
     * @param labelRight the right edge of the wheel's labels
     * @param hot whether the card's own leaf is the hovered one
     */
    static void render(GuiGraphics g, Font font, int cy, int screenW, int labelRight,
                       float menuAlpha, Card card, boolean hot,
                       int accentRgb, int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f) * (hot ? 1.0f : INACTIVE_ALPHA);
        if (a <= 0.01f) return;

        int valueW = Math.max(font.width(card.value()), font.width(card.widestValue()));
        int innerW = Math.max(font.width(card.title()) + 8 + valueW, font.width(card.tag()));
        int w = STRIPE + PAD * 2 + innerW;
        int h = PAD + LINE + 2 + LINE + PAD;

        int left = Math.min(labelRight + LABEL_CLEARANCE, screenW - w - SCREEN_MARGIN);
        int top = cy - h / 2;
        int accent = hot ? 0xFF000000 | accentRgb : GREY;

        g.fill(left, top, left + w, top + h, scale(panelArgb, a));
        g.fill(left, top, left + STRIPE, top + h, scale(accent, a));

        int textX = left + STRIPE + PAD;
        int right = left + w - PAD;
        g.drawString(font, card.title(), textX, top + PAD, scale(MUTED, a), false);
        g.drawString(font, card.value(), right - font.width(card.value()), top + PAD,
                scale(card.live() ? labelArgb : MUTED, a), false);
        g.drawString(font, card.tag(), textX, top + PAD + LINE + 2, scale(accent, a), false);

        int barLeft = left + STRIPE;
        int barW = w - STRIPE;
        g.fill(barLeft, top + h - BAR, barLeft + barW, top + h, scale(0x40FFFFFF, a));
        int fill = Math.round(barW * Mth.clamp(card.fraction(), 0.0f, 1.0f));
        if (fill > 0) {
            g.fill(barLeft, top + h - BAR, barLeft + fill, top + h, scale(accent, a));
        }

        if (hot) {
            g.drawString(font, I18n.get("gui.tacz_sewv.wheel.scale.hint"),
                    textX, top + h + 3, scale(MUTED, a), false);
        }
    }

    private static int scale(int argb, float a) {
        return FormationPreviewDraw.scaleAlpha(argb, a);
    }
}
