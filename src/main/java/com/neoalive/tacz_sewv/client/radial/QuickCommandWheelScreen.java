package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.client.BoardKeybind;
import com.neoalive.tacz_sewv.client.QuickCommandKeybind;
import com.neoalive.tacz_sewv.client.TdtScreen;
import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketClearBoarding;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketQuickCommand;

/**
 * Far Cry–style radial wheel. Cursor is GLFW-disabled (not vanilla {@code grabMouse}, which would
 * close this Screen). Raw deltas arrive via {@link com.neoalive.tacz_sewv.mixin.client.MixinMouseHandler}.
 */
public final class QuickCommandWheelScreen extends Screen {

    private static final int HUB_FILL = 0xCC0E1218;
    private static final int LABEL = 0xFFE8ECF0;
    private static final int LABEL_HOT = 0xFFFFFFFF;

    /** Per-frame approach rate toward hot/cold (higher = snappier fade). */
    private static final float HOT_FADE_SPEED = 0.28f;
    private static final float MENU_FADE_IN_SPEED = 0.18f;
    private static final float MENU_FADE_OUT_SPEED = 0.22f;

    private final RadialInputState input;
    private boolean cursorDisabled;
    private boolean closing;

    private boolean wasAttackDown;
    private boolean wasUseDown;

    /** 0..1 highlight strength per wedge — lerps toward the current hot index. */
    private float[] wedgeHot = new float[0];
    /** Whole-ring opacity for open / submenu transitions. */
    private float menuAlpha = 0.0f;

    public QuickCommandWheelScreen() {
        super(Component.translatable("gui.tacz_sewv.quick_command_wheel"));
        if (QuickCommandRegistry.rootWedges().isEmpty()) {
            QuickCommandRegistry.init();
        }
        this.input = new RadialInputState(QuickCommandRegistry.rootWedges());
    }

    public RadialInputState inputState() {
        return this.input;
    }

    @Override
    protected void init() {
        disableCursor();
        this.menuAlpha = 0.0f;
    }

    @Override
    public void removed() {
        restoreCursor();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) {
            closeQuiet();
            return;
        }
        // Hard override: key release closes before any click-commit in the same frame.
        // Must use GLFW — KeyMapping.isDown() is false under IN_GAME whenever a Screen is open.
        if (!QuickCommandKeybind.isBoundKeyPhysicallyDown(mc)) {
            closeQuiet();
            return;
        }
        if (!QuickCommandKeybind.hasTerminal(mc.player)) {
            closeQuiet();
            return;
        }

        // SBW cancels MouseButton.Pre in armed seats, which kills both Screen.mouseClicked and
        // KeyMapping.click. Read GLFW button state directly so the wheel still commits while seated.
        long window = mc.getWindow().getWindow();
        boolean attackDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean useDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean attackRising = attackDown && !this.wasAttackDown;
        boolean useRising = useDown && !this.wasUseDown;
        this.wasAttackDown = attackDown;
        this.wasUseDown = useDown;

        if (attackRising) {
            if (hotEnabled()) {
                playUi(ModSounds.INTERACT_BEEP.get());
                onCommit();
            }
            if (this.closing) return;
        }
        if (useRising) {
            playUi(ModSounds.INTERACT_BEEP_BACK.get());
            onPop();
        }
    }

    private static void playUi(net.minecraft.sounds.SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicks are handled in tick() via GLFW so seated banHand still works; swallow here
        // to keep the Screen from propagating into the world (e.g. other keybinds) on close.
        return button == 0 || button == 1 || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // Prefer tick()+GLFW for release — keyReleased can race open. Only close if this is
        // still our binding and GLFW agrees the key is up (avoids spurious closes).
        if (QuickCommandKeybind.OPEN_WHEEL.matches(keyCode, scanCode)
                && this.minecraft != null
                && !QuickCommandKeybind.isBoundKeyPhysicallyDown(this.minecraft)) {
            closeQuiet();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** Called from {@link com.neoalive.tacz_sewv.mixin.client.MixinMouseHandler}. */
    public void feedRawDelta(double dx, double dy) {
        this.input.feedMouseDelta(dx, dy);
    }

    private boolean hotEnabled() {
        int hot = this.input.hotIndex();
        if (hot < 0) return true;
        List<WedgeEntry> wedges = this.input.currentWedges();
        if (hot >= wedges.size()) return true;
        WedgeEntry e = wedges.get(hot);
        if (!(e instanceof WedgeEntry.PipelineEntry leaf)) return true;
        if (!QuickAirClient.isAirClientAction(leaf.pipelineId())) return true;
        return QuickAirClient.isEnabled(leaf.pipelineId());
    }

    private void onCommit() {
        RadialInputState.CommitResult result = this.input.commitHot();
        if (result instanceof RadialInputState.CommitResult.FiredLeaf leaf) {
            if (firePipeline(leaf.pipelineId())) {
                closeQuiet();
            }
            // Client-side reject (no selection, etc.): keep the wheel open.
        } else if (result instanceof RadialInputState.CommitResult.EnteredSubmenu) {
            // Soft fade-out then back in on the new layer.
            this.menuAlpha = 0.25f;
            Arrays.fill(this.wedgeHot, 0.0f);
        }
    }

    private void onPop() {
        if (this.input.popMenu()) {
            closeQuiet();
        } else {
            this.menuAlpha = 0.25f;
            Arrays.fill(this.wedgeHot, 0.0f);
        }
    }

    /** @return {@code true} if the packet was sent / mode armed (caller should close). */
    private boolean firePipeline(String pipelineId) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null || mc.level == null) return false;

        // Route-to-FOB uses FOB assignment lists, not the radius unit pick.
        if (QuickCommandRegistry.ID_QUICK_ROUTE_FOB.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(pipelineId, List.of()));
            return true;
        }

        // Release passenger-only board waiters into the player's current hull.
        if (QuickCommandRegistry.ID_BOARD_QUEUED.equals(pipelineId)) {
            NetworkHandler.CHANNEL.sendToServer(new PacketClearBoarding());
            return true;
        }

        // Patrol / Search & Destroy — same PacketPatrolVehicle path as the TDT (selection + radii).
        if (QuickCommandRegistry.ID_QUICK_PATROL.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_SEARCH.equals(pipelineId)) {
            boolean search = QuickCommandRegistry.ID_QUICK_SEARCH.equals(pipelineId);
            int radius = search ? TdtScreen.searchRadius() : TdtScreen.patrolRadius();
            if (radius < PacketPatrolVehicle.MIN_RADIUS) {
                mc.player.displayClientMessage(
                        Component.translatable("message.tacz_sewv.patrol.min_radius",
                                        PacketPatrolVehicle.MIN_RADIUS)
                                .withStyle(ChatFormatting.GRAY),
                        true);
                return false;
            }
            BoardKeybind.orderAreaTask(radius,
                    search ? IVehiclePatrol.MODE_SEARCH : IVehiclePatrol.MODE_PATROL);
            return true;
        }

        // Air client actions: player rappel + radius takeoff/landing (no PacketQuickCommand).
        if (QuickAirClient.isAirClientAction(pipelineId)) {
            return QuickAirClient.fire(pipelineId);
        }

        List<Integer> units = resolveUnits(mc, pipelineId);
        if (units.isEmpty()) {
            boolean ribbon = QuickCommandRegistry.ID_QUICK_EVAC.equals(pipelineId)
                    && ClientConfig.QUICK_EVAC_PULL_SELECTED_FROM_RIBBON.get();
            String key = ribbon
                    ? "message.tacz_sewv.quick_command.need_selection"
                    : "message.tacz_sewv.quick_command.need_units";
            mc.player.displayClientMessage(
                    Component.translatable(key).withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }

        // Attack That: arm SEM target pick with radius units as the snapshot (same as TDT).
        if (QuickCommandRegistry.ID_QUICK_ATTACK.equals(pipelineId)) {
            TdtSelection.writeSnapshotIds(units);
            net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer.isSelectingTarget = true;
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.select_target")
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return true;
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(pipelineId, units));
        return true;
    }

    /**
     * Default: owned PMCs within the pipeline's radius of the player.
     * Board / entrench / refill / evac stay on-foot-only.
     * Config {@code quickEvacPullSelectedFromRibbon}: ribbon/SEM selection for Quick Evac only.
     */
    private static List<Integer> resolveUnits(Minecraft mc, String pipelineId) {
        Player player = mc.player;
        boolean onFootOnly = QuickCommandRegistry.requiresOnFoot(pipelineId);
        boolean ribbonEvac = QuickCommandRegistry.ID_QUICK_EVAC.equals(pipelineId)
                && ClientConfig.QUICK_EVAC_PULL_SELECTED_FROM_RIBBON.get();
        if (ribbonEvac) {
            List<Integer> selected = new ArrayList<>(
                    TdtSelection.resolve(SewvConfig.BOARD_SCAN_RADIUS.get()));
            return filterOwned(mc, player, selected, onFootOnly);
        }

        double radius = QuickCommandRegistry.ID_QUICK_EVAC.equals(pipelineId)
                ? SewvConfig.QUICK_EVAC_BOARD_RADIUS.get()
                : SewvConfig.QUICK_LAND_RADIUS.get();
        AABB box = player.getBoundingBox().inflate(radius);
        List<Integer> nearby = new ArrayList<>();
        for (PmcUnitEntity pmc : mc.level.getEntitiesOfClass(PmcUnitEntity.class, box,
                u -> u.isAlive()
                        && com.neoalive.tacz_sewv.invasion.PmcOwnerSupport.isOwner(player, u)
                        && (!onFootOnly || u.getVehicle() == null))) {
            nearby.add(pmc.getId());
        }
        return nearby;
    }

    private static List<Integer> filterOwned(Minecraft mc, Player player, List<Integer> ids,
                                             boolean onFootOnly) {
        List<Integer> out = new ArrayList<>();
        for (int id : ids) {
            Entity e = mc.level.getEntity(id);
            if (!(e instanceof PmcUnitEntity pmc)) continue;
            if (!com.neoalive.tacz_sewv.invasion.PmcOwnerSupport.isOwner(player, pmc)) continue;
            if (onFootOnly && pmc.getVehicle() != null) continue;
            out.add(id);
        }
        return out;
    }

    private void closeQuiet() {
        if (this.closing) return;
        this.closing = true;
        restoreCursor();
        Minecraft mc = this.minecraft;
        if (mc != null && mc.screen == this) {
            mc.setScreen(null);
        }
    }

    private void disableCursor() {
        Minecraft mc = this.minecraft;
        if (mc == null || this.cursorDisabled) return;
        long window = mc.getWindow().getWindow();
        // CURSOR_DISABLED without MouseHandler.grabMouse — that API would setScreen(null).
        InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_DISABLED,
                mc.getWindow().getScreenWidth() / 2.0,
                mc.getWindow().getScreenHeight() / 2.0);
        this.cursorDisabled = true;
    }

    private void restoreCursor() {
        Minecraft mc = this.minecraft;
        if (mc == null || !this.cursorDisabled) return;
        long window = mc.getWindow().getWindow();
        InputConstants.grabOrReleaseMouse(window, InputConstants.CURSOR_NORMAL,
                mc.getWindow().getScreenWidth() / 2.0,
                mc.getWindow().getScreenHeight() / 2.0);
        this.cursorDisabled = false;
    }

    private void tickFades(int wedgeCount, int hotIndex) {
        if (wedgeCount != this.wedgeHot.length) {
            this.wedgeHot = new float[wedgeCount];
        }
        // Fade the ring in (or recover after a submenu dip).
        float menuTarget = 1.0f;
        float menuSpeed = this.menuAlpha < menuTarget ? MENU_FADE_IN_SPEED : MENU_FADE_OUT_SPEED;
        this.menuAlpha = Mth.lerp(menuSpeed, this.menuAlpha, menuTarget);

        for (int i = 0; i < wedgeCount; i++) {
            float target = i == hotIndex ? 1.0f : 0.0f;
            this.wedgeHot[i] = Mth.lerp(HOT_FADE_SPEED, this.wedgeHot[i], target);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No dim overlay — keep the world readable behind the wheel.
        int cx = this.width / 2;
        int cy = this.height / 2;
        // Compact footprint; ring thickness ~2× the previous thin annulus.
        int outer = Math.min(this.width, this.height) / 6;
        int inner = Math.max(8, (int) (outer * 0.24));

        List<WedgeEntry> wedges = this.input.currentWedges();
        tickFades(wedges.size(), this.input.hotIndex());
        boolean[] enabled = new boolean[wedges.size()];
        for (int i = 0; i < wedges.size(); i++) {
            WedgeEntry e = wedges.get(i);
            if (e instanceof WedgeEntry.PipelineEntry leaf
                    && QuickAirClient.isAirClientAction(leaf.pipelineId())) {
                enabled[i] = QuickAirClient.isEnabled(leaf.pipelineId());
            } else {
                enabled[i] = true;
            }
        }
        RadialWheelDraw.renderRing(g, this.font, cx, cy, inner, outer, wedges, this.wedgeHot,
                this.menuAlpha, HUB_FILL, LABEL, LABEL_HOT, enabled);

        super.render(g, mouseX, mouseY, partialTick);
    }
}
