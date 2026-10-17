package com.neoalive.tacz_sewv.client.xaero;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Draws preferred pathways on the Xaero world map — saved catalog and the live plot overlay.
 * Uses the same dashed-line vocabulary as {@link OrderPreview} standing orders.
 */
public final class PathwayMapRender {

    private static final int START_TINT = 0xFF88FFAA;
    private static final int END_TINT = 0xFFFFCC66;
    private static final int HOVER_TINT = 0xFFFFFFAA;
    private static final int SELECT_TINT = 0xFFFFFFFF;

    private PathwayMapRender() {}

    public record Cursor(int screenX, int screenY, boolean valid) {}

    /** Saved paths — soft, labelled, always visible when the map is open. */
    public static void drawSaved(GuiGraphics g, Font font, PathwayPlot.ScreenProject project,
                                 java.util.Map<String, List<BlockPos>> paths, int baseColor) {
        for (var entry : paths.entrySet()) {
            drawRoute(g, font, project, entry.getValue(), baseColor, 0x66, entry.getKey(),
                    -1, -1, null, false);
        }
    }

    /** Active plot — bright, interactive highlights, ghost leg to cursor. */
    public static void drawPlot(GuiGraphics g, Font font, PathwayPlot.ScreenProject project,
                                List<BlockPos> nodes, int selected, int hover, int baseColor,
                                @Nullable Cursor cursor) {
        drawRoute(g, font, project, nodes, baseColor, 0xDD, null, selected, hover, cursor, true);
    }

    private static void drawRoute(GuiGraphics g, Font font, PathwayPlot.ScreenProject project,
                                  List<BlockPos> nodes, int baseColor, int lineAlpha,
                                  @Nullable String label, int selected, int hover,
                                  @Nullable Cursor cursor, boolean plotMode) {
        if (nodes.isEmpty()) {
            return;
        }

        int lineColor = tint(baseColor, lineAlpha);
        int n = nodes.size();

        for (int i = 0; i < n - 1; i++) {
            int[] from = project.toScreen(nodes.get(i));
            int[] to = project.toScreen(nodes.get(i + 1));
            OrderPreview.dashedLine(g, from[0], from[1], to[0], to[1], lineColor);
            drawSegmentArrow(g, from, to, tint(baseColor, Math.min(255, lineAlpha + 40)));
        }

        if (plotMode && cursor != null && cursor.valid() && n >= 1) {
            int[] last = project.toScreen(nodes.get(n - 1));
            OrderPreview.dashedLine(g, last[0], last[1], cursor.screenX(), cursor.screenY(),
                    tint(baseColor, 0x44));
        }

        for (int i = 0; i < n; i++) {
            int[] at = project.toScreen(nodes.get(i));
            boolean isStart = i == 0;
            boolean isEnd = i == n - 1;
            boolean picked = i == selected;
            boolean hovered = i == hover && hover != selected;

            int nodeColor = baseColor;
            if (isStart) nodeColor = blend(baseColor, START_TINT, 0.35f);
            if (isEnd && n > 1) nodeColor = blend(baseColor, END_TINT, 0.35f);
            if (hovered) nodeColor = blend(nodeColor, HOVER_TINT, 0.5f);
            if (picked) nodeColor = SELECT_TINT;

            drawNode(g, font, at[0], at[1], nodeColor, isStart, isEnd, plotMode);
            if (plotMode || n <= 12) {
                String num = String.valueOf(i + 1);
                int tw = font.width(num);
                g.drawString(font, num, at[0] - tw / 2, at[1] - 12, tint(nodeColor, 0xFF), false);
            }
        }

        if (label != null && !label.isEmpty()) {
            int[] start = project.toScreen(nodes.get(0));
            int lw = font.width(label);
            int lx = start[0] - lw / 2;
            int ly = start[1] + 8;
            g.fill(lx - 2, ly - 1, lx + lw + 2, ly + font.lineHeight, 0xA0000000);
            g.drawString(font, label, lx, ly, tint(baseColor, 0xCC), false);
        }
    }

    private static void drawNode(GuiGraphics g, Font font, int x, int y, int color,
                                 boolean start, boolean end, boolean plotMode) {
        int r = plotMode ? 5 : 4;
        if (end && !start) {
            // Diamond terminus
            for (int dy = -r; dy <= r; dy++) {
                int half = r - Math.abs(dy);
                g.fill(x - half, y + dy, x + half + 1, y + dy + 1, 0x90000000);
                g.fill(x - half + 1, y + dy, x + half, y + dy + 1, color);
            }
            return;
        }
        // Ring + centre — start gets a slightly larger ring
        int outer = start ? r + 1 : r;
        g.fill(x - outer, y - outer, x + outer + 1, y + outer + 1, 0x90000000);
        g.fill(x - outer + 1, y - outer + 1, x + outer, y + outer, color);
        g.fill(x - 2, y - 2, x + 2, y + 2, start ? tint(color, 0xFF) : 0x90000000);
    }

    private static void drawSegmentArrow(GuiGraphics g, int[] from, int[] to, int color) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 16.0) return;
        double ux = dx / len;
        double uy = dy / len;
        double px = -uy;
        double py = ux;
        // Arrow at 70% along the segment (clear of both nodes)
        int tipX = (int) Math.round(from[0] + dx * 0.7);
        int tipY = (int) Math.round(from[1] + dy * 0.7);
        int wing = 5;
        int back = 8;
        int lx = (int) Math.round(tipX - ux * back + px * wing);
        int ly = (int) Math.round(tipY - uy * back + py * wing);
        int rx = (int) Math.round(tipX - ux * back - px * wing);
        int ry = (int) Math.round(tipY - uy * back - py * wing);
        drawLine(g, lx, ly, tipX, tipY, color);
        drawLine(g, rx, ry, tipX, tipY, color);
    }

    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / Math.max(1, steps);
            int y = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static int tint(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
