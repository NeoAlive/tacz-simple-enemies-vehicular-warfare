package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.xaero.CruisePlot;
import com.neoalive.tacz_sewv.client.xaero.OrderPreview;
import com.neoalive.tacz_sewv.client.xaero.UnitOrderOption;
import com.neoalive.tacz_sewv.client.xaero.VehicleMarkerElements;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.util.MarkerOrder;
import com.neoalive.tacz_sewv.util.VehicleMarker;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.GuiRightClickMenu;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.List;

/**
 * The command half of the map integration: left-click a vehicle marker to select it, right-click the
 * map for the order menu, and — while cruise plotting is armed — lay a route out click by click.
 *
 * <p>Both hooks are here because Xaero has no registry for either. Selection rides on
 * {@code mapClicked}, whose left-button branch is empty — the element framework has already resolved
 * whatever the cursor is over into {@code viewed}, so this only has to read it and stop the click.
 * The order entries ride on {@code getRightClickOptions}, which builds a fresh list per click and has
 * the clicked block position sitting in {@code rightClickX/Y/Z}; the entries themselves are
 * {@link UnitOrderOption}, deliberately not anonymous classes in a mixin.
 *
 * <p><b>Cruise plotting takes over the map's clicks entirely while it is armed</b> — left lays a
 * node, right removes one, and neither is allowed through to selection or to the right-click menu,
 * which would otherwise open on top of the plot. Its two buttons are added once in {@code init} and
 * simply hidden when the mode is off: the mode is armed from the right-click menu, long after
 * {@code init} has run, so there is no later moment to add them in. The route is drawn at the TAIL
 * of {@code render}, in plain screen space computed from the shadowed camera — the map's own element
 * pass is for things that persist and want hover, and this is a transient overlay that wants
 * neither.
 *
 * <p>Lives in a separate, <b>non-required</b> mixin config: Xaero is optional, and the main config
 * is {@code required: true}, which would turn a missing map mod into a startup crash.
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinGuiMap extends Screen {

    protected MixinGuiMap(Component title) {
        super(title);
    }

    @Shadow
    private HoveredMapElementHolder<?, ?> viewed;

    @Shadow
    private int rightClickX;

    @Shadow
    private int rightClickY;

    @Shadow
    private int rightClickZ;

    @Shadow
    private ResourceKey<Level> rightClickDim;

    @Shadow
    private int mouseBlockPosX;

    @Shadow
    private int mouseBlockPosY;

    @Shadow
    private int mouseBlockPosZ;

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Shadow
    private double screenScale;

    // Xaero's open right-click menu (null when closed — onRightClickClosed nulls it) and its two
    // toggle-menus. While any of these is up, a click belongs to the menu, not to our drag/box.
    @Shadow
    private GuiRightClickMenu rightClickMenu;

    @Shadow
    public boolean waypointMenu;

    @Shadow
    public boolean playersMenu;

    /** Xaero's "no surface height known here" sentinel — an unexplored tile, or cave mode. */
    @Unique
    private static final int TACZ_SEWV$NO_HEIGHT = 32767;

    /** How near (in blocks) a right-click has to be to drop that node instead of the last one. */
    @Unique
    private static final double TACZ_SEWV$NODE_PICK_REACH = 24.0;

    @Unique
    private Button tacz_sewv$confirmButton;

    @Unique
    private Button tacz_sewv$cancelButton;

    /**
     * Live multi-unit line-order drag. All transient client state, and deliberately an <b>instance</b>
     * field: it dies with the screen, so a drag interrupted by closing the map can never leak into the
     * next one. {@code orderDragging} is only ever true between a press we swallowed and its release.
     */
    @Unique
    private boolean tacz_sewv$orderDragging;

    @Unique
    private int tacz_sewv$dragAx;

    @Unique
    private int tacz_sewv$dragAy;

    @Unique
    private int tacz_sewv$dragAz;

    /** The marker under a left-press, so a barely-moved drag toggles it (a drag can start on a marker). */
    @Unique
    private VehicleMarker tacz_sewv$pressMarker;

    /** Below this (blocks²) an order-drag is treated as a click — edit selection — rather than a line. */
    @Unique
    private static final double TACZ_SEWV$MIN_DRAG_SQ = 16.0;

    /**
     * Right-drag box-select was remapped to middle-mouse drag so RMB stays free for Xaero's order
     * menu. {@code boxSelecting} is true only while the middle button is held over the map; like
     * the line drag it is an instance field, so it dies with the screen. Point A is stored in
     * world coordinates so the box stays anchored to the ground.
     */
    @Unique
    private boolean tacz_sewv$boxSelecting;

    @Unique
    private int tacz_sewv$boxAx;

    @Unique
    private int tacz_sewv$boxAz;

    /** Screen-pixel drag below which a middle-drag is a middle-CLICK — no box, no other action.
     *  Kept above Xaero's own 5px click threshold so the two never both fire on one gesture. */
    @Unique
    private static final double TACZ_SEWV$BOX_MIN_PX = 6.0;

    // remap = true on this one and on render: the class is remap = false for Xaero's own members,
    // but init/render are VANILLA methods and are SRG-named in production, so the literal name
    // would simply never be found.
    @Inject(method = "init", at = @At("RETURN"), remap = true)
    private void tacz_sewv$addPlotButtons(CallbackInfo ci) {
        int y = this.height - 28;
        this.tacz_sewv$confirmButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.map.cruise.confirm"),
                b -> tacz_sewv$hint("message.tacz_sewv.cruise.plotted", CruisePlot.confirm()))
                .bounds(this.width / 2 - 104, y, 100, 20).build());
        this.tacz_sewv$cancelButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.tacz_sewv.map.cruise.cancel"),
                b -> {
                    CruisePlot.cancel();
                    tacz_sewv$hint("message.tacz_sewv.cruise.cancelled");
                })
                .bounds(this.width / 2 + 4, y, 100, 20).build());
        this.tacz_sewv$confirmButton.visible = false;
        this.tacz_sewv$cancelButton.visible = false;
    }

    @Inject(method = "mapClicked", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$mapClicked(int button, int x, int y, CallbackInfo ci) {
        if (CruisePlot.armed()) {
            if (button == 0) {
                CruisePlot.add(new BlockPos(this.mouseBlockPosX,
                        tacz_sewv$nodeY(), this.mouseBlockPosZ));
            } else {
                CruisePlot.removeNear(this.mouseBlockPosX, this.mouseBlockPosZ, TACZ_SEWV$NODE_PICK_REACH);
            }
            ci.cancel(); // never fall through to selection or to the right-click menu
            return;
        }

        if (button != 0 || this.viewed == null) return;
        if (!(this.viewed.getElement() instanceof VehicleMarker marker)) return;
        // Only swallow the click if it selected something — a click on an enemy symbol should still
        // do whatever the map would have done with it.
        if (MapMarkers.toggleSelected(marker)) ci.cancel();
    }

    /**
     * Both map drags begin here: a LEFT-drag (with &gt;1 unit selected) lays out a line MOVE order
     * instead of panning, and a MIDDLE-drag boxes in your units. Neither disturbs a plain click — a
     * left-click still selects a marker, a right-click still opens the order menu — because only a real
     * drag, judged on release, acts. A press on any Xaero widget is left alone, and while cruise
     * plotting is armed both stand aside.
     *
     * <p>remap = true: mouseClicked/mouseReleased are vanilla {@code Screen} methods (SRG-named in
     * production), unlike the {@code remap = false} Xaero members this class mostly targets.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = true)
    private void tacz_sewv$onMapPress(double mouseX, double mouseY, int button,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.MAP_MARKERS_ENABLED.get() || CruisePlot.armed()) return;
        if (tacz_sewv$dropdownOpen()) return; // the right-click menu (or a toggle-menu) owns this click
        if (this.getChildAt(mouseX, mouseY).isPresent()) return; // a Xaero widget, not the map

        if (button == 2) {
            // Middle-press: begin a box. Swallowed so Xaero does not treat MMB as something else;
            // a barely-moved release simply selects nothing (RMB still owns the order menu).
            this.tacz_sewv$boxSelecting = true;
            this.tacz_sewv$boxAx = this.mouseBlockPosX;
            this.tacz_sewv$boxAz = this.mouseBlockPosZ;
            cir.setReturnValue(true);
            return;
        }
        // With <2 selected, leave the left button to Xaero (pan + normal marker selection). With a
        // group selected we take it over entirely: a drag from ANYWHERE (marker or open map) lays out
        // the line, and a plain click edits the selection — judged on release, so units clustered
        // together no longer block the drag from starting on top of one.
        if (button != 0 || MapMarkers.selected().size() < 2) return;

        this.tacz_sewv$orderDragging = true;
        this.tacz_sewv$dragAx = this.mouseBlockPosX;
        this.tacz_sewv$dragAz = this.mouseBlockPosZ;
        this.tacz_sewv$dragAy = tacz_sewv$nodeY();
        this.tacz_sewv$pressMarker = (this.viewed != null
                && this.viewed.getElement() instanceof VehicleMarker m) ? m : null;
        cir.setReturnValue(true); // swallow: Xaero must not start a camera pan on this press
    }

    /**
     * Confirms whichever drag was running. A LEFT line-order drag dispatches the selection along A→B
     * (a barely-moved one deselects and hands panning back). A MIDDLE box-drag adds every own unit
     * inside the box to the selection; a barely-moved one is a no-op (RMB still opens the order menu).
     */
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, remap = true)
    private void tacz_sewv$onMapRelease(double mouseX, double mouseY, int button,
                                        CallbackInfoReturnable<Boolean> cir) {
        // A menu opened between our press and this release (e.g. the right-click order menu): the
        // release belongs to it. Drop any half-started gesture without acting on it.
        if (tacz_sewv$dropdownOpen()) {
            this.tacz_sewv$orderDragging = false;
            this.tacz_sewv$boxSelecting = false;
            this.tacz_sewv$pressMarker = null;
            return;
        }
        if (button == 2 && this.tacz_sewv$boxSelecting) {
            this.tacz_sewv$boxSelecting = false;
            tacz_sewv$applyBoxSelection(mouseX, mouseY);
            cir.setReturnValue(true);
            return;
        }
        if (button != 0 || !this.tacz_sewv$orderDragging) return;
        this.tacz_sewv$orderDragging = false;
        VehicleMarker pressed = this.tacz_sewv$pressMarker;
        this.tacz_sewv$pressMarker = null;

        Vec3 a = new Vec3(this.tacz_sewv$dragAx + 0.5, this.tacz_sewv$dragAy, this.tacz_sewv$dragAz + 0.5);
        Vec3 b = new Vec3(this.mouseBlockPosX + 0.5, tacz_sewv$nodeY(), this.mouseBlockPosZ + 0.5);
        double dx = b.x - a.x, dz = b.z - a.z;
        if (dx * dx + dz * dz < TACZ_SEWV$MIN_DRAG_SQ) {
            // A click, not a drag: edit the selection. On a marker toggle it; on open map deselect
            // everything (which drops below the group threshold and hands panning back).
            if (pressed != null) {
                MapMarkers.toggleSelected(pressed);
            } else {
                MapMarkers.clearSelection();
            }
        } else if (MapMarkers.selected().size() >= 2) {
            OrderPreview.dispatchMoveLine(a, b);
        }
        cir.setReturnValue(true); // swallow: this left press/release pair was ours end to end
    }

    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void tacz_sewv$orderOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        if (!ClientConfig.MAP_MARKERS_ENABLED.get()) return;
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null) return;

        options.addAll(UnitOrderOption.allFor(options.size(), (GuiMap) (Object) this,
                this.rightClickX, this.rightClickY, this.rightClickZ, this.rightClickDim,
                MapMarkers.selected().size()));
    }

    @Inject(method = "render", at = @At("TAIL"), remap = true)
    private void tacz_sewv$drawPlot(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        // The order-preview overlay draws independently of cruise plotting. Standing orders come
        // straight from the synced markers (so they clear themselves when an order is dismissed or
        // overridden); the live drag is the transient line being laid down right now.
        if (ClientConfig.MAP_MARKERS_ENABLED.get()) {
            tacz_sewv$drawStandingPreviews(guiGraphics);
            if (this.tacz_sewv$orderDragging && !CruisePlot.armed()
                    && MapMarkers.selected().size() >= 2) {
                tacz_sewv$drawDragPreview(guiGraphics);
            }
            if (this.tacz_sewv$boxSelecting && !CruisePlot.armed()) {
                tacz_sewv$drawSelectionBox(guiGraphics, mouseX, mouseY);
            }
        }

        boolean armed = CruisePlot.armed();
        if (this.tacz_sewv$confirmButton != null) this.tacz_sewv$confirmButton.visible = armed;
        if (this.tacz_sewv$cancelButton != null) this.tacz_sewv$cancelButton.visible = armed;
        if (!armed) return;

        List<BlockPos> nodes = CruisePlot.nodes();
        int color = ClientConfig.parseColor(ClientConfig.COLOR_PMC.get(), 0xFF55FF55);

        // A cruise is a loop, so the leg back to the first node is drawn too — the route the crew
        // will actually drive, not the sequence of clicks.
        for (int i = 0; i < nodes.size(); i++) {
            int[] from = tacz_sewv$toScreen(nodes.get(i));
            int[] to = tacz_sewv$toScreen(nodes.get((i + 1) % nodes.size()));
            if (nodes.size() > 1) tacz_sewv$drawLeg(guiGraphics, from, to, color);
        }
        for (int i = 0; i < nodes.size(); i++) {
            int[] at = tacz_sewv$toScreen(nodes.get(i));
            guiGraphics.fill(at[0] - 4, at[1] - 4, at[0] + 4, at[1] + 4, 0xFF000000);
            guiGraphics.fill(at[0] - 3, at[1] - 3, at[0] + 3, at[1] + 3, color);
            guiGraphics.drawString(this.font, String.valueOf(i + 1), at[0] + 5, at[1] - 4, color);
        }

        guiGraphics.drawCenteredString(this.font,
                Component.translatable("message.tacz_sewv.cruise.plotting", nodes.size()),
                this.width / 2, this.height - 42, 0xFFFFFFFF);
    }

    /**
     * Height for a plotted node: the map's surface guess, or the player's own Y where the map has
     * none. Never the 32767 sentinel — the drive goal paths TO this position, and a node at y=0
     * would aim the route through bedrock.
     */
    /** True while any Xaero menu is up — the right-click order menu, or the waypoint/player toggles. */
    @Unique
    private boolean tacz_sewv$dropdownOpen() {
        return this.rightClickMenu != null || this.waypointMenu || this.playersMenu;
    }

    @Unique
    private int tacz_sewv$nodeY() {
        if (this.mouseBlockPosY != TACZ_SEWV$NO_HEIGHT) return this.mouseBlockPosY;
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 64 : mc.player.getBlockY();
    }

    /**
     * World XZ to screen pixels. The map's own maths is in window pixels ({@code scale} is a
     * window-pixel zoom), so the GUI-space answer divides by the GUI scale factor — the same
     * {@code screenScale} the map divides by when it sets up its own transform.
     */
    @Unique
    private int[] tacz_sewv$toScreen(BlockPos pos) {
        return tacz_sewv$toScreenXZ(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    /** World XZ (continuous, e.g. an entity position) to screen pixels — the same maths as toScreen. */
    @Unique
    private int[] tacz_sewv$toScreenXZ(double wx, double wz) {
        double px = (wx - this.cameraX) * this.scale / this.screenScale;
        double pz = (wz - this.cameraZ) * this.scale / this.screenScale;
        return new int[]{(int) Math.round(this.width / 2.0 + px), (int) Math.round(this.height / 2.0 + pz)};
    }

    /** A world distance (blocks) as screen pixels at the current zoom — for an area task's radius. */
    @Unique
    private double tacz_sewv$worldToScreen(double blocks) {
        return blocks * this.scale / this.screenScale;
    }

    /** A leg as a chain of dots: no rotated quad, and it reads as a route rather than a border. */
    @Unique
    private void tacz_sewv$drawLeg(GuiGraphics guiGraphics, int[] from, int[] to, int color) {
        int dx = to[0] - from[0];
        int dy = to[1] - from[1];
        int steps = Math.max(Math.abs(dx), Math.abs(dy)) / 6;
        for (int i = 1; i < steps; i++) {
            int x = from[0] + dx * i / steps;
            int y = from[1] + dy * i / steps;
            guiGraphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    /**
     * The faint animated overlay of every own unit's standing order, drawn from the server-synced
     * {@link MarkerOrder} on each marker. Because the data is the sync, a dismissed or overridden
     * order simply stops arriving and the overlay clears itself — there is no client-held state to go
     * stale. Only the map's own dimension is drawn, so a unit in the Nether does not scatter across
     * the Overworld map.
     */
    @Unique
    private void tacz_sewv$drawStandingPreviews(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ResourceKey<Level> dim = mc.player.level().dimension();

        for (VehicleMarker marker : MapMarkers.markers()) {
            MarkerOrder order = marker.order();
            if (order.type() == MarkerOrder.Type.NONE || !dim.equals(marker.dimension())) continue;

            int color = OrderPreview.lowAlpha(VehicleMarkerElements.factionColor(marker.faction()));
            int[] unit = tacz_sewv$toScreenXZ(marker.x(), marker.z());
            switch (order.type()) {
                case MOVE -> {
                    int[] t = tacz_sewv$toScreen(order.target());
                    OrderPreview.dashedLine(guiGraphics, unit[0], unit[1], t[0], t[1], color);
                    OrderPreview.blinkingDot(guiGraphics, t[0], t[1], color);
                }
                case PATROL, SEARCH -> {
                    int[] c = tacz_sewv$toScreen(order.target());
                    OrderPreview.ring(guiGraphics, c[0], c[1], tacz_sewv$worldToScreen(order.radius()), color);
                    OrderPreview.node(guiGraphics, c[0], c[1], color);
                }
                case CRUISE -> {
                    List<BlockPos> route = order.route();
                    for (int i = 0; i < route.size(); i++) {
                        int[] from = tacz_sewv$toScreen(route.get(i));
                        int[] to = tacz_sewv$toScreen(route.get((i + 1) % route.size()));
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

    /** The line being laid down right now: A→B with a pip at each unit's arc-length destination. */
    @Unique
    private void tacz_sewv$drawDragPreview(GuiGraphics guiGraphics) {
        int n = MapMarkers.selected().size();
        Vec3 a = new Vec3(this.tacz_sewv$dragAx + 0.5, this.tacz_sewv$dragAy, this.tacz_sewv$dragAz + 0.5);
        Vec3 b = new Vec3(this.mouseBlockPosX + 0.5, tacz_sewv$nodeY(), this.mouseBlockPosZ + 0.5);
        int color = OrderPreview.lowAlpha(ClientConfig.parseColor(ClientConfig.COLOR_PMC.get(), 0xFF55FF55));

        int[] sa = tacz_sewv$toScreenXZ(a.x, a.z);
        int[] sb = tacz_sewv$toScreenXZ(b.x, b.z);
        OrderPreview.dashedLine(guiGraphics, sa[0], sa[1], sb[0], sb[1], color);
        for (Vec3 p : OrderPreview.arcLengthPoints(a, b, n)) {
            int[] sp = tacz_sewv$toScreenXZ(p.x, p.z);
            OrderPreview.node(guiGraphics, sp[0], sp[1], color);
        }
        guiGraphics.drawString(this.font, String.valueOf(n), sb[0] + 6, sb[1] - 4, color);
    }

    /**
     * Adds every OWN unit inside the drawn box to the selection. World bounds run from point A to the
     * cursor's block; the pixel check against the release cursor is what tells a real box from a mere
     * middle-click (below it this no-ops). Only the map's own
     * dimension counts, and {@link MapMarkers#addSelected} silently ignores anything not yours.
     */
    @Unique
    private void tacz_sewv$applyBoxSelection(double mouseX, double mouseY) {
        int[] a = tacz_sewv$toScreenXZ(this.tacz_sewv$boxAx + 0.5, this.tacz_sewv$boxAz + 0.5);
        if (Math.hypot(mouseX - a[0], mouseY - a[1]) < TACZ_SEWV$BOX_MIN_PX) return; // a click, not a box

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ResourceKey<Level> dim = mc.player.level().dimension();

        double minX = Math.min(this.tacz_sewv$boxAx, this.mouseBlockPosX);
        double maxX = Math.max(this.tacz_sewv$boxAx, this.mouseBlockPosX);
        double minZ = Math.min(this.tacz_sewv$boxAz, this.mouseBlockPosZ);
        double maxZ = Math.max(this.tacz_sewv$boxAz, this.mouseBlockPosZ);
        int caught = 0;
        for (VehicleMarker marker : MapMarkers.markers()) {
            if (!dim.equals(marker.dimension())) continue;
            if (marker.x() >= minX && marker.x() <= maxX && marker.z() >= minZ && marker.z() <= maxZ
                    && MapMarkers.addSelected(marker)) {
                caught++;
            }
        }
        if (caught > 0) tacz_sewv$hint("message.tacz_sewv.map.boxed", MapMarkers.selected().size());
    }

    /** The live rectangle while middle-dragging: point A anchored to the world, the far corner at the cursor. */
    @Unique
    private void tacz_sewv$drawSelectionBox(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int[] a = tacz_sewv$toScreenXZ(this.tacz_sewv$boxAx + 0.5, this.tacz_sewv$boxAz + 0.5);
        int x1 = Math.min(a[0], mouseX), x2 = Math.max(a[0], mouseX);
        int y1 = Math.min(a[1], mouseY), y2 = Math.max(a[1], mouseY);
        int color = OrderPreview.lowAlpha(ClientConfig.parseColor(ClientConfig.COLOR_PMC.get(), 0xFF55FF55));
        guiGraphics.fill(x1, y1, x2, y2, (color & 0x00FFFFFF) | 0x22000000); // faint interior tint
        guiGraphics.fill(x1, y1, x2, y1 + 1, color);                          // top
        guiGraphics.fill(x1, y2 - 1, x2, y2, color);                          // bottom
        guiGraphics.fill(x1, y1, x1 + 1, y2, color);                          // left
        guiGraphics.fill(x2 - 1, y1, x2, y2, color);                          // right
    }

    @Unique
    private static void tacz_sewv$hint(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
