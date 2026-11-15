package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.map.RibbonLayout;
import com.neoalive.tacz_sewv.client.map.VehicleMarkerPainter;
import com.neoalive.tacz_sewv.map.MarkerOrder;
import com.neoalive.tacz_sewv.map.MarkerTags;
import com.neoalive.tacz_sewv.map.VehicleMarker;

/**
 * Own units along the bottom of the World Map, grouped under brackets: platoon on top, then
 * formation, then a shared order. Only units near the view are listed (plus selected ones and the
 * rest of any group they are in), recomputed a couple of times a second rather than every frame.
 * Click selects, Shift toggles, double click centres the map, a bracket selects its whole group.
 */
public final class UnitRibbon {

    /** What part of the world the map shows, in blocks, and the camera that produced it. */
    public record View(double minX, double maxX, double minZ, double maxZ, double camX, double camZ, double scale,
                       ResourceKey<Level> dim) {}

    private static final long REFRESH_MS = 500L;
    private static final long DOUBLE_CLICK_MS = 350L;
    private static final double VIEW_MARGIN = 16.0;
    private static final int CELL = 20;
    private static final int ICON = 16;
    private static final int GROUP_GAP = 6;
    private static final int IDLE_GAP = 12;
    private static final int LANE_H = 7;
    /** Bracket type icon, centred on its line. Placeholder art under textures/gui/ribbon_*.png. */
    private static final int LANE_ICON = 7;
    private static final net.minecraft.resources.ResourceLocation[] LANE_ICONS = {
            new net.minecraft.resources.ResourceLocation(com.neoalive.tacz_sewv.TaczSewv.MODID,
                    "textures/gui/ribbon_platoon.png"),
            new net.minecraft.resources.ResourceLocation(com.neoalive.tacz_sewv.TaczSewv.MODID,
                    "textures/gui/ribbon_formation.png"),
            new net.minecraft.resources.ResourceLocation(com.neoalive.tacz_sewv.TaczSewv.MODID,
                    "textures/gui/ribbon_order.png")};
    private static final int PAD = 3;
    private static final int BTN_H = 11;
    /** Panel height this frame: only lanes that hold a bracket take a row. */
    private static int height = 40;
    /** Row of each lane this frame, -1 when the lane holds no bracket. */
    private static final int[] LANE_ROW = {-1, -1, -1};
    private static int laneRows;
    private static int btnW = 30;
    /** Clears Xaero's zoom readout, drawn just above the bottom edge. */
    private static final int BOTTOM = 16;
    private static final int ARROW_W = 10;
    private static final int[] LANE_COLOUR_DEFAULT = {0, 0xFFFFFFFF, XaeroTheme.GOLD};

    private static RibbonLayout.Result layout = new RibbonLayout.Result(List.of(), List.of(), 0);
    private static final Map<Integer, VehicleMarker> BY_VEHICLE = new HashMap<>();
    private static long refreshedAt;
    private static double lastCamX;
    private static double lastCamZ;
    private static double lastScale;
    private static int offset;
    private static int lastClickVehicle = -1;
    private static long lastClickAt;
    @Nullable
    private static VehicleMarker jump;

    // Geometry of the last frame, for hit tests.
    private static int x0;
    private static int y0;
    private static int x1;
    private static int listX0;
    private static int listX1;
    private static boolean overflow;
    private static int[] cellX = new int[0];
    private static boolean shown;

    private UnitRibbon() {}

    /** Height the ribbon takes at the bottom of the screen, for anything that has to sit above it. */
    public static int reserved() {
        return shown ? height + BOTTOM + 2 : 0;
    }

    public static void hide() {
        shown = false;
    }

    /** The unit to centre the camera on after a double click, consumed by the caller. */
    @Nullable
    public static VehicleMarker takeJump() {
        VehicleMarker j = jump;
        jump = null;
        return j;
    }

    public static void render(GuiGraphics g, Font font, int mx, int my, int sw, int sh, int left, int right,
                              View view) {
        refresh(view);
        if (layout.units().isEmpty()) {
            shown = false;
            return;
        }
        shown = true;
        // Sized to what it lists, centred in the space between Xaero's button columns.
        laneRows = 0;
        java.util.Arrays.fill(LANE_ROW, -1);
        for (RibbonLayout.Lane lane : RibbonLayout.Lane.values()) {
            for (RibbonLayout.Span sp : layout.spans()) {
                if (sp.lane() == lane) {
                    LANE_ROW[lane.ordinal()] = laneRows++;
                    break;
                }
            }
        }
        int stack = 3 * BTN_H + 2;
        height = Math.max(PAD + stack + PAD, PAD + laneRows * LANE_H + 1 + ICON + 3 + PAD);
        btnW = 0;
        for (String k : new String[]{"all", "view", "none"}) {
            btnW = Math.max(btnW, font.width(I18n.get("gui.tacz_sewv.map.ribbon." + k)) + 8);
        }
        int avail = Math.max(btnW + 60, sw - right - left);
        int chrome = PAD + btnW + 4 + PAD;
        int want = chrome + contentWidth();
        if (want > avail) want = avail;
        int mid = left + avail / 2;
        x0 = Math.max(left, mid - want / 2);
        x1 = Math.min(left + avail, x0 + want);
        y0 = sh - BOTTOM - height;
        int y1 = y0 + height;
        XaeroTheme.panel(g, x0, y0, x1 - x0, height, false);

        int bx = x0 + PAD;
        int by = y0 + (height - (3 * BTN_H + 2)) / 2;
        String[] labels = {I18n.get("gui.tacz_sewv.map.ribbon.all"), I18n.get("gui.tacz_sewv.map.ribbon.view"),
                I18n.get("gui.tacz_sewv.map.ribbon.none")};
        for (int i = 0; i < 3; i++) {
            int cy = by + i * (BTN_H + 1);
            XaeroTheme.chip(g, font, bx, cy, btnW, BTN_H, labels[i], inBox(mx, my, bx, cy, btnW, BTN_H), false);
        }
        listX0 = bx + btnW + 4;
        listX1 = x1 - PAD;

        int total = contentWidth();
        overflow = total > listX1 - listX0;
        if (overflow) {
            listX0 += ARROW_W + 2;
            listX1 -= ARROW_W + 2;
        } else {
            offset = 0;
        }
        offset = Math.max(0, Math.min(offset, maxOffset()));
        cellX = cellPositions();

        g.enableScissor(listX0, y0, listX1, y1);
        drawBrackets(g, mx, my);
        int iconY = y0 + PAD + laneRows * LANE_H + 1;
        List<RibbonLayout.Unit> units = layout.units();
        for (int i = 0; i < units.size(); i++) {
            int cx = cellX[i];
            if (cx + CELL < listX0 || cx > listX1) continue;
            VehicleMarker m = BY_VEHICLE.get(units.get(i).vehicleId());
            boolean sel = m != null && MapMarkers.isSelected(m);
            boolean hover = inBox(mx, my, cx, iconY - 1, CELL, ICON + 4);
            if (hover) g.fill(cx, iconY - 1, cx + CELL, iconY + ICON + 3, XaeroTheme.ROW_HOVER);
            if (sel) XaeroTheme.outline(g, cx, iconY - 1, CELL, ICON + 4, XaeroTheme.TRIM_OPEN);
            if (m != null) {
                float hp = Math.max(0f, Math.min(1f, m.healthFrac()));
                g.fill(cx + 3, iconY + ICON + 1, cx + CELL - 3, iconY + ICON + 2, 0xFF202020);
                g.fill(cx + 3, iconY + ICON + 1, cx + 3 + Math.round((CELL - 6) * hp), iconY + ICON + 2,
                        hp > 0.66f ? 0xFF55DD55 : hp > 0.33f ? 0xFFE6D34A : 0xFFC44536);
            }
        }
        g.flush();
        drawBracketIcons(g, mx, my);
        VehicleMarkerPainter.prepareTextures();
        for (int i = 0; i < units.size(); i++) {
            int cx = cellX[i];
            if (cx + CELL < listX0 || cx > listX1) continue;
            VehicleMarker m = BY_VEHICLE.get(units.get(i).vehicleId());
            if (m != null) VehicleMarkerPainter.paintIcon(g, m, cx + CELL / 2, iconY + ICON / 2, ICON);
        }
        g.disableScissor();

        if (overflow) {
            int ay = y0 + (height - 14) / 2;
            XaeroTheme.chip(g, font, listX0 - ARROW_W - 2, ay, ARROW_W, 14, "<",
                    inBox(mx, my, listX0 - ARROW_W - 2, ay, ARROW_W, 14), false);
            XaeroTheme.chip(g, font, listX1 + 2, ay, ARROW_W, 14, ">", inBox(mx, my, listX1 + 2, ay, ARROW_W, 14), false);
        }
        tooltip(g, font, mx, my, sw, sh);
    }

    public static boolean contains(double mx, double my) {
        return shown && mx >= x0 && mx < x1 && my >= y0 && my < y0 + height;
    }

    public static boolean mouseClicked(double mx, double my, int button, boolean shift, ResourceKey<Level> dim) {
        if (!contains(mx, my)) return false;
        if (button != 0) return true;
        int by = y0 + (height - (3 * BTN_H + 2)) / 2;
        int bx = x0 + PAD;
        for (int i = 0; i < 3; i++) {
            int cy = by + i * (BTN_H + 1);
            if (!inBox(mx, my, bx, cy, btnW, BTN_H)) continue;
            if (i == 2) {
                MapMarkers.clearSelection();
                return true;
            }
            if (!shift) MapMarkers.clearSelection();
            if (i == 0) {
                for (VehicleMarker m : MapMarkers.markers()) {
                    if (own(m) && dim.equals(m.dimension())) MapMarkers.addSelected(m);
                }
            } else {
                for (RibbonLayout.Unit u : layout.units()) select(u, true);
            }
            return true;
        }
        if (overflow) {
            int step = Math.max(CELL, (listX1 - listX0) / 3);
            if (mx < listX0) {
                offset = Math.max(0, offset - step);
                return true;
            }
            if (mx >= listX1) {
                offset = Math.min(maxOffset(), offset + step);
                return true;
            }
        }
        RibbonLayout.Span span = spanAt(mx, my);
        if (span != null) {
            if (!shift) MapMarkers.clearSelection();
            for (RibbonLayout.Unit u : RibbonLayout.members(layout, span)) select(u, true);
            return true;
        }
        int i = cellAt(mx, my);
        if (i >= 0) {
            RibbonLayout.Unit u = layout.units().get(i);
            VehicleMarker m = BY_VEHICLE.get(u.vehicleId());
            if (m == null) return true;
            long now = System.currentTimeMillis();
            if (u.vehicleId() == lastClickVehicle && now - lastClickAt < DOUBLE_CLICK_MS) {
                jump = m;
                lastClickVehicle = -1;
                return true;
            }
            lastClickVehicle = u.vehicleId();
            lastClickAt = now;
            if (shift) {
                MapMarkers.toggleSelected(m);
            } else {
                MapMarkers.clearSelection();
                MapMarkers.addSelected(m);
            }
        }
        return true;
    }

    public static boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my)) return false;
        if (overflow) offset = Math.max(0, Math.min(maxOffset(), offset - (int) Math.signum(delta) * CELL * 2));
        return true;
    }

    /** Whether the cursor is over a unit cell (legend context). */
    public static boolean hoveringUnit(double mx, double my) {
        return shown && cellAt(mx, my) >= 0;
    }

    // ---- building ------------------------------------------------------------------------------------

    private static void refresh(View v) {
        long now = System.currentTimeMillis();
        double spanX = Math.max(1.0, v.maxX() - v.minX());
        boolean moved = Math.abs(v.camX() - lastCamX) > spanX * 0.25 || Math.abs(v.camZ() - lastCamZ) > spanX * 0.25
                || lastScale == 0.0 || Math.abs(v.scale() / lastScale - 1.0) > 0.10;
        if (!moved && now - refreshedAt < REFRESH_MS) return;
        refreshedAt = now;
        lastCamX = v.camX();
        lastCamZ = v.camZ();
        lastScale = v.scale();

        BY_VEHICLE.clear();
        List<RibbonLayout.Unit> units = new ArrayList<>();
        for (VehicleMarker m : MapMarkers.markers()) {
            if (!own(m) || !v.dim().equals(m.dimension())) continue;
            BY_VEHICLE.put(m.vehicleId(), m);
            boolean inView = m.x() >= v.minX() - VIEW_MARGIN && m.x() <= v.maxX() + VIEW_MARGIN
                    && m.z() >= v.minZ() - VIEW_MARGIN && m.z() <= v.maxZ() + VIEW_MARGIN;
            MarkerTags t = m.tags();
            boolean ordered = !t.stance().idle() || m.order().type() != MarkerOrder.Type.NONE;
            units.add(new RibbonLayout.Unit(m.vehicleId(), m.driverId(), t.platoonId(), t.formationGroup(),
                    ordered ? t.orderBatch() : 0L, m.kind().ordinal(), MapMarkers.isSelected(m), inView));
        }
        layout = RibbonLayout.build(units);
    }

    private static boolean own(VehicleMarker m) {
        return m.allegiance() == VehicleMarker.Allegiance.OWN && m.driverId() != MapMarkers.NO_DRIVER;
    }

    private static void select(RibbonLayout.Unit u, boolean add) {
        VehicleMarker m = BY_VEHICLE.get(u.vehicleId());
        if (m != null && add) MapMarkers.addSelected(m);
    }

    // ---- geometry ------------------------------------------------------------------------------------

    /** Cell x before paging: groups (outermost lane present) get a gap, idle units a wider one. */
    private static int[] rawPositions() {
        List<RibbonLayout.Unit> units = layout.units();
        int[] xs = new int[units.size()];
        int x = 0;
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) {
                RibbonLayout.Unit a = units.get(i - 1);
                RibbonLayout.Unit b = units.get(i);
                if (i == layout.idleStart()) x += IDLE_GAP;
                else if (a.platoonId() != b.platoonId() || a.formation() != b.formation() || a.order() != b.order()) {
                    x += GROUP_GAP;
                }
            }
            xs[i] = x;
            x += CELL;
        }
        return xs;
    }

    private static int contentWidth() {
        int[] xs = rawPositions();
        return xs.length == 0 ? 0 : xs[xs.length - 1] + CELL;
    }

    private static int maxOffset() {
        return Math.max(0, contentWidth() - (listX1 - listX0));
    }

    private static int[] cellPositions() {
        int[] xs = rawPositions();
        int base = listX0 - offset;
        if (!overflow) base = listX0 + Math.max(0, ((listX1 - listX0) - contentWidth()) / 2);
        for (int i = 0; i < xs.length; i++) xs[i] += base;
        return xs;
    }

    private static int cellAt(double mx, double my) {
        if (!shown || mx < listX0 || mx >= listX1) return -1;
        int iconY = y0 + PAD + laneRows * LANE_H + 1;
        if (my < iconY - 1 || my >= iconY + ICON + 3) return -1;
        for (int i = 0; i < cellX.length; i++) {
            if (mx >= cellX[i] && mx < cellX[i] + CELL) return i;
        }
        return -1;
    }

    @Nullable
    private static RibbonLayout.Span spanAt(double mx, double my) {
        if (!shown || mx < listX0 || mx >= listX1) return null;
        for (RibbonLayout.Span s : layout.spans()) {
            int ly = y0 + PAD + LANE_ROW[s.lane().ordinal()] * LANE_H;
            if (my < ly || my >= ly + LANE_H) continue;
            if (mx >= cellX[s.first()] + 2 && mx < cellX[s.last()] + CELL - 2) return s;
        }
        return null;
    }

    private static void drawBrackets(GuiGraphics g, int mx, int my) {
        RibbonLayout.Span hot = spanAt(mx, my);
        for (RibbonLayout.Span s : layout.spans()) {
            int ly = laneLine(s);
            int a = cellX[s.first()] + 2;
            int b = cellX[s.last()] + CELL - 2;
            int colour = spanColour(s, hot);
            int c = (a + b) / 2;
            int gapL = c - LANE_ICON / 2 - 2;
            int gapR = c + LANE_ICON / 2 + 3;
            int solidA = s.openLeft() ? a + 6 : a;
            int solidB = s.openRight() ? b - 6 : b;
            segment(g, solidA, Math.min(solidB, gapL), ly, colour);
            segment(g, Math.max(solidA, gapR), solidB, ly, colour);
            if (s.openLeft()) {
                for (int x = a; x < a + 6; x += 2) g.fill(x, ly, x + 1, ly + 1, colour);
            } else {
                g.fill(a, ly, a + 1, ly + 3, colour);
            }
            if (s.openRight()) {
                for (int x = b - 5; x < b; x += 2) g.fill(x, ly, x + 1, ly + 1, colour);
            } else {
                g.fill(b - 1, ly, b, ly + 3, colour);
            }
        }
    }

    /** One tinted type icon per bracket segment, in the gap left at its centre. */
    private static void drawBracketIcons(GuiGraphics g, int mx, int my) {
        RibbonLayout.Span hot = spanAt(mx, my);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        for (RibbonLayout.Span s : layout.spans()) {
            int c = (cellX[s.first()] + 2 + cellX[s.last()] + CELL - 2) / 2;
            int ly = laneLine(s);
            int colour = spanColour(s, hot);
            g.setColor((colour >> 16 & 0xFF) / 255f, (colour >> 8 & 0xFF) / 255f, (colour & 0xFF) / 255f, 1f);
            g.blit(LANE_ICONS[s.lane().ordinal()], c - LANE_ICON / 2, ly - LANE_ICON / 2, LANE_ICON, LANE_ICON,
                    0f, 0f, 16, 16, 16, 16);
        }
        g.setColor(1f, 1f, 1f, 1f);
    }

    private static void segment(GuiGraphics g, int a, int b, int y, int colour) {
        if (b > a) g.fill(a, y, b, y + 1, colour);
    }

    private static int laneLine(RibbonLayout.Span s) {
        return y0 + PAD + LANE_ROW[s.lane().ordinal()] * LANE_H + LANE_H / 2;
    }

    private static int spanColour(RibbonLayout.Span s, @Nullable RibbonLayout.Span hot) {
        if (hot != null && hot.lane() == s.lane() && hot.key() == s.key()) return XaeroTheme.TRIM_OPEN;
        return laneColour(s);
    }

    private static int laneColour(RibbonLayout.Span s) {
        if (s.lane() == RibbonLayout.Lane.PLATOON) {
            VehicleMarker m = BY_VEHICLE.get(layout.units().get(s.first()).vehicleId());
            int rgb = m != null ? m.platoonColorRgb() : 0;
            return rgb != 0 ? 0xFF000000 | rgb : XaeroTheme.DIM;
        }
        return LANE_COLOUR_DEFAULT[s.lane().ordinal()];
    }

    // ---- tooltip -------------------------------------------------------------------------------------

    private static void tooltip(GuiGraphics g, Font font, int mx, int my, int sw, int sh) {
        List<String> lines = new ArrayList<>();
        RibbonLayout.Span span = spanAt(mx, my);
        if (span != null) {
            String lane = I18n.get("gui.tacz_sewv.map.ribbon.lane." + span.lane().name().toLowerCase(Locale.ROOT));
            lines.add(lane + " " + span.index());
            lines.add(I18n.get("gui.tacz_sewv.map.ribbon.units", RibbonLayout.members(layout, span).size()));
        } else {
            int i = cellAt(mx, my);
            if (i < 0) return;
            VehicleMarker m = BY_VEHICLE.get(layout.units().get(i).vehicleId());
            if (m == null) return;
            lines.add(I18n.get("gui.tacz_sewv.map.kind." + m.kind().name().toLowerCase(Locale.ROOT)));
            lines.add(I18n.get("gui.tacz_sewv.map.ribbon.health", Math.round(m.healthFrac() * 100)));
            lines.add(I18n.get("gui.tacz_sewv.map.stance." + m.tags().stance().name().toLowerCase(Locale.ROOT)));
            if (m.tags().platoonId() != 0) lines.add(I18n.get("gui.tacz_sewv.map.ribbon.platoon", m.tags().platoonId()));
            if (m.tags().formationGroup() != 0L) lines.add(I18n.get("gui.tacz_sewv.map.ribbon.in_formation"));
        }
        XaeroTheme.tooltip(g, font, lines, mx, my, sw, sh);
    }

    private static boolean inBox(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
