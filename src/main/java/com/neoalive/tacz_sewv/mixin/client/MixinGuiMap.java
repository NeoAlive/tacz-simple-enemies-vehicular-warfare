package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.xaero.CruisePlot;
import com.neoalive.tacz_sewv.client.xaero.UnitOrderOption;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.VehicleMarker;
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

    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void tacz_sewv$orderOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        if (!SewvConfig.MAP_MARKERS_ENABLED.get()) return;
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null) return;

        options.addAll(UnitOrderOption.allFor(options.size(), (GuiMap) (Object) this,
                this.rightClickX, this.rightClickY, this.rightClickZ, this.rightClickDim,
                MapMarkers.selected().size()));
    }

    @Inject(method = "render", at = @At("TAIL"), remap = true)
    private void tacz_sewv$drawPlot(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        boolean armed = CruisePlot.armed();
        if (this.tacz_sewv$confirmButton != null) this.tacz_sewv$confirmButton.visible = armed;
        if (this.tacz_sewv$cancelButton != null) this.tacz_sewv$cancelButton.visible = armed;
        if (!armed) return;

        List<BlockPos> nodes = CruisePlot.nodes();
        int color = SewvConfig.parseColor(SewvConfig.COLOR_PMC.get(), 0xFF55FF55);

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
        double px = (pos.getX() + 0.5 - this.cameraX) * this.scale / this.screenScale;
        double pz = (pos.getZ() + 0.5 - this.cameraZ) * this.scale / this.screenScale;
        return new int[]{(int) Math.round(this.width / 2.0 + px), (int) Math.round(this.height / 2.0 + pz)};
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

    @Unique
    private static void tacz_sewv$hint(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
