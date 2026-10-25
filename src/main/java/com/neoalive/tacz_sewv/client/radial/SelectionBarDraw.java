package com.neoalive.tacz_sewv.client.radial;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/**
 * Who the next order goes to, under the wheel — the replacement for the TDT ribbon's count line.
 * A selection silently changes every order's recipients ({@link WheelScope}), so the scope line is
 * loud when one is set and the keys that change it are always on screen.
 *
 * <pre>
 *   ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔  (strip: accent when selected, like a wedge's)
 *        ● 5 selected                 (accent)   |   Nearby · 12 units  (muted)
 *   ▐A▌ All   ▐N▌ None   ▐L▌ Live Select          (keycaps bright, descriptions muted)
 * </pre>
 */
final class SelectionBarDraw {

    private static final int MUTED = 0xFF8B98A5;
    private static final int SELECTED = 0xFFFFD84A;
    private static final int KEY = 0xFFFFFFFF;
    private static final int KEYCAP = 0x40FFFFFF;
    /** Same alpha as a cold wedge strip. */
    private static final int STRIP_IDLE = 0x80000000 | QuickCommandRegistry.SELECT_ACCENT;
    private static final int STRIP = 2;
    private static final int PAD = 4;
    private static final int CAP_PAD = 2;
    /** Between a key and its description, and between chips. */
    private static final int KEY_GAP = 3;
    private static final int CHIP_GAP = 10;
    /** Gap below the labels' bottom edge. */
    private static final int LABEL_CLEARANCE = 6;

    private SelectionBarDraw() {}

    static void render(GuiGraphics g, Font font, int cx, int labelBottom, float menuAlpha,
                       int selectedCount, int nearbyCount, KeyMapping all, KeyMapping none, KeyMapping live,
                       int panelArgb) {
        float a = Mth.clamp(menuAlpha, 0.0f, 1.0f);
        if (a < 0.05f) return;

        boolean explicit = selectedCount > 0;
        String scope = explicit
                ? I18n.get("gui.tacz_sewv.wheel.sel.selected", selectedCount)
                : I18n.get("gui.tacz_sewv.wheel.sel.nearby", nearbyCount);
        String[][] chips = {chip(all, "gui.tacz_sewv.wheel.sel.all"),
                chip(none, "gui.tacz_sewv.wheel.sel.none"),
                chip(live, "gui.tacz_sewv.wheel.sel.live")};
        int keysW = -CHIP_GAP;
        for (String[] c : chips) keysW += chipWidth(font, c) + CHIP_GAP;

        int w = Math.max(font.width(scope), keysW) + PAD * 2;
        int h = PAD + font.lineHeight + 3 + font.lineHeight + PAD;
        int top = labelBottom + LABEL_CLEARANCE;
        int left = cx - w / 2;
        g.fill(left, top, left + w, top + h, FormationPreviewDraw.scaleAlpha(panelArgb, a));
        g.fill(left, top, left + w, top + STRIP,
                FormationPreviewDraw.scaleAlpha(explicit ? SELECTED : STRIP_IDLE, a));
        g.drawCenteredString(font, scope, cx, top + PAD,
                FormationPreviewDraw.scaleAlpha(explicit ? SELECTED : MUTED, a));

        int x = cx - keysW / 2;
        int y = top + PAD + font.lineHeight + 3;
        for (String[] c : chips) {
            int kw = font.width(c[0]);
            g.fill(x, y - CAP_PAD, x + kw + CAP_PAD * 2, y + font.lineHeight + 1,
                    FormationPreviewDraw.scaleAlpha(KEYCAP, a));
            g.drawString(font, c[0], x + CAP_PAD, y, FormationPreviewDraw.scaleAlpha(KEY, a), true);
            g.drawString(font, c[1], x + kw + CAP_PAD * 2 + KEY_GAP, y,
                    FormationPreviewDraw.scaleAlpha(MUTED, a), true);
            x += chipWidth(font, c) + CHIP_GAP;
        }
    }

    /** {key name, description}. */
    private static String[] chip(KeyMapping key, String labelKey) {
        return new String[] {key.getTranslatedKeyMessage().getString(), I18n.get(labelKey)};
    }

    private static int chipWidth(Font font, String[] chip) {
        return font.width(chip[0]) + CAP_PAD * 2 + KEY_GAP + font.width(chip[1]);
    }
}
