package com.neoalive.tacz_sewv.client.map;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.client.AirportPlots;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.PreferredPathwaysClient;
import com.neoalive.tacz_sewv.client.xaero.OrderPreview;
import com.neoalive.tacz_sewv.client.xaero.PathwayMapRender;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.init.ModBlocks;
import com.neoalive.tacz_sewv.map.BattleFieldMarker;
import com.neoalive.tacz_sewv.map.MarkerOrder;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * The passive map overlays — everything drawn straight in screen space rather than as an element:
 * runway plots, standing-order previews, saved pathways and the debug battlefield picture. Shared by
 * the World Map (drawn at {@code GuiMap.render} TAIL) and the minimap (one sentinel element in its
 * framebuffer, which clips these to the map shape for free).
 *
 * <p>Only what reads synced state lives here. Anything interactive — drags, box select, the route
 * and pathway plotting, the RTS panel — stays in {@code MixinGuiMap}, because it only exists while
 * the fullscreen map has the mouse.
 *
 * <p>({@code OrderPreview} and {@code PathwayMapRender} sit in {@code client.xaero} for history, but
 * name no Xaero type, so calling them from here keeps this class Xaero-free.)
 */
public final class MapOverlayPainter {

    private MapOverlayPainter() {}

    /** Every passive overlay in the World Map's order. {@code dim} is the dimension being drawn. */
    public static void drawAll(GuiGraphics guiGraphics, MapProjection project, ResourceKey<Level> dim,
                               boolean savedPathways) {
        drawAirportOverlay(guiGraphics, project, dim);
        drawStandingPreviews(guiGraphics, project, dim);
        if (ClientConfig.MAP_SHOW_COMMAND_DEBUG.get()) {
            drawBattleFieldOverlay(guiGraphics, project, dim);
        }
        if (savedPathways) {
            drawSavedPathways(guiGraphics, project, dim);
        }
    }

    /**
     * Every runway whose clearance check has passed, shaded as a filled rectangle.
     *
     * <p>Drawn first of the overlays so a strip reads as ground the other marks sit on rather than
     * as something painted over them. It is flat-shaded and unpulsed for the same reason: a cleared
     * strip is a fixed piece of terrain, not a live task like a sweep box or a standing order.
     *
     * <p>A plot is dropped when its runway block is <b>gone</b>, which is what keeps a broken strip
     * from shading the map until the next relog — {@code isLoaded} is the whole guard, since an
     * unloaded chunk reports air for a block that is perfectly well still there.
     */
    public static void drawAirportOverlay(GuiGraphics guiGraphics, MapProjection project, ResourceKey<Level> dim) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        final int color = 0xFF88CCFF;

        for (AirportPlots.Plot plot : AirportPlots.plots()) {
            if (!dim.equals(plot.dimension())) continue;
            if (mc.level.isLoaded(plot.runway())
                    && !mc.level.getBlockState(plot.runway()).is(ModBlocks.RUNWAY.get())) {
                AirportPlots.forget(plot.runway());
                continue;
            }

            int[] a = project.toScreen(Math.min(plot.x1(), plot.x2()),
                    Math.min(plot.z1(), plot.z2()));
            int[] b = project.toScreen(Math.max(plot.x1(), plot.x2()) + 1.0,
                    Math.max(plot.z1(), plot.z2()) + 1.0);
            int x0 = Math.min(a[0], b[0]);
            int y0 = Math.min(a[1], b[1]);
            int x1 = Math.max(a[0], b[0]);
            int y1 = Math.max(a[1], b[1]);
            if (x1 <= x0 || y1 <= y0) continue;
            if (!project.intersects(x0, y0, x1, y1)) continue;

            guiGraphics.fill(x0, y0, x1, y1, (color & 0x00FFFFFF) | 0x44000000);
            guiGraphics.fill(x0, y0, x1, y0 + 1, color);
            guiGraphics.fill(x0, y1 - 1, x1, y1, color);
            guiGraphics.fill(x0, y0, x0 + 1, y1, color);
            guiGraphics.fill(x1 - 1, y0, x1, y1, color);
        }
    }

    /**
     * The faint animated overlay of every own unit's standing order, drawn from the server-synced
     * {@link MarkerOrder} on each marker. Because the data is the sync, a dismissed or overridden
     * order simply stops arriving and the overlay clears itself — there is no client-held state to go
     * stale. Only the drawn dimension is used, so a unit in the Nether does not scatter across the
     * Overworld map.
     */
    public static void drawStandingPreviews(GuiGraphics guiGraphics, MapProjection project, ResourceKey<Level> dim) {
        // Same smoothed position the icon is drawn at, so a line never trails its unit by a sync.
        float partialTicks = Minecraft.getInstance().getFrameTime();
        for (VehicleMarker marker : MapMarkers.markers()) {
            MarkerOrder order = marker.order();
            if (order.type() == MarkerOrder.Type.NONE || !dim.equals(marker.dimension())) continue;

            int[] unit = project.toScreen(MarkerMotion.x(marker, partialTicks), MarkerMotion.z(marker, partialTicks));
            if (!project.onScreen(unit)) continue;
            int color = OrderPreview.lowAlpha(VehicleMarkerPainter.factionColor(marker.faction()));
            switch (order.type()) {
                case MOVE -> {
                    int[] t = project.toScreen(order.target());
                    OrderPreview.dashedLine(guiGraphics, unit[0], unit[1], t[0], t[1], color);
                    OrderPreview.blinkingDot(guiGraphics, t[0], t[1], color);
                }
                case PATROL, SEARCH -> {
                    int[] c = project.toScreen(order.target());
                    OrderPreview.ring(guiGraphics, c[0], c[1], project.lengthPx(order.radius()), color);
                    OrderPreview.node(guiGraphics, c[0], c[1], color);
                }
                case CRUISE -> {
                    List<BlockPos> route = order.route();
                    for (int i = 0; i < route.size(); i++) {
                        int[] from = project.toScreen(route.get(i));
                        int[] to = project.toScreen(route.get((i + 1) % route.size()));
                        if (route.size() > 1) {
                            OrderPreview.dashedLine(guiGraphics, from[0], from[1], to[0], to[1], color);
                        }
                        OrderPreview.node(guiGraphics, from[0], from[1], color);
                    }
                }
                case NONE -> { }
            }
        }
    }

    /** The player's saved preferred pathways, labelled. */
    public static void drawSavedPathways(GuiGraphics guiGraphics, MapProjection project, ResourceKey<Level> dim) {
        int color = ClientConfig.parseColor(ClientConfig.COLOR_PMC.get(), 0xFF55FF55);
        PathwayMapRender.drawSaved(guiGraphics, Minecraft.getInstance().font, project::toScreen,
                PreferredPathwaysClient.forDimension(dim), color);
    }

    /**
     * Debug BattleField overlay: friendly/enemy centroids, enemy→us axis chevron, open-flank
     * marks. Draws wire fields only — no centroid/flank/geometry recomputation.
     */
    public static void drawBattleFieldOverlay(GuiGraphics guiGraphics, MapProjection project, ResourceKey<Level> dim) {
        Font font = Minecraft.getInstance().font;
        final int FRIENDLY = 0xFF55FF55;
        final int ENEMY = 0xFFFF5555;
        final int AXIS = 0xFFFFFF55;
        final int FLANK = 0xFF55FFFF;

        for (BattleFieldMarker bf : MapMarkers.battleFields()) {
            if (!dim.equals(bf.dimension())) continue;

            int[] friend = project.toScreen(bf.friendlyX(), bf.friendlyZ());
            int[] enemy = project.toScreen(bf.enemyX(), bf.enemyZ());

            // Axis: dotted leg enemy → friendly, then a chevron at the friendly end.
            leg(guiGraphics, enemy, friend, AXIS);
            chevron(guiGraphics, enemy, friend, AXIS);

            // Centroids — distinct marks (enemy diamond, friendly square).
            diamond(guiGraphics, enemy[0], enemy[1], 5, ENEMY);
            guiGraphics.fill(friend[0] - 4, friend[1] - 4, friend[0] + 4, friend[1] + 4, 0xFF000000);
            guiGraphics.fill(friend[0] - 3, friend[1] - 3, friend[0] + 3, friend[1] + 3, FRIENDLY);

            // Play name — above the friendly centroid, white on dark so it stays legible
            // beside stars / axis / flanks (not sharing their glyphs).
            String play = bf.playLabel();
            if (play != null && !play.isEmpty()) {
                int pw = font.width(play);
                int px = friend[0] - pw / 2;
                int py = friend[1] - 16;
                guiGraphics.fill(px - 2, py - 1, px + pw + 2, py + font.lineHeight, 0xC0000000);
                guiGraphics.drawString(font, play, px, py, 0xFFFFFFF0, false);
            }

            if (bf.flankLeft()) {
                int[] fl = project.toScreen(bf.flankLeftX(), bf.flankLeftZ());
                flankMark(guiGraphics, font, fl[0], fl[1], FLANK, "L");
            }
            if (bf.flankRight()) {
                int[] fr = project.toScreen(bf.flankRightX(), bf.flankRightZ());
                flankMark(guiGraphics, font, fr[0], fr[1], FLANK, "R");
            }
        }
    }

    /** Filled diamond centred on (cx,cy) — enemy centroid mark. */
    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
        // Thin black outline tips
        g.fill(cx, cy - r - 1, cx + 1, cy - r, 0xFF000000);
        g.fill(cx, cy + r, cx + 1, cy + r + 1, 0xFF000000);
    }

    /** Open-flank marker: cyan ring + L/R label. */
    private static void flankMark(GuiGraphics g, Font font, int cx, int cy, int color, String label) {
        g.fill(cx - 6, cy - 6, cx + 6, cy + 6, 0xFF000000);
        g.fill(cx - 5, cy - 5, cx + 5, cy + 5, color);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF000000);
        g.drawString(font, label, cx + 7, cy - 4, color, false);
    }

    /**
     * Chevron at {@code to}, pointing along the segment from→to (enemy→us). Screen-space only —
     * direction comes from the two centroid positions already on the wire.
     */
    private static void chevron(GuiGraphics g, int[] from, int[] to, int color) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 8.0) return;
        double ux = dx / len;
        double uy = dy / len;
        // Perpendicular
        double px = -uy;
        double py = ux;
        int tipX = to[0];
        int tipY = to[1];
        int wing = 7;
        int back = 10;
        int lx = (int) Math.round(tipX - ux * back + px * wing);
        int ly = (int) Math.round(tipY - uy * back + py * wing);
        int rx = (int) Math.round(tipX - ux * back - px * wing);
        int ry = (int) Math.round(tipY - uy * back - py * wing);
        leg(g, new int[]{lx, ly}, new int[]{tipX, tipY}, color);
        leg(g, new int[]{rx, ry}, new int[]{tipX, tipY}, color);
        g.fill(tipX - 2, tipY - 2, tipX + 2, tipY + 2, color);
    }

    /** A leg as a chain of dots: no rotated quad, and it reads as a route rather than a border. */
    public static void leg(GuiGraphics guiGraphics, int[] from, int[] to, int color) {
        int dx = to[0] - from[0];
        int dy = to[1] - from[1];
        int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 6;
        for (int i = 1; i < steps; i++) {
            int x = from[0] + dx * i / steps;
            int y = from[1] + dy * i / steps;
            guiGraphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }
}
