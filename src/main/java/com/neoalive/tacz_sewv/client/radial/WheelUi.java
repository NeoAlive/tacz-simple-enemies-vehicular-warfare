package com.neoalive.tacz_sewv.client.radial;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;

/**
 * The wheel's order descriptions and full-screen detail card, for other screens (the world map's
 * order flyout). The drawers stay package-private; this is the one door to them.
 */
public final class WheelUi {

    private WheelUi() {}

    /** Smoothstep used by the detail card fade. */
    public static float ease(float t) {
        return DetailOverlayDraw.ease(t);
    }

    /** Full-screen description of the leaf {@code pipelineId}, titled {@code title}. */
    public static void detail(GuiGraphics g, Font font, int w, int h, float eased, String title,
                              String pipelineId) {
        DetailOverlayDraw.render(g, font, w, h, eased, new OrderInfo.Text(title, description(pipelineId)));
    }

    /** The wheel's description text for a leaf id, or an empty string when there is none. */
    public static String description(String pipelineId) {
        String key = OrderInfo.DESC_PREFIX + OrderInfo.descId(pipelineId);
        return I18n.exists(key) ? I18n.get(key) : "";
    }

    /** The wheel's glyph and accent for a leaf id, or null when the wheel has no such leaf. */
    @Nullable
    public static WedgeEntry.PipelineEntry leaf(String pipelineId) {
        return find(QuickCommandRegistry.rootWedges(), pipelineId);
    }

    @Nullable
    private static WedgeEntry.PipelineEntry find(java.util.List<WedgeEntry> entries, String id) {
        for (WedgeEntry e : entries) {
            if (e instanceof WedgeEntry.PipelineEntry p && p.pipelineId().equals(id)) return p;
            if (e instanceof WedgeEntry.CategoryEntry c) {
                WedgeEntry.PipelineEntry hit = find(c.children(), id);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
