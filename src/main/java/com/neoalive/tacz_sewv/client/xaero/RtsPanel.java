package com.neoalive.tacz_sewv.client.xaero;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.territory.RtsKeybind;
import com.neoalive.tacz_sewv.client.territory.TerritoryClient;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketTerritoryCommand;
import com.neoalive.tacz_sewv.network.PacketTerritoryState.PlanView;
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

    private static final int PANEL_W = 240;
    private static final int RAIL_W = 16;
    /** Air between the panel and Xaero's button column when they have to share the edge. */
    private static final int COLUMN_GAP = 2;
    /** Every row (tool rows, roster header, roster rows, keybind header) is 20 px: 16 px sprite + 2 px above/below. */
    private static final int ROW_H = 20;
    private static final int SPRITE = 16;
    private static final int HELP_ENTRY_H = 22;
    private static final int HELP_ENTRIES = 6;
    /** Roster header sits this far below the panel top: header, mode, two tool rows, and the one status line. */
    private static final int LIST_TOP = 114;
    /** Advance Plan Start/Stop button width, and PlanView.state's RUNNING value. */
    private static final int PLAN_BTN_W = 44;
    private static final int PLAN_RUNNING = 1;
    /** The ">>" skip-layer button: its width, and how long right-click must be held to fire it. */
    private static final int SKIP_BTN_W = 20;
    private static final long SKIP_HOLD_MS = 1000L;
    private static final int SKIP_TRACK = 0xFF1B2128;
    private static final int SKIP_FILL = 0xFF5CE6A8;
    private static final String[] HOLD_KEYS = {"", "hostiles", "not_loaded", "limit", "not_claimable", "retry", "arriving"};
    /** Overlay fade-in: front chunks appearing, a chunk becoming covered, and the manual line appearing. */
    private static final long FADE_MS = 450L;
    /** A drag can capture at most this many chunks (the wire cap); past it further chunks are ignored. */
    private static final int MAX_CAPTURED = 1024;

    private static final int BG = 0xD0121820;
    private static final int BORDER = 0xFF3A4A5A;
    private static final int ACCENT = 0xFF55DD55;
    private static final int AMBER = 0xFFFFAA00;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int DIM = 0xFF8A94A0;
    private static final int SELECT_BG = 0x4055AAFF;
    /** Row backgrounds: neutral when idle, a subtle amber tint when the row's tool is armed. Amber means "attention". */
    private static final int ROW_BG = 0x30303840;
    private static final int ARMED_BG = 0x50E5A045;
    private static final float DISABLED_ALPHA = 0.4f;

    // Roster row columns (all rows share them, so bars and chips line up down the list).
    private static final int NAME_W = 88;
    private static final int BAR_W = 40;
    private static final int BAR_H = 4;
    private static final int BAR_TRACK = 0xFF262A30;
    /** Health reads as a value: green above 66%, yellow above 33%, red at or below. */
    private static final int HEALTH_GREEN = 0xFF55DD55;
    private static final int HEALTH_YELLOW = 0xFFE6D34A;
    private static final int HEALTH_RED = 0xFFC44536;
    private static final ResourceLocation ICON_INFANTRY = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_unit_infantry.png");
    private static final ResourceLocation ICON_VEHICLE = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_unit_vehicle.png");
    private static final ResourceLocation ICON_AIR = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_unit_air.png");
    private static final ResourceLocation ICON_MODE = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_mode.png");
    private static final ResourceLocation ICON_TOOL = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_frontline.png");
    private static final ResourceLocation ICON_ADVANCE = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_advance.png");
    private static final ResourceLocation ICON_ROSTER = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_roster.png");
    private static final ResourceLocation ICON_KEYS = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_keys.png");
    /** Vanilla 1.20.1 checkbox sheet: 64x64, 20x20 cells; the second row (v = 20) is the checked state. */
    private static final ResourceLocation CHECKBOX = new ResourceLocation("textures/gui/checkbox.png");

    // Map overlay palette. Amber is the persistent "this is held" colour; red and white are interaction-time only.
    private static final int LINE_AMBER = 0xFFE5A045;
    private static final int UNCOVERED_RGB = 0xC44536;
    private static final int LEASH_RGB = 0x4A7BA8;
    /** Advance Plan region and arrows: a cool green, distinct from the amber Frontline marks and the blue leash. */
    private static final int ADVANCE_RGB = 0x3FBF8F;
    /** 20% (was 15%, nudged up after the first look): motion carries a low-alpha mark, so raise this if it is faint, never slow the march. */
    private static final int LEASH_ALPHA = 0x33;
    private static final ResourceLocation FRONT_ICON = new ResourceLocation(TaczSewv.MODID, "textures/gui/rts_frontline.png");
    private static final int ICON_NATIVE = 16;
    /** On-screen chunk size (px) at which the 16 px icon still fits, and below which the 8 px half-scale is used. */
    private static final int ICON_FULL_MIN_CHUNK_PX = 28;
    private static final int ICON_HALF_MIN_CHUNK_PX = 10;
    /** Dash and gap (px) of the manual ordering line. */
    private static final int DASH_ON = 6;
    private static final int DASH_OFF = 5;

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

    // Manual mode: a right-drag across front chunks. Transient client state; the server owns the result.
    private static boolean dragging;
    private static double dragLastX;
    private static double dragLastZ;
    private static final LinkedHashSet<Long> captured = new LinkedHashSet<>();
    private static long[] frontSetSource;
    private static Set<Long> frontSet = Set.of();

    // Advance Plan painting: left-drag adds chunks, right-drag erases, Confirm bakes. Transient client state.
    private static boolean planPainting;
    private static final LinkedHashSet<Long> painted = new LinkedHashSet<>();
    /** The button held on a paint stroke (0 paint, 1 erase), or -1 between strokes. */
    private static int paintButton = -1;
    private static double paintLastX;
    private static double paintLastZ;
    /** Confirm was pressed: painting mode closes by itself once the server's plan matches, and stays open on a refusal. */
    private static boolean planSent;
    /** Wall-clock start of a right-click hold on the skip button, or -1. It fires only if it runs to the end on the button. */
    private static long skipHoldStart = -1;

    // Fade-in bookkeeping, keyed by packed chunk. Rebuilt only when a state push swaps the arrays.
    private static long[] fadeFront;
    private static int[] fadeCover;
    private static long[] fadeLine;
    private static final Map<Long, Long> appeared = new HashMap<>();
    private static final Map<Long, Boolean> covered = new HashMap<>();
    private static final Map<Long, Long> coveredAt = new HashMap<>();
    private static final Map<Long, Long> lineAppeared = new HashMap<>();
    /** Per-frame scratch: covered front chunk -> its fade-in alpha. Cleared each draw; never read across frames. */
    private static final Map<Long, Float> coveredAlpha = new HashMap<>();

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
        resetTransient();
    }

    /** Map closed: tell the server nobody is looking, so it can stop pushing state. */
    public static void onClosed() {
        if (expanded && enabled()) send(PacketTerritoryCommand.panelOpen(false));
        open = false;
        expanded = false;
        toolArmed = false;
        swallowRelease = false;
        resetTransient();
    }

    /** Drops the drag and the fade bookkeeping, so a reopened map fades its overlay in afresh. */
    private static void resetTransient() {
        skipHoldStart = -1;
        cancelTool();
        appeared.clear();
        covered.clear();
        coveredAt.clear();
        lineAppeared.clear();
        fadeFront = null;
        fadeCover = null;
        fadeLine = null;
    }

    private static void toggle() {
        expanded = !expanded;
        if (!expanded) cancelTool();
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

    /** True while a map tool owns the mouse: the Frontline Tool or Advance Plan painting. */
    public static boolean toolArmed() {
        return toolArmed || planPainting;
    }

    public static boolean planPainting() {
        return planPainting;
    }

    /** Drops whichever of the two tools is live, with any stroke in progress. */
    public static void cancelTool() {
        toolArmed = false;
        dragging = false;
        captured.clear();
        planPainting = false;
        paintButton = -1;
        planSent = false;
        painted.clear();
    }

    private static void armTool() {
        if (!toolEnabled()) {
            hint(TerritoryClient.modeOn() ? "gui.tacz_sewv.rts.need_selection" : "gui.tacz_sewv.rts.need_mode");
            return;
        }
        cancelTool();
        CruisePlot.cancel();
        GuardPlot.cancel();
        PathwayPlot.cancel();
        toolArmed = true;
    }

    /** Advance Plan painting needs Territory Mode on, but no selected units: units only matter at Start. */
    private static void armPlan() {
        if (!TerritoryClient.modeOn()) {
            hint("gui.tacz_sewv.rts.need_mode");
            return;
        }
        cancelTool();
        CruisePlot.cancel();
        GuardPlot.cancel();
        PathwayPlot.cancel();
        planPainting = true;
    }

    /** The Confirm button while painting: bake the painted set. Painting stays open until the server's plan matches it. */
    public static void confirmPlan() {
        if (!planPainting) return;
        send(PacketTerritoryCommand.planBake(new ArrayList<>(painted)));
        planSent = true;
    }

    private static List<Integer> selectedPosted() {
        Set<Integer> selected = selection();
        List<Integer> out = new ArrayList<>();
        for (Row r : TerritoryClient.roster()) {
            if (r.posted() && selected.contains(r.id())) out.add(r.id());
        }
        return out;
    }

    private static void startPlan(PlanView plan) {
        if (plan.startReason() == 1) {
            hint("gui.tacz_sewv.rts.plan.no_front");
        } else if (plan.startReason() == 2) {
            hint("gui.tacz_sewv.rts.plan.fragmented");
        } else {
            List<Integer> ids = selectedPosted();
            if (ids.isEmpty()) hint("gui.tacz_sewv.rts.plan.need_posted");
            else send(PacketTerritoryCommand.planStart(ids));
        }
    }

    private static boolean startable(PlanView plan) {
        return plan.state() != PLAN_RUNNING && plan.startReason() == 0 && !selectedPosted().isEmpty();
    }

    /** The painted set is exactly the baked plan's region: the bake went through. */
    private static boolean matchesPainted(PlanView plan) {
        if (plan == null || plan.region().length != painted.size()) return false;
        for (long key : plan.region()) {
            if (!painted.contains(key)) return false;
        }
        return true;
    }

    /**
     * A press while the tool is armed, at the world position under the cursor: left fires the auto Frontline at that
     * chunk, right begins the manual drag. Anything else is swallowed. (Right-click no longer cancels the tool; a
     * right-press with no front chunk under it is just an empty drag. Esc, the panel and the tool key disarm.)
     */
    public static void toolClick(int button, double worldX, double worldZ) {
        swallowRelease = true;
        if (planPainting) {
            if (button == 0 || button == 1) {
                paintButton = button;
                paintLastX = worldX;
                paintLastZ = worldZ;
                paint(worldX, worldZ, worldX, worldZ);
            }
            return;
        }
        if (button == 0) fire(Mth.floor(worldX) >> 4, Mth.floor(worldZ) >> 4);
        else if (button == 1) beginDrag(worldX, worldZ);
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

    private static void beginDrag(double worldX, double worldZ) {
        dragging = true;
        captured.clear();
        dragLastX = worldX;
        dragLastZ = worldZ;
        capture(worldX, worldZ, worldX, worldZ);
    }

    /** Called every frame with the cursor's world position; while a drag runs it captures the front chunks crossed. */
    public static void sampleDrag(double worldX, double worldZ) {
        if (paintButton >= 0) {
            paint(paintLastX, paintLastZ, worldX, worldZ);
            paintLastX = worldX;
            paintLastZ = worldZ;
        }
        if (!dragging) return;
        capture(dragLastX, dragLastZ, worldX, worldZ);
        dragLastX = worldX;
        dragLastZ = worldZ;
    }

    /** Only FRONT chunks are draggable (interior ones crossed on the way are ignored); order = first visit. */
    private static void capture(double x0, double z0, double x1, double z1) {
        Set<Long> front = frontSet();
        for (long key : FrontlineMath.chunksAlong(x0, z0, x1, z1)) {
            if (captured.size() >= MAX_CAPTURED) return;
            if (front.contains(key)) captured.add(key);
        }
    }

    /** Any chunk crossed: added (until the server's cap) or erased. Unlike the Frontline drag it is not limited to the front. */
    private static void paint(double x0, double z0, double x1, double z1) {
        int cap = TerritoryClient.planCap();
        for (long key : FrontlineMath.chunksAlong(x0, z0, x1, z1)) {
            if (paintButton == 0) {
                if (painted.size() < cap) painted.add(key);
            } else {
                painted.remove(key);
            }
        }
        planSent = false;
    }

    private static Set<Long> frontSet() {
        long[] front = TerritoryClient.front();
        if (front != frontSetSource) {
            Set<Long> set = new HashSet<>(front.length * 2);
            for (long key : front) set.add(key);
            frontSet = set;
            frontSetSource = front;
        }
        return frontSet;
    }

    /**
     * The release of a right-drag: send what was drawn. A non-empty drag is a completed operation, so the tool
     * disarms (which also hands Xaero's panning back). An empty one is not: it is still sent, so the server says
     * so, but the tool stays armed to retry.
     */
    private static void endDrag() {
        dragging = false;
        List<Long> chunks = new ArrayList<>(captured);
        captured.clear();
        List<Integer> ids = eligibleSelected();
        if (ids.isEmpty()) {
            hint("gui.tacz_sewv.rts.need_selection");
            return;
        }
        send(PacketTerritoryCommand.manualFrontline(chunks, ids));
        if (!chunks.isEmpty()) toolArmed = false;
    }

    private static void hint(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key), true);
    }

    // ---- layout -------------------------------------------------------------------------------------------

    private record Layout(int x0, int y0, int x1, int y1, int headerY, int modeY, int toolY, int planY,
                          int reasonY, int rosterY, int listTop, int listBottom, int helpY, int helpBodyH) {
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
            return new Layout(x1 - RAIL_W, y0, x1, y0 + railH, y0, y0, y0, y0, y0, y0, y0, y0, y0, 0);
        }
        int panelH = Mth.clamp(h - 40, 210, 520);
        int y0 = (h - panelH) / 2;
        int y1 = y0 + panelH;
        int x1 = rightEdge(w, y1);
        int listTop = y0 + LIST_TOP;
        // The keybind drawer never squeezes the roster below three rows.
        int helpBody = helpOpen ? Math.min(HELP_ENTRIES * HELP_ENTRY_H, Math.max(0, panelH - LIST_TOP - ROW_H - 4 - 3 * ROW_H)) : 0;
        int helpY = y1 - ROW_H - helpBody - 2;
        return new Layout(x1 - PANEL_W, y0, x1, y1, y0 + 2, y0 + 18, y0 + 38, y0 + 58, y0 + 80, y0 + 92,
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
        if (button == 1) {
            if (expanded && skipHit(l, mx, my)) skipHoldStart = System.currentTimeMillis();
            return true;
        }
        if (button != 0) return true;

        if (!expanded) {
            toggle();
            return true;
        }
        if (my < l.modeY) { // header: the collapse arrow at the right
            if (mx >= l.x1 - 18) toggle();
            return true;
        }
        if (my >= l.modeY && my < l.modeY + ROW_H) {
            send(PacketTerritoryCommand.setMode(!TerritoryClient.modeOn()));
            return true;
        }
        if (my >= l.toolY && my < l.toolY + ROW_H) {
            if (TerritoryClient.manualLine().length > 0 && mx >= l.x1 - 20) {
                send(PacketTerritoryCommand.clearLine()); // "Manual line - clear"
            } else if (toolArmed) {
                cancelTool();
            } else {
                armTool();
            }
            return true;
        }
        if (my >= l.planY && my < l.planY + ROW_H) {
            planRowClick(mx, l);
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
        if (my >= l.helpY && my < l.helpY + ROW_H) {
            helpOpen = !helpOpen;
            helpScroll = 0;
        }
        return true;
    }

    private static int skipX(Layout l) {
        return l.x1 - 20 - PLAN_BTN_W - 2 - SKIP_BTN_W - 2;
    }

    /** Over the ">>" button: only shown while a plan is RUNNING and not being repainted. */
    private static boolean skipHit(Layout l, double mx, double my) {
        PlanView plan = TerritoryClient.plan();
        if (plan == null || plan.state() != PLAN_RUNNING || planPainting) return false;
        return mx >= skipX(l) && mx < skipX(l) + SKIP_BTN_W && my >= l.planY && my < l.planY + ROW_H;
    }

    /** Advance Plan row: [clear x] and [Start/Stop] once a plan is baked; anywhere else starts (or cancels) painting. */
    private static void planRowClick(double mx, Layout l) {
        PlanView plan = TerritoryClient.plan();
        if (plan != null && !planPainting) {
            if (mx >= l.x1 - 20) {
                send(PacketTerritoryCommand.planClear());
                return;
            }
            if (skipHit(l, mx, l.planY + ROW_H / 2)) {
                hint("gui.tacz_sewv.rts.plan.skip.hold"); // a left click is not the gesture; say what is
                return;
            }
            if (mx >= l.x1 - 20 - PLAN_BTN_W - 2) {
                if (plan.state() == PLAN_RUNNING) send(PacketTerritoryCommand.planStop());
                else startPlan(plan);
                return;
            }
        }
        if (planPainting) cancelTool();
        else armPlan();
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

    /**
     * The release side of a press we consumed. Completes a right-drag first (at the cursor's final position), then
     * answers true exactly once so Xaero never sees the release.
     */
    public static boolean consumeRelease(int button, double worldX, double worldZ) {
        if (button == 1) skipHoldStart = -1; // released early: nothing fires
        if (paintButton == button) {
            sampleDrag(worldX, worldZ);
            paintButton = -1;
        }
        if (dragging && button == 1) {
            sampleDrag(worldX, worldZ);
            endDrag();
        }
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && planPainting) {
            cancelTool(); // discards the painting; the release of a stroke still in progress is swallowed and does nothing
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && dragging) {
            // Cancel is not complete: discard the drag, apply nothing, keep the tool armed. The release that follows
            // is still swallowed (the press was ours) and, with no drag running, does nothing.
            dragging = false;
            captured.clear();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && toolArmed) {
            toolArmed = false; // Esc cancels the tool next; the one after closes the map
            return true;
        }
        if (RtsKeybind.Keys.TOGGLE_PANEL.matches(keyCode, scanCode)) {
            toggle();
            return true;
        }
        if (RtsKeybind.Keys.FRONTLINE_TOOL.matches(keyCode, scanCode)) {
            if (!expanded) toggle();
            if (toolArmed) cancelTool();
            else armTool();
            return true;
        }
        return false;
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    /**
     * The map overlay, in two tiers so it never draws five layers at once.
     *
     * <p><b>Persistent (always, mode on):</b> a solid amber coverage line joining covered front chunks that are
     * 4-adjacent, with the Frontline icon on each covered chunk's centre. A gap in the line IS the signal for an
     * uncovered stretch, so no red fill is needed. The dashed amber manual ordering line shows whenever a manual line
     * exists or is being drawn. A baked Advance Plan adds its region tint and advance arrows.
     *
     * <p><b>Interaction-time (a tool armed):</b> per-chunk borders (amber covered, red 15% fill on uncovered), the
     * painted Advance Plan chunks, and the white hover highlight.
     *
     * <p>Back to front (Xaero's own claim fill sits beneath all of it): plan region tint, armed chunk marks, coverage
     * line, manual line, icons (on top of both lines; fills are flushed before them), leash and lost-chunk outlines
     * (over the icons: a low-alpha boundary), advance arrows, drag capture highlights, hover. Chunks, lines and icons
     * fade in when they appear, and a chunk that becomes covered fades its line and icon in, the same for an auto run
     * and a manual one (both just change what the server pushes).
     */
    public static void drawOverlay(GuiGraphics g, Project p, int w, int h, int hoverBlockX, int hoverBlockZ) {
        if (!enabled()) return;
        if (!TerritoryClient.modeOn()) {
            cancelTool(); // mode went off with a tool live; the server dropped any plan with it
            return;
        }
        if (planPainting && planSent && matchesPainted(TerritoryClient.plan())) cancelTool(); // the bake went through
        long now = System.currentTimeMillis();
        long[] front = TerritoryClient.front();
        int[] cover = TerritoryClient.coverage();
        long[] line = TerritoryClient.manualLine();
        trackFade(front, cover, line, now);

        drawPlanRegion(g, p, w, h);

        coveredAlpha.clear();
        for (int i = 0; i < front.length; i++) {
            long key = front[i];
            float appear = fade(appeared.get(key), now);
            float cov = cover[i] > 0 ? (coveredAt.containsKey(key) ? fade(coveredAt.get(key), now) : 1f) : 0f;
            if (cover[i] > 0) coveredAlpha.put(key, appear * cov);
            if (toolArmed) {
                Chunk c = FrontlineMath.unpack(key);
                if (cov > 0f) {
                    box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0, scaleAlpha(LINE_AMBER, appear * cov));
                }
                if (cov < 1f) {
                    float u = appear * (1f - cov);
                    box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1,
                            scaleAlpha(0x26000000 | UNCOVERED_RGB, u), scaleAlpha(0x99000000 | UNCOVERED_RGB, u));
                }
            }
        }

        drawCoverageLine(g, p, w, h);

        // Manual ordering: the stored line (chunks still on the front).
        if (line.length > 0) {
            Set<Long> frontKeys = frontSet();
            List<int[]> pts = new ArrayList<>(line.length);
            List<Float> alphas = new ArrayList<>(line.length);
            for (long key : line) {
                if (!frontKeys.contains(key)) continue;
                Chunk c = FrontlineMath.unpack(key);
                pts.add(p.toScreen(c.x() * 16.0 + 8, c.z() * 16.0 + 8));
                alphas.add(fade(lineAppeared.get(key), now));
            }
            dashedPath(g, pts, alphas, w, h);
        }

        // Icons over both lines they anchor; everything queued from here on lands on top of them.
        drawIcons(g, p, w, h);

        drawLeashes(g, p, w, h);
        for (Row r : TerritoryClient.roster()) {
            if (r.posted() && r.lost()) {
                box(g, p, w, h, r.chunkX(), r.chunkZ(), r.chunkX() + 1, r.chunkZ() + 1,
                        0x33000000 | UNCOVERED_RGB, 0xFF000000 | UNCOVERED_RGB);
            }
        }

        drawPlanArrows(g, p, w, h);

        if (dragging && !captured.isEmpty()) {
            List<int[]> pts = new ArrayList<>(captured.size());
            List<Float> alphas = new ArrayList<>(captured.size());
            for (long key : captured) {
                Chunk c = FrontlineMath.unpack(key);
                box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0x33FFFFFF, 0x99FFFFFF);
                pts.add(p.toScreen(c.x() * 16.0 + 8, c.z() * 16.0 + 8));
                alphas.add(1f);
            }
            dashedPath(g, pts, alphas, w, h);
        }

        if ((toolArmed && !dragging) || planPainting) {
            int cx = hoverBlockX >> 4, cz = hoverBlockZ >> 4;
            box(g, p, w, h, cx, cz, cx + 1, cz + 1, 0x33FFFFFF, 0x99FFFFFF);
        }
    }

    /**
     * The Advance Plan region under everything of ours: while painting, the painted set; otherwise the baked plan by
     * layer. The layer being claimed is emphasised, later layers are dimmed, and a layer already claimed is only a faint
     * wash (Xaero draws the claim itself).
     */
    private static void drawPlanRegion(GuiGraphics g, Project p, int w, int h) {
        if (planPainting) {
            for (long key : painted) {
                Chunk c = FrontlineMath.unpack(key);
                box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0x55000000 | ADVANCE_RGB, 0xCC000000 | ADVANCE_RGB);
            }
            return;
        }
        PlanView plan = TerritoryClient.plan();
        if (plan == null) return;
        for (int i = 0; i < plan.region().length; i++) {
            Chunk c = FrontlineMath.unpack(plan.region()[i]);
            int layer = plan.layerOf()[i];
            if (layer < plan.currentLayer()) {
                box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0x18000000 | ADVANCE_RGB, 0);
            } else if (layer == plan.currentLayer()) {
                box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0x60000000 | ADVANCE_RGB, 0xE0000000 | ADVANCE_RGB);
            } else {
                box(g, p, w, h, c.x(), c.z(), c.x() + 1, c.z() + 1, 0x24000000 | ADVANCE_RGB, 0x60000000 | ADVANCE_RGB);
            }
        }
    }

    /**
     * One arrow per edge normal, from the front's edge toward the region centre and perpendicular to the edge it
     * crosses, ending in a chevron. Length runs to the centre along the arrow's own axis (clamped), so a deep region
     * reads as far to go and a shallow one as near.
     */
    private static void drawPlanArrows(GuiGraphics g, Project p, int w, int h) {
        PlanView plan = TerritoryClient.plan();
        if (plan == null || planPainting || plan.arrows().length == 0) return;
        double cx = 0, cz = 0;
        int n = 0;
        for (int i = 0; i < plan.region().length; i++) {
            if (plan.layerOf()[i] < plan.currentLayer()) continue;
            Chunk c = FrontlineMath.unpack(plan.region()[i]);
            cx += c.centreX();
            cz += c.centreZ();
            n++;
        }
        if (n == 0) return;
        cx /= n;
        cz /= n;
        int argb = 0xE0000000 | ADVANCE_RGB;
        int[] arrows = plan.arrows();
        for (int i = 0; i + 3 < arrows.length; i += 4) {
            double bx = arrows[i], bz = arrows[i + 1];
            int dx = arrows[i + 2], dz = arrows[i + 3];
            double along = (cx - bx) * dx + (cz - bz) * dz;
            double len = Mth.clamp(along - 8, 24, 128);
            int[] from = p.toScreen(bx + dx * 8.0, bz + dz * 8.0);
            int[] tip = p.toScreen(bx + dx * (8.0 + len), bz + dz * (8.0 + len));
            if (Math.max(from[0], tip[0]) < 0 || Math.min(from[0], tip[0]) > w
                    || Math.max(from[1], tip[1]) < 0 || Math.min(from[1], tip[1]) > h) continue;
            leg(g, from, tip, argb);
            chevron(g, from, tip, argb);
        }
    }

    /** A leg as a chain of dots: no rotated quad, and it reads as a route rather than a border. (Copied from MixinGuiMap.) */
    private static void leg(GuiGraphics g, int[] from, int[] to, int color) {
        int dx = to[0] - from[0];
        int dy = to[1] - from[1];
        int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 6;
        for (int i = 1; i < steps; i++) {
            int x = from[0] + dx * i / steps;
            int y = from[1] + dy * i / steps;
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    /** Chevron at {@code to}, pointing along from -> to. Screen-space only. (Copied from MixinGuiMap.) */
    private static void chevron(GuiGraphics g, int[] from, int[] to, int color) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 8.0) return;
        double ux = dx / len, uy = dy / len;
        double px = -uy, py = ux;
        int wing = 7, back = 10;
        int[] left = {(int) Math.round(to[0] - ux * back + px * wing), (int) Math.round(to[1] - uy * back + py * wing)};
        int[] right = {(int) Math.round(to[0] - ux * back - px * wing), (int) Math.round(to[1] - uy * back - py * wing)};
        leg(g, left, to, color);
        leg(g, right, to, color);
        g.fill(to[0] - 2, to[1] - 2, to[0] + 2, to[1] + 2, color);
    }

    /**
     * Solid amber segments between covered front chunks whose centres are 4-adjacent. Both neighbours must be covered:
     * either one uncovered breaks the line, so the gap marks the hole. Each chunk looks east and south only, so a
     * segment is drawn once.
     */
    private static void drawCoverageLine(GuiGraphics g, Project p, int w, int h) {
        for (Map.Entry<Long, Float> e : coveredAlpha.entrySet()) {
            Chunk c = FrontlineMath.unpack(e.getKey());
            for (int[] step : new int[][] {{1, 0}, {0, 1}}) {
                Float other = coveredAlpha.get(FrontlineMath.pack(c.x() + step[0], c.z() + step[1]));
                if (other == null) continue;
                int[] a = p.toScreen(c.x() * 16.0 + 8, c.z() * 16.0 + 8);
                int[] b = p.toScreen((c.x() + step[0]) * 16.0 + 8, (c.z() + step[1]) * 16.0 + 8);
                if (Math.max(a[0], b[0]) < 0 || Math.min(a[0], b[0]) > w
                        || Math.max(a[1], b[1]) < 0 || Math.min(a[1], b[1]) > h) continue;
                // Axis-aligned by construction (4-adjacent), so one 2 px rectangle is the whole line.
                g.fill(Math.min(a[0], b[0]) - 1, Math.min(a[1], b[1]) - 1,
                        Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1,
                        scaleAlpha(LINE_AMBER, Math.min(e.getValue(), other)));
            }
        }
    }

    /**
     * The Frontline icon on each covered chunk's centre, drawn over the line it anchors. Grayscale source, tinted
     * amber at blit time through the shader colour (never pre-tinted in the file). Native 16 px where the chunk is
     * big enough to hold it, an 8 px half-scale where it would dominate, and omitted when the map is zoomed so far out
     * that a chunk is a few pixels (the line alone carries it).
     */
    private static void drawIcons(GuiGraphics g, Project p, int w, int h) {
        if (coveredAlpha.isEmpty()) return;
        // GuiGraphics.fill is batched into a deferred buffer that only flushes at the end of the frame, while blit
        // draws immediately. Without this the lines and borders queued above would land ON TOP of the icons no
        // matter what order they were called in.
        g.flush();
        float r = ((LINE_AMBER >> 16) & 0xFF) / 255f;
        float gr = ((LINE_AMBER >> 8) & 0xFF) / 255f;
        float b = (LINE_AMBER & 0xFF) / 255f;
        RenderSystem.enableBlend();
        for (Map.Entry<Long, Float> e : coveredAlpha.entrySet()) {
            Chunk c = FrontlineMath.unpack(e.getKey());
            int[] a = p.toScreen(c.x() * 16.0, c.z() * 16.0);
            int[] far = p.toScreen((c.x() + 1) * 16.0, (c.z() + 1) * 16.0);
            int chunkPx = Math.abs(far[0] - a[0]);
            int size = chunkPx >= ICON_FULL_MIN_CHUNK_PX ? ICON_NATIVE : (chunkPx >= ICON_HALF_MIN_CHUNK_PX ? ICON_NATIVE / 2 : 0);
            if (size == 0) continue;
            int cx = (a[0] + far[0]) / 2, cy = (a[1] + far[1]) / 2;
            if (cx + size < 0 || cy + size < 0 || cx - size > w || cy - size > h) continue;
            g.setColor(r, gr, b, e.getValue());
            g.blit(FRONT_ICON, cx - size / 2, cy - size / 2, size, size, 0, 0, ICON_NATIVE, ICON_NATIVE, ICON_NATIVE, ICON_NATIVE);
        }
        g.setColor(1f, 1f, 1f, 1f);
    }

    /**
     * One marching square per distinct selected posted chunk: its 3x3-chunk leash. The dash pattern and the march
     * are patrol's ring ({@code OrderPreview.ring}) ported to a closed rectangular path — every other segment, phase
     * from the same wall clock over the same period constant, so a lap takes exactly as long as a patrol ring's.
     * Drawn first (behind the coverage line); never one ring per chunk, which is noise.
     */
    private static void drawLeashes(GuiGraphics g, Project p, int w, int h) {
        Set<Integer> selected = selection();
        Set<Long> seen = new HashSet<>();
        int argb = (LEASH_ALPHA << 24) | LEASH_RGB;
        for (Row r : TerritoryClient.roster()) {
            if (!r.posted() || !selected.contains(r.id())) continue;
            if (!seen.add(FrontlineMath.pack(r.chunkX(), r.chunkZ()))) continue;
            int[] a = p.toScreen((r.chunkX() - 1) * 16.0, (r.chunkZ() - 1) * 16.0);
            int[] b = p.toScreen((r.chunkX() + 2) * 16.0, (r.chunkZ() + 2) * 16.0);
            marchSquare(g, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), argb, w, h);
        }
    }

    private static void marchSquare(GuiGraphics g, int x0, int y0, int x1, int y1, int argb, int w, int h) {
        if (x1 < 0 || y1 < 0 || x0 > w || y0 > h) return;
        double perimeter = 2.0 * ((x1 - x0) + (y1 - y0));
        if (perimeter < 16.0) return;
        // Segment length ~8 px (patrol's ring is ~7-10 px), an even count, never fewer than the ring's 48.
        int segs = Mth.clamp((int) Math.round(perimeter / 8.0), 48, 160) & ~1;
        double laps = System.currentTimeMillis() / 1000.0 / OrderPreview.RING_SECONDS_PER_RAD / (Math.PI * 2.0);
        double offset = (laps - Math.floor(laps)) * perimeter;
        for (int i = 1; i < segs; i += 2) { // odd segments only, as the ring's `(i & 1) == 0 -> continue`
            double s = (i * perimeter / segs + offset) % perimeter;
            int px, py;
            if (s < x1 - x0) { px = (int) (x0 + s); py = y0; }
            else if ((s -= x1 - x0) < y1 - y0) { px = x1; py = (int) (y0 + s); }
            else if ((s -= y1 - y0) < x1 - x0) { px = (int) (x1 - s); py = y1; }
            else { s -= x1 - x0; px = x0; py = (int) (y1 - s); }
            g.fill(px - 1, py - 1, px + 1, py + 1, argb);
        }
    }

    /**
     * The manual ordering line: dashed amber through the chunk centres in drawn order, kept distinct from OpenPAC's
     * white manual-claim line by colour and by the dashing. Dash phase runs continuously along the whole path; each
     * stretch takes the fade of its weaker end so a fresh line appears smoothly.
     */
    private static void dashedPath(GuiGraphics g, List<int[]> pts, List<Float> alphas, int w, int h) {
        double travelled = 0;
        for (int i = 1; i < pts.size(); i++) {
            int[] a = pts.get(i - 1), b = pts.get(i);
            double dx = b[0] - a[0], dz = b[1] - a[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len == 0) continue;
            boolean off = Math.max(a[0], b[0]) < 0 || Math.min(a[0], b[0]) > w || Math.max(a[1], b[1]) < 0 || Math.min(a[1], b[1]) > h;
            if (!off) {
                int color = scaleAlpha(LINE_AMBER, Math.min(alphas.get(i - 1), alphas.get(i)));
                int stride = Math.max(1, (int) (len / 1500));
                for (int s = 0; s < len; s += stride) {
                    if (((travelled + s) % (DASH_ON + DASH_OFF)) >= DASH_ON) continue;
                    int x = (int) Math.round(a[0] + dx * s / len);
                    int y = (int) Math.round(a[1] + dz * s / len);
                    g.fill(x - 1, y - 1, x + 1, y + 1, color);
                }
            }
            travelled += len;
        }
    }

    /** Refreshes the fade timestamps, only when a state push has swapped the arrays (about once a second). */
    private static void trackFade(long[] front, int[] cover, long[] line, long now) {
        if (front != fadeFront || cover != fadeCover) {
            Set<Long> present = new HashSet<>();
            for (int i = 0; i < front.length; i++) {
                long key = front[i];
                present.add(key);
                appeared.putIfAbsent(key, now);
                boolean cov = cover[i] > 0;
                Boolean was = covered.put(key, cov);
                if (was != null && was != cov) {
                    if (cov) coveredAt.put(key, now);
                    else coveredAt.remove(key);
                }
            }
            appeared.keySet().retainAll(present);
            covered.keySet().retainAll(present);
            coveredAt.keySet().retainAll(present);
            fadeFront = front;
            fadeCover = cover;
        }
        if (line != fadeLine) {
            Set<Long> present = new HashSet<>();
            for (long key : line) {
                present.add(key);
                lineAppeared.putIfAbsent(key, now);
            }
            lineAppeared.keySet().retainAll(present);
            fadeLine = line;
        }
    }

    private static float fade(Long startedAt, long now) {
        if (startedAt == null) return 1f;
        return Mth.clamp((now - startedAt) / (float) FADE_MS, 0f, 1f);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.round(((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
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

    /**
     * The panel, in three stages because {@code GuiGraphics.fill} is batched into a deferred buffer while {@code blit}
     * draws immediately: (1) every fill a sprite sits on, then a flush; (2) the sprites; (3) text and bars. Drawing
     * a row background after its icon would hide the icon, however the calls are ordered in the source.
     *
     * <p>Colour is for STATE only, never for category: idle rows are monochrome, an armed tool's row takes a subtle
     * amber tint, a disabled row drops to 40% opacity. Amber is reserved for "attention here".
     */
    public static void render(GuiGraphics g, Font font, int w, int h, int mx, int my) {
        if (!enabled()) return;
        Layout l = layout(w, h);
        boolean on = TerritoryClient.modeOn();

        // ---- stage 1: fills
        g.fill(l.x0, l.y0, l.x1, l.y1, BG);
        g.fill(l.x0, l.y0, l.x1, l.y0 + 1, BORDER);
        g.fill(l.x0, l.y1 - 1, l.x1, l.y1, BORDER);
        g.fill(l.x0, l.y0, l.x0 + 1, l.y1, BORDER);
        g.fill(l.x1 - 1, l.y0, l.x1, l.y1, BORDER);

        if (!expanded) {
            drawRail(g, font, l, on);
            return;
        }

        boolean enabled = toolEnabled();
        boolean hasLine = TerritoryClient.manualLine().length > 0;
        List<Row> rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, maxScroll(l, rows.size()));
        Set<Integer> selected = selection();
        int shown = l.visibleRows();

        rowBackground(g, l, l.modeY, ROW_BG);
        rowBackground(g, l, l.toolY, toolArmed ? ARMED_BG : ROW_BG);
        rowBackground(g, l, l.planY, planPainting ? ARMED_BG : ROW_BG);
        rowBackground(g, l, l.rosterY, ROW_BG);
        rowBackground(g, l, l.helpY, ROW_BG);
        for (int i = 0; i < shown && scroll + i < rows.size(); i++) {
            if (selected.contains(rows.get(scroll + i).id())) rowBackground(g, l, l.listTop + i * ROW_H, SELECT_BG);
        }
        g.flush();

        // ---- stage 2: sprites
        sprite(g, ICON_MODE, l.x0 + 6, l.modeY + 2, 1f);
        sprite(g, ICON_TOOL, l.x0 + 6, l.toolY + 2, enabled ? 1f : DISABLED_ALPHA);
        sprite(g, ICON_ADVANCE, l.x0 + 6, l.planY + 2, on ? 1f : DISABLED_ALPHA);
        sprite(g, ICON_ROSTER, l.x0 + 6, l.rosterY + 2, 1f);
        sprite(g, ICON_KEYS, l.x0 + 6, l.helpY + 2, 1f);
        for (int i = 0; i < shown && scroll + i < rows.size(); i++) {
            Row r = rows.get(scroll + i);
            sprite(g, unitIcon(r), l.x0 + 6, l.listTop + i * ROW_H + 2, ineligible(r) ? DISABLED_ALPHA : 1f);
        }
        checkbox(g, l.x1 - 6 - SPRITE, l.modeY + 2, on);
        checkbox(g, l.x1 - 6 - SPRITE, l.rosterY + 2, hideIneligible);

        // ---- stage 3: text and bars
        int labelX = l.x0 + 6 + SPRITE + 6;
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.title"), l.x0 + 6, l.headerY + 3, TEXT, false);
        g.drawString(font, ">", l.x1 - 12, l.headerY + 3, DIM, false);

        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.mode"), labelX, l.modeY + 6, TEXT, false);

        int toolText = enabled ? TEXT : scaleAlpha(TEXT, DISABLED_ALPHA);
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.tool"), labelX, l.toolY + 6, toolText, false);
        if (hasLine) {
            boolean hot = mx >= l.x1 - 20 && mx < l.x1 - 4 && my >= l.toolY && my < l.toolY + ROW_H;
            g.drawString(font, "x", l.x1 - 14, l.toolY + 6, hot ? 0xFFFF6666 : DIM, false);
        }
        PlanView plan = TerritoryClient.plan();
        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.plan"), labelX, l.planY + 6,
                on ? TEXT : scaleAlpha(TEXT, DISABLED_ALPHA), false);
        if (plan != null && !planPainting) {
            boolean running = plan.state() == PLAN_RUNNING;
            boolean go = running || startable(plan);
            int bx = l.x1 - 20 - PLAN_BTN_W - 2;
            g.fill(bx, l.planY + 4, bx + PLAN_BTN_W, l.planY + ROW_H - 4, running ? 0xFF8A5A1F : (go ? 0xFF2F7F55 : 0xFF3A4048));
            g.drawCenteredString(font, Component.translatable(running ? "gui.tacz_sewv.rts.plan.stop" : "gui.tacz_sewv.rts.plan.start"),
                    bx + PLAN_BTN_W / 2, l.planY + 6, go ? CHIP_LIGHT : DIM);
            boolean hot = mx >= l.x1 - 20 && mx < l.x1 - 4 && my >= l.planY && my < l.planY + ROW_H;
            g.drawString(font, "x", l.x1 - 14, l.planY + 6, hot ? 0xFFFF6666 : DIM, false);
            if (running) drawSkipButton(g, font, l, mx, my);
        } else {
            skipHoldStart = -1;
        }

        // One status line for both tools: what you are doing now, then the plan's state, then why a tool is unavailable.
        if (planPainting) {
            int n = painted.size(), cap = TerritoryClient.planCap();
            g.drawString(font, Component.translatable("gui.tacz_sewv.rts.plan.painting", n, cap), l.x0 + 8, l.reasonY,
                    n >= cap ? 0xFFFF6666 : LINE_AMBER, false);
        } else if (toolArmed) {
            g.drawString(font, Component.translatable("gui.tacz_sewv.rts.armed"), l.x0 + 8, l.reasonY, LINE_AMBER, false);
        } else if (plan != null) {
            boolean held = plan.state() == PLAN_RUNNING && plan.hold() > 0 && plan.hold() < HOLD_KEYS.length;
            g.drawString(font, planStatus(plan), l.x0 + 8, l.reasonY, held ? LINE_AMBER : DIM, false);
        } else if (!enabled) {
            Component reason = Component.translatable(on ? "gui.tacz_sewv.rts.need_selection" : "gui.tacz_sewv.rts.need_mode");
            g.drawString(font, reason, l.x0 + 8, l.reasonY, DIM, false);
        }

        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.roster", TerritoryClient.roster().size()),
                labelX, l.rosterY + 6, TEXT, false);
        Component filter = Component.translatable("gui.tacz_sewv.rts.filter");
        g.drawString(font, filter, l.x1 - 6 - SPRITE - 4 - font.width(filter), l.rosterY + 6, DIM, false);

        for (int i = 0; i < shown && scroll + i < rows.size(); i++) {
            drawRow(g, font, rows.get(scroll + i), l, l.listTop + i * ROW_H, mx, my);
        }
        if (rows.size() > shown) {
            int trackH = l.listBottom - l.listTop;
            int thumbH = Math.max(6, trackH * shown / rows.size());
            int thumbY = l.listTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll(l, rows.size()));
            g.fill(l.x1 - 3, thumbY, l.x1 - 1, thumbY + thumbH, BORDER);
        }

        g.drawString(font, Component.translatable("gui.tacz_sewv.rts.keys"), labelX, l.helpY + 6, TEXT, false);
        g.drawString(font, helpOpen ? "v" : "^", l.x1 - 14, l.helpY + 6, DIM, false);
        if (helpOpen) drawHelp(g, font, l);

        if (toolArmed) {
            g.drawCenteredString(font, Component.translatable("gui.tacz_sewv.rts.tool_hint"), w / 2, h - 42, 0xFFFFFFFF);
        } else if (planPainting) {
            g.drawCenteredString(font, Component.translatable("gui.tacz_sewv.rts.plan.paint_hint"), w / 2, h - 42, 0xFFFFFFFF);
        }
        if (skipHit(l, mx, my)) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("gui.tacz_sewv.rts.plan.skip.tip1"),
                    Component.translatable("gui.tacz_sewv.rts.plan.skip.tip2")), mx, my);
        } else if (plan != null && !planPainting && mx >= l.x1 - 20 && mx < l.x1 - 4 && my >= l.planY && my < l.planY + ROW_H) {
            g.renderComponentTooltip(font, List.of(Component.translatable("gui.tacz_sewv.rts.plan.clear")), mx, my);
        } else if (mx >= l.x0 + 4 && mx < l.x1 - 4 && my >= l.planY && my < l.planY + ROW_H) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("gui.tacz_sewv.rts.plan.tip1"),
                    Component.translatable("gui.tacz_sewv.rts.plan.tip2")), mx, my);
        } else if (hasLine && mx >= l.x1 - 20 && mx < l.x1 - 4 && my >= l.toolY && my < l.toolY + ROW_H) {
            g.renderComponentTooltip(font, List.of(Component.translatable("gui.tacz_sewv.rts.clear_line")), mx, my);
        } else if (mx >= l.x0 + 4 && mx < l.x1 - 4 && my >= l.toolY && my < l.toolY + ROW_H) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("gui.tacz_sewv.rts.tool_tip1"),
                    Component.translatable("gui.tacz_sewv.rts.tool_tip2")), mx, my);
        }
    }

    /**
     * The ">>" skip-layer button: dark track, filled left to right in a bright colour while right-click is held on it,
     * and fired when the fill completes. Moving off the button (or the plan stopping) abandons the hold.
     */
    private static void drawSkipButton(GuiGraphics g, Font font, Layout l, int mx, int my) {
        float progress = 0f;
        if (skipHoldStart >= 0) {
            if (!skipHit(l, mx, my)) {
                skipHoldStart = -1;
            } else {
                progress = Mth.clamp((System.currentTimeMillis() - skipHoldStart) / (float) SKIP_HOLD_MS, 0f, 1f);
                if (progress >= 1f) {
                    send(PacketTerritoryCommand.planSkip());
                    skipHoldStart = -1;
                    progress = 0f;
                }
            }
        }
        int x = skipX(l), y = l.planY + 4, h = ROW_H - 8;
        g.fill(x, y, x + SKIP_BTN_W, y + h, SKIP_TRACK);
        if (progress > 0f) g.fill(x, y, x + Math.round(SKIP_BTN_W * progress), y + h, SKIP_FILL);
        g.drawCenteredString(font, ">>", x + SKIP_BTN_W / 2, l.planY + 6, progress > 0.5f ? 0xFF10202E : (skipHit(l, mx, my) ? TEXT : DIM));
    }

    /** The plan's state as one short line. Layers are shown 1-based; {@code currentLayer} is the 0-based one being claimed. */
    private static Component planStatus(PlanView plan) {
        int layer = Math.min(plan.currentLayer() + 1, plan.layerCount());
        if (plan.state() == PLAN_RUNNING) {
            if (plan.hold() > 0 && plan.hold() < HOLD_KEYS.length) {
                return Component.translatable("gui.tacz_sewv.rts.plan.held", layer, plan.layerCount(),
                        Component.translatable("gui.tacz_sewv.rts.plan.hold." + HOLD_KEYS[plan.hold()]));
            }
            return Component.translatable("gui.tacz_sewv.rts.plan.running", layer, plan.layerCount());
        }
        if (plan.startReason() == 1) return Component.translatable("gui.tacz_sewv.rts.plan.no_front");
        if (plan.startReason() == 2) return Component.translatable("gui.tacz_sewv.rts.plan.fragmented");
        if (selectedPosted().isEmpty()) return Component.translatable("gui.tacz_sewv.rts.plan.need_posted");
        return Component.translatable("gui.tacz_sewv.rts.plan.ready", plan.region().length, plan.layerCount());
    }

    /** A row's background: inset 4 px each side and 1 px top and bottom, so adjacent 20 px rows read as separate. */
    private static void rowBackground(GuiGraphics g, Layout l, int y, int argb) {
        g.fill(l.x0 + 4, y + 1, l.x1 - 4, y + ROW_H - 1, argb);
    }

    /** A 16 px monochrome sprite, tinted near-white and faded by {@code alpha} (40% when its row is disabled). */
    private static void sprite(GuiGraphics g, ResourceLocation texture, int x, int y, float alpha) {
        RenderSystem.enableBlend();
        g.setColor(0.92f, 0.92f, 0.92f, alpha);
        g.blit(texture, x, y, SPRITE, SPRITE, 0, 0, 16, 16, 16, 16);
        g.setColor(1f, 1f, 1f, 1f);
    }

    /** The vanilla checkbox: empty when off, the checked cell tinted green when on. */
    private static void checkbox(GuiGraphics g, int x, int y, boolean checked) {
        RenderSystem.enableBlend();
        if (checked) g.setColor(0.45f, 1f, 0.45f, 1f);
        g.blit(CHECKBOX, x, y, SPRITE, SPRITE, 0f, checked ? 20f : 0f, 20, 20, 64, 64);
        g.setColor(1f, 1f, 1f, 1f);
    }

    /** The collapsed rail: the arrow, the vertical label, and an amber dot while the mode is on with units posted. */
    private static void drawRail(GuiGraphics g, Font font, Layout l, boolean on) {
        g.drawCenteredString(font, "<", l.x0 + RAIL_W / 2, l.y0 + 6, TEXT);
        String label = Component.translatable("gui.tacz_sewv.rts.rail").getString();
        for (int i = 0; i < label.length(); i++) {
            g.drawCenteredString(font, String.valueOf(label.charAt(i)), l.x0 + RAIL_W / 2, l.y0 + 20 + i * 10, DIM);
        }
        boolean posted = false;
        for (Row r : TerritoryClient.roster()) {
            if (r.posted()) {
                posted = true;
                break;
            }
        }
        if (on && posted) {
            // 6x6 rounded amber dot in a 1 px black ring, so it holds against any map colour.
            int cx = l.x0 + RAIL_W / 2, cy = l.y1 - 12;
            g.fill(cx - 4, cy - 3, cx + 4, cy + 3, 0xFF000000);
            g.fill(cx - 3, cy - 4, cx + 3, cy + 4, 0xFF000000);
            g.fill(cx - 3, cy - 2, cx + 3, cy + 2, LINE_AMBER);
            g.fill(cx - 2, cy - 3, cx + 2, cy + 3, LINE_AMBER);
        }
    }

    /** Infantry, a hull, or an aircraft: air is the driver of an aircraft (the server's AIR status), vehicle any other seat. */
    private static ResourceLocation unitIcon(Row r) {
        if (r.status() == com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_AIR) return ICON_AIR;
        return r.kind() == 1 ? ICON_VEHICLE : ICON_INFANTRY;
    }

    /** A unit that cannot be posted (and is not already): its row reads as disabled, its chip says why. */
    private static boolean ineligible(Row r) {
        return r.status() != com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_OK && !r.posted();
    }

    /**
     * Roster row content: name, health bar, status pill, eject. Sprites and the selection background are earlier
     * stages. The columns are fixed, so a scan down the list compares bars and reads chips without reading names.
     */
    private static void drawRow(GuiGraphics g, Font font, Row r, Layout l, int y, int mx, int my) {
        int ty = y + (ROW_H - 8) / 2;
        int nameX = l.x0 + 6 + SPRITE + 4;
        g.drawString(font, font.plainSubstrByWidth(r.name(), NAME_W), nameX, ty, ineligible(r) ? DIM : TEXT, false);

        int barX = nameX + NAME_W + 4;
        healthBar(g, barX, y + (ROW_H - BAR_H) / 2, r.health());

        Chip chip = chipFor(r);
        if (chip != null) pill(g, font, barX + BAR_W + 6, y + 4, chip);

        if (r.posted()) {
            boolean hot = mx >= l.x1 - 14 && mx < l.x1 - 5 && my >= y && my < y + ROW_H;
            g.drawString(font, "x", l.x1 - 12, ty, hot ? 0xFFFF6666 : DIM, false);
        }
    }

    private static void healthBar(GuiGraphics g, int x, int y, float health) {
        float f = Mth.clamp(health, 0f, 1f);
        g.fill(x, y, x + BAR_W, y + BAR_H, BAR_TRACK);
        int color = f > 0.66f ? HEALTH_GREEN : (f > 0.33f ? HEALTH_YELLOW : HEALTH_RED);
        g.fill(x, y, x + Math.round(BAR_W * f), y + BAR_H, color);
    }

    private record Chip(String key, int bg, int fg) {}

    private static final int CHIP_LIGHT = 0xFFFFFFFF;
    private static final int CHIP_DARK = 0xFF10202E;

    /** TERRITORY when posted (red if its chunk was lost), otherwise why the unit cannot be. Each chip owns its colour. */
    private static Chip chipFor(Row r) {
        if (r.posted()) {
            return new Chip("gui.tacz_sewv.rts.chip.territory", r.lost() ? 0xFFC44536 : 0xFF2F7F55, CHIP_LIGHT);
        }
        return switch (r.status()) {
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_CREW -> new Chip("gui.tacz_sewv.rts.chip.crew", 0xFF5A6478, CHIP_LIGHT);
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_FOB -> new Chip("gui.tacz_sewv.rts.chip.fob", 0xFF3F6FB0, CHIP_LIGHT);
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_AIR -> new Chip("gui.tacz_sewv.rts.chip.air", 0xFF7FB2E5, CHIP_DARK);
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_DOWNED -> new Chip("gui.tacz_sewv.rts.chip.downed", 0xFFC44536, CHIP_LIGHT);
            case com.neoalive.tacz_sewv.network.PacketTerritoryState.ST_SWEEP -> new Chip("gui.tacz_sewv.rts.chip.sweep", 0xFF2E8B8B, CHIP_LIGHT);
            default -> null;
        };
    }

    /** A pill: a 12 px tall filled shape with cut corners, its text in the chip's contrasting foreground. */
    private static void pill(GuiGraphics g, Font font, int x, int y, Chip chip) {
        Component text = Component.translatable(chip.key());
        int w = font.width(text) + 8;
        g.fill(x + 1, y, x + w - 1, y + 12, chip.bg());
        g.fill(x, y + 1, x + w, y + 11, chip.bg());
        g.drawString(font, text, x + 4, y + 2, chip.fg(), false);
    }

    /** Keybind rows only (spec 3.4): glyph, action name, one-line description. Reference, not a command surface. */
    private static void drawHelp(GuiGraphics g, Font font, Layout l) {
        String[] glyph = {
                RtsKeybind.Keys.TOGGLE_PANEL.getTranslatedKeyMessage().getString(),
                RtsKeybind.Keys.FRONTLINE_TOOL.getTranslatedKeyMessage().getString(),
                "RMB drag",
                "LMB/RMB drag",
                "Esc",
                "Shift+Click"};
        String[] name = {"toggle", "frontline", "draw", "advance", "cancel", "add"};
        int y = l.helpY + ROW_H + 2;
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
