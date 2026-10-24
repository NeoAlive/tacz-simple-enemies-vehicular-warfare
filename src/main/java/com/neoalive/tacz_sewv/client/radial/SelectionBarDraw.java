package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

/**
 * Who the next order goes to, under the wheel — the replacement for the TDT ribbon's count line.
 * A selection silently changes every order's recipients ({@link WheelScope}), so the scope line is
 * loud when one is set and the keys that change it are always on screen.
 *
 * <pre>
 *        ● 5 selected                 (accent)   |   Nearby · 12 units  (muted)
 *   [A] All   [N] None   [L] Live Select
 * </pre>
 */
final class SelectionBarDraw {

    private static final int MUTED = 0xFF8B98A5;
    private static final int SELECTED = 0xFFFFD84A;
    private static final int PAD = 4;

    private SelectionBarDraw() {}

    static void render(GuiGraphics g, Font font, int cx, int cy, int labelReach, float menuAlpha,
                       int selectedCount, int nearbyCount, KeyMapping all, KeyMapping none, KeyMapping live,
                       int panelArgb, int labelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (a < 0.05f) return;

        boolean explicit = selectedCount > 0;
        String scope = explicit
                ? I18n.get("gui.tacz_sewv.wheel.sel.selected", selectedCount)
                : I18n.get("gui.tacz_sewv.wheel.sel.nearby", nearbyCount);
        String keys = chip(all, "gui.tacz_sewv.wheel.sel.all") + "   "
                + chip(none, "gui.tacz_sewv.wheel.sel.none") + "   "
                + chip(live, "gui.tacz_sewv.wheel.sel.live");

        int w = Math.max(font.width(scope), font.width(keys)) + PAD * 2;
        int h = PAD + font.lineHeight + 3 + font.lineHeight + PAD;
        // Below the bottom wedge's label, mirroring the queue-mode line above the top one.
        int top = cy + labelReach + font.lineHeight + 4;
        int left = cx - w / 2;
        g.fill(left, top, left + w, top + h, FormationPreviewDraw.scaleAlpha(panelArgb, a));
        if (explicit) {
            g.fill(left, top, left + w, top + 1, FormationPreviewDraw.scaleAlpha(SELECTED, a));
        }
        g.drawCenteredString(font, scope, cx, top + PAD,
                FormationPreviewDraw.scaleAlpha(explicit ? SELECTED : MUTED, a));
        g.drawCenteredString(font, keys, cx, top + PAD + font.lineHeight + 3,
                FormationPreviewDraw.scaleAlpha(labelArgb, a));
    }

    private static String chip(KeyMapping key, String labelKey) {
        return "[" + key.getTranslatedKeyMessage().getString() + "] " + I18n.get(labelKey);
    }
}
