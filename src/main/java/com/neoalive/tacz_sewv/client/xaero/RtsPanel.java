package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.territory.RtsKeybind;
import com.neoalive.tacz_sewv.client.territory.TerritoryClient;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketTerritoryCommand;
import com.neoalive.tacz_sewv.network.PacketTerritoryState.Row;

/**
 * The RTS panel on the world map: a collapsible right-edge panel with the Territory Mode toggle, the Frontline
 * Tool, the roster and a keybind drawer. It is drawn and hit-tested by hand (this repo's screens use no vanilla
 * list widgets) and holds nothing the server does not own — every button just sends a
 * {@link PacketTerritoryCommand} and the next {@code PacketTerritoryState} redraws the truth.
 *
 * <p>Deliberately Xaero-free: {@code MixinGuiMap} owns the hooks and hands in the screen size, the map projection
 * and the hovered block; the panel only draws and decides. Static state, reset each time the map opens (the spec's
 * default is collapsed) and released on close, so the server stops pushing state nobody is looking at.
 *
 * <p>Geometry is one {@link Layout} computed per call, shared by drawing and hit-testing, so what you see is
 * exactly what you can click. The panel is flush with the right edge, stepping in past Xaero's own button column
 * only where the two would overlap (see {@link #setRightColumn}).
 */
public final class RtsPanel {

    private static final int PANEL_W = 200;
    private static final int RAIL_W = 16;
    /** Air between the panel and Xaero's button column when they have to share the edge. */
    private static final int COLUMN_GAP = 2;
    private static final int ROW_H = 12;
    private static final int HELP_ENTRY_H = 22;
    private static final int HELP_ENTRIES = 4;

    private static final int BG = 0xD0121820;
    private static final int BORDER = 0xFF3A4A5A;
    private static final int ACCENT = 0xFF55DD55;
    private static final int AMBER = 0xFFFFAA00;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF8A94A0;
    private static final int SELECT_BG = 0x4055AAFF;

    private static boolean expanded;
    private static boolean helpOpen;
    private static boolean hideIneligible;
    private static boolean toolArmed;
    private static int scroll;
    private static int helpScroll;
    /** A press was ours, so its release must not reach Xaero (which would treat it as a map click). */
    private static boolean swallowRelease;
    /** The map is open. init() re-runs on a window resize and must not collapse the panel or drop the tool. */
    private static boolean open;

    private static List<Row> sortedSource;
    private static List<Row> sorted = List.of();

    private RtsPanel() {}

    /** The map's world-to-screen projection, supplied by the mixin (which owns the camera). */
    @FunctionalInterface
    public interface Project {
        int[] toScreen(double worldX, double worldZ);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    public static boolean enabled() {
        return RtsKeybind.available();
    }

    /** A fresh map: collapsed by default (spec 3.2), no tool armed. */
    public static void onOpened() {
        if (open) return;
        open = true;
        expanded = false;
        toolArmed = false;
        scroll = 0;
        helpScroll = 0;
        swallowRelease = false;
    }

    /** Map closed: tell the server nobody is looking, so it can stop pushing state. */
    public static void onClosed() {
        if (expanded && enabled()) send(PacketTerritoryCommand.panelOpen(false));
        open = false;
        expanded = false;
        toolArmed = false;
        swallowRelease = false;
    }

    private static void toggle() {
        expanded = !expanded;
        if (!expanded) toolArmed = false;
        send(PacketTerritoryCommand.panelOpen(expanded));
    }

    private static void send(PacketTerritoryCommand cmd) {
        NetworkHandler.CHANNEL.sendToServer(cmd);
    }

    // ---- state the tool depends on ------------------------------------------------------------------------

    private static Set<Integer> selection() {
        return MapMarkers.selected();
    }

    private static List<Integer> eligibleSelected() {
        Set<Integer> selected = selection();
        List<Integer> out = new ArrayList<>();
        for (Row r : TerritoryClient.roster()) {
            if (r.status() == com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_OK && selected.contains(r.id())) {
                out.add(r.id());
            }
        }
        return out;
    }

    private static boolean toolEnabled() {
        return TerritoryClient.modeOn() && !eligibleSelected().isEmpty();
    }

    public static boolean toolArmed() {
        return toolArmed;
    }

    public static void cancelTool() {
        toolArmed = false;
    }

    private static void armTool() {
        if (!toolEnabled()) {
            hint(TerritoryClient.modeOn() ? "gui.tacz_sewv.rts.need_selection" : "gui.tacz_sewv.rts.need_mode");
            return;
        }
        CruisePlot.cancel();
        GuardPlot.cancel();
        PathwayPlot.cancel();
        toolArmed = true;
    }

    /** A click while the tool is armed: left fires at the chunk, right cancels, anything else is swallowed. */
    public static void toolClick(int button, int chunkX, int chunkZ) {
        swallowRelease = true;
        if (button == 0) fire(chunkX, chunkZ);
        else if (button == 1) toolArmed = false;
    }

    /** The armed tool's left-click: the clicked chunk is the origin. One-shot — the tool disarms. */
    public static void fire(int chunkX, int chunkZ) {
        List<Integer> ids = eligibleSelected();
        toolArmed = false;
        if (ids.isEmpty()) {
            hint("gui.tacz_sewv.rts.need_selection");
            return;
        }
        send(PacketTerritoryCommand.frontline(chunkX, chunkZ, ids));
    }

    private static void hint(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key), true);
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private record Layout(int x0, int y0, int x1, int y1, int headerY, int modeY, int toolY, int reasonY,
                          int rosterY, int listTop, int listBottom, int helpY, int helpBodyH) {
        boolean inside(double mx, double my) {
            return mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }

        int visibleRows() {
            return Math.max(0, (listBottom - listTop) / ROW_H);
        }
    }

    /**
     * Xaero's right-edge button column, as the mixin measured it this frame: the topmost visible right-aligned
     * widget's Y and the column's width. The panel sits flush with the screen edge unless it would overlap that
     * column vertically, and only then steps in by exactly the column's width. (No column found = flush.)
     */
    private static int columnTop = Integer.MAX_VALUE;
    private static int columnW;

    public static void setRightColumn(int top, int width) {
        columnTop = top;
        columnW = width;
    }

    private static int rightEdge(int w, int panelBottom) {
        return panelBottom > columnTop ? w - columnW - COLUMN_GAP : w;
    }

    private static Layout layout(int w, int h) {
        if (!expanded) {
            int railH = 72;
            int y0 = (h - railH) / 2;
            int x1 = rightEdge(w, y0 + railH);
            return new Layout(x1 - RAIL_W, y0, x1, y0 + railH, y0, y0, y0, y0, y0, y0, y0, y0, 0);
        }
        int panelH = Mth.clamp(h - 60, 190, 340);
        int y0 = (h - panelH) / 2;
        int y1 = y0 + panelH;
        int x1 = rightEdge(w, y1);
        int listTop = y0 + 84;
        int helpBody = helpOpen ? Math.min(HELP_ENTRIES * HELP_ENTRY_H, Math.max(0, panelH - 84 - 12 - 4 - 3 * ROW_H)) : 0;
        int helpY = y1 - 12 - helpBody - 2;
        return new Layout(x1 - PANEL_W, y0, x1, y1, y0 + 2, y0 + 18, y0 + 38, y0 + 56, y0 + 70,
                listTop, helpY - 2, helpY, helpBody);
    }

    private static List<Row> visibleRows() {
        List<Row> roster = TerritoryClient.roster();
        if (roster != sortedSource) {
            sortedSource = roster;
            List<Row> copy = new ArrayList<>(roster);
            copy.sort(Comparator.comparing((Row r) -> r.name().toLowerCase()).thenComparingInt(Row::id));
            sorted = copy;
        }
        if (!hideIneligible) return sorted;
        List<Row> out = new ArrayList<>(sorted.size());
        for (Row r : sorted) {
            if (r.status() == com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_OK || r.posted()) out.add(r);
        }
        return out;
    }

    private static int maxScroll(Layout l, int rowCount) {
        return Math.max(0, rowCount - l.visibleRows());
    }

    // ---- input --------------------------------------------------------------------------------------------

    /**
     * A press on the map. Returns true when the panel consumed it (the mixin then swallows the press, and
     * {@link #consumeRelease} swallows its release).
     */
    public static boolean mouseClicked(double mx, double my, int button, int w, int h) {
        if (!enabled()) return false;
        Layout l = layout(w, h);
        if (!l.inside(mx, my)) return false;
        swallowRelease = true;
        if (button != 0) return true;

        if (!expanded) {
            toggle();
            return true;
        }
        if (my < l.modeY - 1) { // header: the collapse arrow at the right
            if (mx >= l.x1 - 18) toggle();
            return true;
        }
        if (my >= l.modeY && my < l.modeY + 16) {
            send(PacketTerritoryCommand.setMode(!TerritoryClient.modeOn()));
            return true;
        }
        if (my >= l.toolY && my < l.toolY + 16) {
            if (toolArmed) toolArmed = false;
            else armTool();
            return true;
        }
        if (my >= l.rosterY && my < l.rosterY + ROW_H) {
            if (mx >= l.x0 + PANEL_W / 2) {
                hideIneligible = !hideIneligible;
                scroll = 0;
            }
            return true;
        }
        if (my >= l.listTop && my < l.listBottom) {
            List<Row> rows = visibleRows();
            int index = scroll + (int) ((my - l.listTop) / ROW_H);
            if (index >= 0 && index < rows.size() && (my - l.listTop) / ROW_H < l.visibleRows()) {
                clickRow(rows.get(index), mx >= l.x1 - 14, l);
            }
            return true;
        }
        if (my >= l.helpY && my < l.helpY + 12) {
            helpOpen = !helpOpen;
            helpScroll = 0;
        }
        return true;
    }

    private static void clickRow(Row row, boolean eject, Layout l) {
        if (eject && row.posted()) {
            send(PacketTerritoryCommand.release(row.id()));
            return; // the unit stays selected (spec 5.3)
        }
        VehicleMarker marker = MapMarkers.markerForDriver(row.id());
        if (marker == null) {
            hint("gui.tacz_sewv.rts.no_marker");
            return;
        }
        if (Screen.hasShiftDown()) {
            MapMarkers.toggleSelected(marker);
        } else {
            MapMarkers.clearSelection();
            MapMarkers.addSelected(marker);
        }
    }

    /** True exactly once after a press the panel consumed: the release is ours too. */
    public static boolean consumeRelease() {
        boolean was = swallowRelease;
        swallowRelease = false;
        return was;
    }

    public static boolean mouseScrolled(double mx, double my, double delta, int w, int h) {
        if (!enabled()) return false;
        Layout l = layout(w, h);
        if (!l.inside(mx, my)) return false;
        if (!expanded) return true;
        int step = delta > 0 ? -1 : 1;
        if (my >= l.listTop && my < l.listBottom) {
            scroll = Mth.clamp(scroll + step * 2, 0, maxScroll(l, visibleRows().size()));
        } else if (helpOpen && my >= l.helpY + 12) {
            helpScroll = Mth.clamp(helpScroll + step, 0, Math.max(0, HELP_ENTRIES - Math.max(1, l.helpBodyH / HELP_ENTRY_H)));
        }
        return true; // never let the wheel zoom the map underneath the panel
    }

    /** A key on the map screen. Returns true when it was ours. */
    public static boolean keyPressed(int keyCode, int scanCode) {
        if (!enabled()) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && toolArmed) {
            toolArmed = false; // Esc cancels the tool first; the next Esc closes the map
            return true;
        }
        if (RtsKeybind.Keys.TOGGLE_PANEL.matches(keyCode, scanCode)) {
            toggle();
            return true;
        }
        if (RtsKeybind.Keys.FRONTLINE_TOOL.matches(keyCode, scanCode)) {
            if (!expanded) toggle();
            if (toolArmed) toolArmed = false;
            else armTool();
            return true;
        }
        return false;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /** Front, coverage, leashes of selected posted units, and the chunk under the cursor while the tool is armed. */
    public static void drawOverlay(GuiGraphics g, Project p, int w, int h, int hoverBlockX, int hoverBlockZ) {
        if (!enabled() || !TerritoryClient.modeOn()) return;
        long[] front = TerritoryClient.front();
        int[] cover = TerritoryClient.coverage();
        for (int i = 0; i < front.length; i++) {
            Chunk c = FrontlineMath.unpack(front[i]);
            boolean covered = cover[i] > 0;
            box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1,
                    covered ? 0x3355DD55 : 0x66FFAA00, covered ? ACCENT : AMBER);
        }
        Set<Integer> selected = selection();
        for (Row r : TerritoryClient.roster()) {
            if (!r.posted()) continue;
            if (selected.contains(r.id())) {
                box(g, p, w, h, r.chunkX() - 1, r.chunkZ() - 1, r.chunkX() + 2, r.chunkZ() + 2, 0x2266AAFF, 0xAA66AAFF);
            }
            if (r.lost()) box(g, p, w, h, r.chunkX(), r.chunkZ(), r.chunkX() + 1, r.chunkZ() + 1, 0x33FF4444, 0xFFFF4444);
        }
        if (toolArmed) {
            int cx = hoverBlockX >> 4, cz = hoverBlockZ >> 4;
            box(g, p, w, h, cx, cz, cx + 1, cz + 1, 0x5566CCFF, 0xFF66CCFF);
        }
    }

    /** Chunk-aligned rectangle [cx0,cx1) x [cz0,cz1) in chunks: translucent fill plus a 1 px border. */
    private static void box(GuiGraphics g, Project p, int w, int h, int cx0, int cz0, int cx1, int cz1,
                            int fill, int border) {
        int[] a = p.toScreen(cx0 * 16.0, cz0 * 16.0);
        int[] b = p.toScreen(cx1 * 16.0, cz1 * 16.0);
        int x0 = Math.min(a[0], b[0]), y0 = Math.min(a[1], b[1]);
        int x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
        if (x1 <= x0 || y1 <= y0 || x1 < 0 || y1 < 0 || x0 > w || y0 > h) return;
        g.fill(x0, y0, x1, y1, fill);
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
    }

    public static void render(GuiGraphics g, Font font, int w, int h, int mx, int my) {
        if (!enabled()) return;
        Layout l = layout(w, h);
        g.fill(l.x0, l.y0, l.x1, l.y1, BG);
        g.fill(l.x0, l.y0, l.x1, l.y0 + 1, BORDER);
        g.fill(l.x0, l.y1 - 1, l.x1, l.y1, BORDER);
        g.fill(l.x0, l.y0, l.x0 + 1, l.y1, BORDER);
        g.fill(l.x1 - 1, l.y0, l.x1, l.y1, BORDER);

        if (!expanded) {
            g.drawCenteredString(font, "<", l.x0 + RAIL_W / 2, l.y0 + 6, TerritoryClient.modeOn() ? ACCENT : TEXT);
            String label = Component.translatable("gui.tacz_sewv.rts.rail").getString();
            for (int i = 0; i < label.length(); i++) {
                g.drawCenteredString(font, String.valueOf(label.charAt(i)), l.x0 + RAIL_W / 2, l.y0 + 20 + i * 10, DIM);
            }
            return;
        }

        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.title"), l.x0 + 6, l.headerY + 2, TEXT, false);
        g.drawString(font, ">", l.x1 - 12, l.headerY + 2, DIM, false);

        // Territory Mode toggle.
        boolean on = TerritoryClient.modeOn();
        g.fill(l.x0 + 4, l.modeY, l.x1 - 4, l.modeY + 16, on ? 0x6032803C : 0x40303840);
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.mode"), l.x0 + 8, l.modeY + 4, TEXT, false);
        Component state = Component.translatable(on ? "gui.tacz_sewv.rts.on" : "gui.tacz_sewv.rts.off");
        g.drawString(font, state, l.x1 - 8 - font.width(state), l.modeY + 4, on ? ACCENT : DIM, false);

        // Frontline Tool, directly below the toggle.
        boolean enabled = toolEnabled();
        int toolFill = toolArmed ? 0x8066AAFF : (enabled ? 0x60305880 : 0x30202830);
        g.fill(l.x0 + 4, l.toolY, l.x1 - 4, l.toolY + 16, toolFill);
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.tool"), l.x0 + 8, l.toolY + 4,
                enabled ? TEXT : DIM, false);
        if (!enabled) {
            Component reason = Component.translatable(on ? "gui.tacz_sewv.rts.need_selection" : "gui.tacz_sewv.rts.need_mode");
            g.drawString(font, reason, l.x0 + 8, l.reasonY, DIM, false);
        } else if (toolArmed) {
            g.drawString(font, Component.translatable("gui.tacz_sewv.rts.armed"), l.x0 + 8, l.reasonY, ACCENT, false);
        }

        // Roster header + filter toggle.
        List<Row> rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll(l, rows.size()));
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.roster", TerritoryClient.roster().size()),
                l.x0 + 6, l.rosterY + 2, TEXT, false);
        Component filter = Component.translatable("gui.tacz_sewv.rts.filter");
        int fx = l.x1 - 8 - font.width(filter) - 10;
        g.fill(fx, l.rosterY + 2, fx + 8, l.rosterY + 10, 0xFF3A4A5A);
        if (hideIneligible) g.fill(fx + 2, l.rosterY + 4, fx + 6, l.rosterY + 8, ACCENT);
        g.drawString(font, filter, fx + 12, l.rosterY + 2, DIM, false);

        // Roster rows.
        Set<Integer> selected = selection();
        int shown = l.visibleRows();
        for (int i = 0; i < shown && scroll + i < rows.size(); i++) {
            drawRow(g, font, rows.get(scroll + i), l, l.listTop + i * ROW_H, selected, mx, my);
        }
        if (rows.size() > shown) {
            int trackH = l.listBottom - l.listTop;
            int thumbH = Math.max(6, trackH * shown / rows.size());
            int thumbY = l.listTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll(l, rows.size()));
            g.fill(l.x1 - 3, thumbY, l.x1 - 1, thumbY + thumbH, BORDER);
        }

        // Keybind drawer.
        g.fill(l.x0 + 4, l.helpY, l.x1 - 4, l.helpY + 12, 0x30202830);
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.keys"), l.x0 + 8, l.helpY + 2, DIM, false);
        g.drawString(font, helpOpen ? "v" : "^", l.x1 - 14, l.helpY + 2, DIM, false);
        if (helpOpen) drawHelp(g, font, l);

        if (toolArmed) {
            g.drawCenteredString(font, Component.translatable("gui.tacz_sewv.rts.tool_hint"), w / 2, h - 42, 0xFFFFFFFF);
        }
        if (mx >= l.x0 + 4 && mx < l.x1 - 4 && my >= l.toolY && my < l.toolY + 16) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("gui.tacz_sewv.rts.tool_tip1"),
                    Component.translatable("gui.tacz_sewv.rts.tool_tip2")), mx, my);
        }
    }

    private static void drawRow(GuiGraphics g, Font font, Row r, Layout l, int y, Set<Integer> selected, int mx, int my) {
        if (selected.contains(r.id())) g.fill(l.x0 + 3, y, l.x1 - 5, y + ROW_H - 1, SELECT_BG);
        // Unit type: a foot soldier is a small square, a driver of a hull a wider one.
        g.fill(l.x0 + 6, y + 3, l.x0 + (r.kind() == 1 ? 14 : 10), y + 8, r.kind() == 1 ? 0xFF6FA8FF : 0xFFCCCCCC);
        int nameX = l.x0 + 18;
        g.drawString(font, font.plainSubstrByWidth(r.name(), 74), nameX, y + 2, r.posted() ? TEXT : (r.status() == 0 ? TEXT : DIM), false);

        // Health bar.
        int hx = nameX + 78;
        g.fill(hx, y + 4, hx + 24, y + 7, 0xFF303030);
        int hw = Math.round(24 * Mth.clamp(r.health(), 0f, 1f));
        g.fill(hx, y + 4, hx + hw, y + 7, r.health() > 0.5f ? 0xFF55DD55 : (r.health() > 0.25f ? AMBER : 0xFFFF4444));

        // Chip: TERRITORY when posted, otherwise why the unit cannot be.
        String chip = r.posted() ? "gui.tacz_sewv.rts.chip.territory" : chipKey(r.status());
        int chipX = hx + 28;
        if (chip != null) {
            int color = r.posted() ? (r.lost() ? 0xFFFF4444 : ACCENT) : AMBER;
            g.drawString(font, Component.translatable(chip), chipX, y + 2, color, false);
        }
        if (r.posted()) {
            boolean hot = mx >= l.x1 - 14 && mx < l.x1 - 5 && my >= y && my < y + ROW_H;
            g.drawString(font, "x", l.x1 - 12, y + 2, hot ? 0xFFFF6666 : DIM, false);
        }
    }

    private static String chipKey(byte status) {
        return switch (status) {
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_FOB -> "gui.tacz_sewv.rts.chip.fob";
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_AIR -> "gui.tacz_sewv.rts.chip.air";
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_CREW -> "gui.tacz_sewv.rts.chip.crew";
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_DOWNED -> "gui.tacz_sewv.rts.chip.downed";
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_SWEEP -> "gui.tacz_sewv.rts.chip.sweep";
            default -> null;
        };
    }

    /** Keybind rows only (spec 3.4): glyph, action name, one-line description. Reference, not a command surface. */
    private static void drawHelp(GuiGraphics g, Font font, Layout l) {
        String[] glyph = {
                RtsKeybind.Keys.TOGGLE_PANEL.getTranslatedKeyMessage().getString(),
                RtsKeybind.Keys.FRONTLINE_TOOL.getTranslatedKeyMessage().getString(),
                "RMB / Esc",
                "Shift+Click"};
        String[] name = {"toggle", "frontline", "cancel", "add"};
        int y = l.helpY + 14;
        int first = helpScroll;
        int fit = Math.max(1, l.helpBodyH / HELP_ENTRY_H);
        for (int i = first; i < HELP_ENTRIES && i < first + fit; i++, y += HELP_ENTRY_H) {
            g.drawString(font, "[" + glyph[i] + "]", l.x0 + 8, y, ACCENT, false);
            int gx = l.x0 + 8 + font.width("[" + glyph[i] + "]") + 4;
            g.drawString(font, Component.translatable("gui.tacz_sewv.rts.help." + name[i]), gx, y, TEXT, false);
            g.drawString(font, font.plainSubstrByWidth(
                    Component.translatable("gui.tacz_sewv.rts.help." + name[i] + ".desc").getString(), PANEL_W - 16),
                    l.x0 + 8, y + 10, DIM, false);
        }
    }
}
