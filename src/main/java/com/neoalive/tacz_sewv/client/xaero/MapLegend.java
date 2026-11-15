package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * The quick wheel's input legend, brought to the World Map: every input that does something right
 * now, as keycap rows in the bottom right. Rebuilt each frame from what the map is doing, so it never
 * lists a gesture that would do nothing.
 */
public final class MapLegend {

    /** What the map is doing this frame, as the legend needs it. */
    public record State(boolean waypointDrag, boolean overHandle, boolean flyout, boolean flyoutValue,
                        boolean plotting, boolean cruisePlot, boolean overRibbonUnit, int selected) {}

    private record Row(String key, String action) {}

    private static final int PAD = 5;
    private static final int GAP = 5;
    private static final int ROW_H = 13;
    private static final int MARGIN = 6;

    private MapLegend() {}

    /** Draws the legend with its bottom edge at {@code bottom} and right edge at {@code right}. */
    public static void render(GuiGraphics g, Font font, int right, int bottom, State s) {
        List<Row> rows = rows(s);
        if (rows.isEmpty()) return;
        int keyW = 0;
        int actW = 0;
        for (Row r : rows) {
            keyW = Math.max(keyW, Math.max(11, font.width(r.key()) + 6));
            actW = Math.max(actW, font.width(r.action()));
        }
        int w = PAD * 2 + keyW + GAP + actW;
        int h = PAD * 2 + rows.size() * ROW_H - 2;
        int x1 = right - MARGIN;
        int x0 = x1 - w;
        int y1 = bottom - MARGIN;
        int y0 = y1 - h;
        XaeroTheme.panel(g, x0, y0, w, h, false);
        int y = y0 + PAD;
        for (Row r : rows) {
            int capW = Math.max(11, font.width(r.key()) + 6);
            XaeroTheme.keycap(g, font, x0 + PAD + keyW - capW, y, r.key());
            XaeroTheme.text(g, font, r.action(), x0 + PAD + keyW + GAP, y + 2, XaeroTheme.DIM);
            y += ROW_H;
        }
    }

    private static List<Row> rows(State s) {
        List<Row> out = new ArrayList<>();
        if (s.waypointDrag()) {
            add(out, "lmb_release", "confirm");
            add(out, "esc_rmb", "cancel");
            return out;
        }
        if (s.flyout()) {
            add(out, "lmb", "issue");
            if (s.flyoutValue()) add(out, "scroll", "value");
            add(out, "ctrl", "details");
            add(out, "esc", "close");
            return out;
        }
        if (s.plotting()) {
            add(out, "lmb", s.cruisePlot() ? "add_node" : "place");
            add(out, "rmb", "remove");
            return out;
        }
        if (s.overHandle()) {
            add(out, "lmb_drag", "move_waypoint");
            add(out, "shift_drag", "detach");
        }
        if (s.overRibbonUnit()) {
            add(out, "lmb", "select");
            add(out, "shift_lmb", "toggle");
            add(out, "double", "centre");
        }
        if (!s.overHandle() && !s.overRibbonUnit()) {
            add(out, "lmb", "select");
            if (s.selected() >= 2) add(out, "lmb_drag", "line_move");
            add(out, "mmb_drag", "box");
        }
        add(out, "rmb", s.selected() > 0 ? "orders" : "menu");
        return out;
    }

    private static void add(List<Row> out, String key, String action) {
        out.add(new Row(I18n.get("gui.tacz_sewv.map.legend.key." + key), I18n.get("gui.tacz_sewv.map.legend." + action)));
    }
}
