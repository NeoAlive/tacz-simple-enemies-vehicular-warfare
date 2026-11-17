package com.neoalive.tacz_sewv.client.map;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.client.zone.ZoneViewClient;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;

/** Spawn zone fills, beacon squares and the legend for the operator map view. */
public final class SpawnZonePainter {

    public static final int PRIORITY_RGB = 0x33CC55;
    public static final int MANUAL_NOZONE_RGB = 0xE53935;
    public static final int BEACON_NOZONE_RGB = 0xFB8C00;
    private static final int FILL_ALPHA = 0x66000000;
    private static final int OUTLINE = 0xFF000000;
    private static final int UNPOWERED = 0xFF9E9E9E;

    private static final Component LEGEND_TITLE = Component.translatable("gui.tacz_sewv.zones.legend.title");
    private static final Component[] LEGEND_LABELS = {
            Component.translatable("gui.tacz_sewv.zones.legend.priority"),
            Component.translatable("gui.tacz_sewv.zones.legend.manual_nozone"),
            Component.translatable("gui.tacz_sewv.zones.legend.beacon_nozone"),
            Component.translatable("gui.tacz_sewv.zones.legend.neutral")
    };
    private static final int[] LEGEND_COLORS = {PRIORITY_RGB, MANUAL_NOZONE_RGB, BEACON_NOZONE_RGB, -1};

    private SpawnZonePainter() {}

    private static int rgbOf(byte kind) {
        return switch (kind) {
            case ZoneViewClient.PRIORITY -> PRIORITY_RGB;
            case ZoneViewClient.MANUAL_NOZONE -> MANUAL_NOZONE_RGB;
            default -> BEACON_NOZONE_RGB;
        };
    }

    /** Visible chunk bounds come from the caller, since a projection cannot be inverted. */
    public static void drawZones(GuiGraphics g, MapProjection p, String dimension, int minCx, int maxCx, int minCz,
            int maxCz) {
        ZoneViewClient.Snapshot s = ZoneViewClient.snapshot();
        if (!s.dimension().equals(dimension)) return;
        ZoneViewClient.Run[] runs = s.runs();
        int from = firstRow(runs, minCz);
        g.drawManaged(() -> {
            for (int i = from; i < runs.length; i++) {
                ZoneViewClient.Run r = runs[i];
                if (r.cz() > maxCz) break;
                if (r.x1() < minCx || r.x0() > maxCx) continue;
                fillChunks(g, p, Math.max(r.x0(), minCx), Math.min(r.x1(), maxCx), r.cz(), r.cz(),
                        FILL_ALPHA | rgbOf(r.kind()));
            }
            for (ZoneViewClient.Beacon b : s.beacons()) drawBeacon(g, p, b);
        });
    }

    /** Index of the first run on row {@code cz} or later. Runs are sorted by row, then x. */
    private static int firstRow(ZoneViewClient.Run[] runs, int cz) {
        int lo = 0, hi = runs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (runs[mid].cz() < cz) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static void fillChunks(GuiGraphics g, MapProjection p, int cx0, int cx1, int cz0, int cz1, int argb) {
        int[] a = p.toScreen(cx0 << 4, cz0 << 4);
        int[] b = p.toScreen((cx1 + 1) << 4, (cz1 + 1) << 4);
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        if (x1 == x0) x1++;
        if (y1 == y0) y1++;
        g.fill(x0, y0, x1, y1, argb);
    }

    /** The stroke being drawn, before the server has it: brush colour, brighter than a saved zone. */
    public static void drawStroke(GuiGraphics g, MapProjection p, LongCollection chunks, byte kind, boolean erase) {
        if (chunks.isEmpty()) return;
        int argb = erase ? 0x90FFFFFF : 0xA0000000 | rgbOf(kind);
        g.drawManaged(() -> {
            for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
                long k = it.nextLong();
                int cx = ZoneGeometry.x(k), cz = ZoneGeometry.z(k);
                fillChunks(g, p, cx, cx, cz, cz, argb);
            }
        });
    }

    private static void drawBeacon(GuiGraphics g, MapProjection p, ZoneViewClient.Beacon b) {
        int cx = b.x() >> 4, cz = b.z() >> 4, r = b.radius();
        int[] a = p.toScreen((cx - r) << 4, (cz - r) << 4);
        int[] c = p.toScreen((cx + r + 1) << 4, (cz + r + 1) << 4);
        int x0 = Math.min(a[0], c[0]), x1 = Math.max(a[0], c[0]);
        int y0 = Math.min(a[1], c[1]), y1 = Math.max(a[1], c[1]);
        if (!p.intersects(x0 - 4, y0 - 4, x1 + 4, y1 + 4)) return;
        int line = b.powered() ? 0xFF000000 | BEACON_NOZONE_RGB : UNPOWERED;
        g.fill(x0, y0, x1, y0 + 1, line);
        g.fill(x0, y1 - 1, x1, y1, line);
        g.fill(x0, y0, x0 + 1, y1, line);
        g.fill(x1 - 1, y0, x1, y1, line);
        int[] at = p.toScreen(b.x() + 0.5, b.z() + 0.5);
        g.fill(at[0] - 3, at[1] - 3, at[0] + 4, at[1] + 4, OUTLINE);
        g.fill(at[0] - 2, at[1] - 2, at[0] + 3, at[1] + 3, line);
    }

    /** Legend box in the bottom-left corner, plus the zone of the chunk under the cursor. */
    public static void drawLegend(GuiGraphics g, Font font, int left, int screenH, int hoverBlockX, int hoverBlockZ,
            String dimension) {
        Component[] labels = LEGEND_LABELS;
        int[] colors = LEGEND_COLORS;
        Component title = LEGEND_TITLE;
        Component hover = hoverLine(hoverBlockX, hoverBlockZ, dimension);
        int w = font.width(title);
        for (Component l : labels) w = Math.max(w, font.width(l) + 14);
        w = Math.max(w, font.width(hover)) + 8;
        int rows = labels.length + 2;
        int h = rows * 11 + 6;
        int x = left, y = screenH - h - 30;
        g.fill(x, y, x + w, y + h, 0xB0000000);
        g.drawString(font, title, x + 4, y + 4, 0xFFFFFF);
        for (int i = 0; i < labels.length; i++) {
            int ly = y + 4 + (i + 1) * 11;
            if (colors[i] >= 0) {
                g.fill(x + 4, ly, x + 12, ly + 8, 0xFF000000 | colors[i]);
            } else {
                g.fill(x + 4, ly, x + 12, ly + 1, 0xFFFFFFFF);
                g.fill(x + 4, ly + 7, x + 12, ly + 8, 0xFFFFFFFF);
                g.fill(x + 4, ly, x + 5, ly + 8, 0xFFFFFFFF);
                g.fill(x + 11, ly, x + 12, ly + 8, 0xFFFFFFFF);
            }
            g.drawString(font, labels[i], x + 16, ly, 0xE0E0E0);
        }
        g.drawString(font, hover, x + 4, y + 4 + (labels.length + 1) * 11, 0xFFFF55);
    }

    private static Component hoverLine(int bx, int bz, String dimension) {
        int cx = bx >> 4, cz = bz >> 4;
        ZoneViewClient.Snapshot s = ZoneViewClient.snapshot();
        byte kind = s.dimension().equals(dimension) ? s.kinds().get(ZoneGeometry.pack(cx, cz)) : 0;
        String key = switch (kind) {
            case ZoneViewClient.PRIORITY -> "gui.tacz_sewv.zones.legend.priority";
            case ZoneViewClient.MANUAL_NOZONE -> "gui.tacz_sewv.zones.legend.manual_nozone";
            case ZoneViewClient.BEACON_NOZONE -> "gui.tacz_sewv.zones.legend.beacon_nozone";
            default -> "gui.tacz_sewv.zones.legend.neutral";
        };
        return Component.translatable("gui.tacz_sewv.zones.legend.hover", cx, cz, Component.translatable(key));
    }
}
