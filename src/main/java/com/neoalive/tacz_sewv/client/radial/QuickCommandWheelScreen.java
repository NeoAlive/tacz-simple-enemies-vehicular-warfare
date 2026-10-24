package com.neoalive.tacz_sewv.client.radial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.nekoyuni.SimpleEnemyMod.client.gui.overlay.CommanderOverlayRenderer;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IFormationMember;
import com.neoalive.tacz_sewv.bridge.IVehiclePatrol;
import com.neoalive.tacz_sewv.client.BoardKeybind;
import com.neoalive.tacz_sewv.client.ClientEvents;
import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.client.QuickCommandKeybind;
import com.neoalive.tacz_sewv.client.TdtSelection;
import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.entity.ai.support.FormationAnchorMode;
import com.neoalive.tacz_sewv.entity.ai.support.FormationComposition;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleFormation;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModSounds;
import com.neoalive.tacz_sewv.item.HandheldRadioItem;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.map.VehicleMarker;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketClearBoarding;
import com.neoalive.tacz_sewv.network.PacketExitPlatoon;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketQuickCommand;
import com.neoalive.tacz_sewv.network.PacketReachGuard;
import com.neoalive.tacz_sewv.network.PacketToggleAutoOrders;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/**
 * Far Cry–style radial wheel — the only place orders are given (the TDT is the identity screen).
 * Cursor is GLFW-disabled (not vanilla {@code grabMouse}, which would close this Screen). Raw
 * deltas arrive via {@link com.neoalive.tacz_sewv.mixin.client.MixinMouseHandler}.
 *
 * <p>Who an order reaches is {@link WheelScope}: the selection if there is one, else owned units
 * nearby. The selection is changed by the A / N / L keys while the wheel is held and by the Select
 * category, and shown under the ring ({@link SelectionBarDraw}).
 */
public final class QuickCommandWheelScreen extends Screen {

    private static final int HUB_FILL = 0xCC0E1218;
    private static final int LABEL = 0xFFE8ECF0;
    private static final int LABEL_HOT = 0xFFFFFFFF;

    private static final float STRETCH_STEP = 0.1f;
    private static final float FILTER_LERP_SPEED = 0.28f;
    private static float formationWidth = IFormationMember.DEFAULT_STRETCH;
    private static float formationLength = IFormationMember.DEFAULT_STRETCH;
    private static FormationComposition.Kind formationFilter = FormationComposition.Kind.GROUND;
    /** Mortar Shelling delay (s, 0 = off); seeded from the carried radio on every open. */
    private static int supportDelay = 0;
    private static final int MAX_SUPPORT_DELAY = 120;
    /** Reach of the crosshair's entity pick — SEM's own commander raytrace distance. */
    private static final double AIM_ENTITY_RANGE = 50.0;

    /** Per-frame approach rate toward hot/cold (higher = snappier fade). */
    private static final float HOT_FADE_SPEED = 0.28f;
    private static final float MENU_FADE_IN_SPEED = 0.18f;
    private static final float MENU_FADE_OUT_SPEED = 0.22f;

    /**
     * The crosshair when the wheel opened. The view is frozen while the wheel is up, so this is
     * the aim the player chose — Board, Board as Passenger and Entrench act on it.
     */
    private record Aim(@Nullable Entity entity, @Nullable BlockPos block) {}

    private final RadialInputState input;
    private final Aim aim;
    private boolean cursorDisabled;
    private boolean closing;
    /** Live Select closed the wheel: the selection glow must survive {@link #removed}. */
    private boolean handedToLiveSelect;
    private int nearbyCount;

    private boolean wasAttackDown;
    private boolean wasUseDown;
    /** Start true so a toggle input already held when the wheel opens is not read as a press. */
    private boolean wasTabDown = true;
    private boolean wasMiddleDown = true;
    private boolean wasAllDown = true;
    private boolean wasNoneDown = true;
    private boolean wasLiveDown = true;

    /** 0..1 highlight strength per wedge — lerps toward the current hot index. */
    private float[] wedgeHot = new float[0];
    /** Whole-ring opacity for open / submenu transitions. */
    private float menuAlpha = 0.0f;
    /** Continuous highlight index for the INFY/GRND/SHIP filter strip. */
    private float filterLerp = FormationComposition.Kind.GROUND.ordinal();

    public QuickCommandWheelScreen() {
        super(Component.translatable("gui.tacz_sewv.quick_command_wheel"));
        if (QuickCommandRegistry.rootWedges().isEmpty()) {
            QuickCommandRegistry.init();
        }
        // Opening the wheel ends a Live Selection: what was picked is what the next order hits.
        if (ClientEvents.isLiveSelecting()) ClientEvents.endLiveSelectionQuietly();
        TdtSelection.scan();
        this.aim = captureAim();
        this.input = new RadialInputState(visibleRoot());
        refreshNearbyCount();
    }

    /**
     * Support is only offered to a player who could make the call (a radio on them, or creative);
     * Select only when there is something to select. Select sits just before the root Cancel.
     */
    private static List<WedgeEntry> visibleRoot() {
        List<WedgeEntry> root = new ArrayList<>(QuickCommandRegistry.rootWedges());
        Player player = Minecraft.getInstance().player;
        if (player != null && HandheldRadioItem.canCall(player)) {
            ItemStack radio = HandheldRadioItem.findCarried(player);
            if (!radio.isEmpty()) supportDelay = RadioSettings.read(radio).delaySeconds();
        } else {
            root.remove(QuickCommandRegistry.SUPPORT_CATEGORY);
        }
        WedgeEntry.CategoryEntry select = SelectLeaves.category();
        if (select != null) root.add(root.size() - 1, select);
        return root;
    }

    private static Aim captureAim() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return new Aim(null, null);
        Entity entity = CommanderRayTrace.rayTraceEntity(player, AIM_ENTITY_RANGE);
        HitResult hit = player.pick(ClientEvents.BLOCK_PICK_RANGE, 0.0F, false);
        BlockPos block = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
                ? bhr.getBlockPos() : null;
        return new Aim(BoardKeybind.isBoardable(entity) ? entity : null, block);
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
        if (!this.handedToLiveSelect) TdtSelection.clearGlow();
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
        if (!QuickCommandKeybind.canUseQuickWheel(mc.player)) {
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
        // Queue mode toggle: middle click (mouse-only, like the rest of the wheel) or Tab. Both
        // polled like the other buttons: Screen.keyPressed never sees Tab while the wheel is up.
        boolean tabDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
                == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        boolean middleDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,
                org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if ((tabDown && !this.wasTabDown) || (middleDown && !this.wasMiddleDown)) {
            OrderQueueClient.toggle();
            playUi(OrderQueueClient.isOn() ? ModSounds.INTERACT_BEEP.get() : ModSounds.INTERACT_BEEP_BACK.get());
        }
        this.wasTabDown = tabDown;
        this.wasMiddleDown = middleDown;

        if (pollSelectionKeys(mc)) return;
        refreshNearbyCount();

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

    /**
     * All / None / Live Select, read by rising edge like Tab. The keys are GUI-context mappings,
     * so they never steal an in-game binding while the wheel is closed.
     *
     * @return whether the wheel closed (Live Select hands over to the in-world pick)
     */
    private boolean pollSelectionKeys(Minecraft mc) {
        boolean all = QuickCommandKeybind.isPhysicallyDown(mc, QuickCommandKeybind.SELECT_ALL);
        boolean none = QuickCommandKeybind.isPhysicallyDown(mc, QuickCommandKeybind.SELECT_NONE);
        boolean live = QuickCommandKeybind.isPhysicallyDown(mc, QuickCommandKeybind.LIVE_SELECT);
        boolean allRising = all && !this.wasAllDown;
        boolean noneRising = none && !this.wasNoneDown;
        boolean liveRising = live && !this.wasLiveDown;
        this.wasAllDown = all;
        this.wasNoneDown = none;
        this.wasLiveDown = live;

        if (allRising) {
            TdtSelection.toggleSelectAll();
            playUi(ModSounds.INTERACT_BEEP.get());
        }
        if (noneRising && TdtSelection.selectedCount() > 0) {
            TdtSelection.deselectAll();
            playUi(ModSounds.INTERACT_BEEP_BACK.get());
        }
        if (liveRising) {
            playUi(ModSounds.INTERACT_BEEP.get());
            this.handedToLiveSelect = true;
            closeQuiet();
            ClientEvents.armLiveSelection();
            return true;
        }
        return false;
    }

    private void refreshNearbyCount() {
        this.nearbyCount = WheelScope.explicit() ? 0
                : WheelScope.units(false, WheelScope.nearbyRadius(), null).size();
    }

    private static void playUi(net.minecraft.sounds.SoundEvent sound) {
        playUi(sound, 1.0F);
    }

    private static void playUi(net.minecraft.sounds.SoundEvent sound, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    private static float scalePitch() {
        return 0.92f + (float) (Math.random() * 0.16);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicks are handled in tick() via GLFW so seated banHand still works; swallow here
        // to keep the Screen from propagating into the world (e.g. other keybinds) on close.
        return button == 0 || button == 1 || button == 2 || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Tab is toggled from tick() via GLFW; only swallow vanilla's focus cycling here.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        Minecraft mc = this.minecraft;
        long window = mc != null ? mc.getWindow().getWindow() : 0L;
        boolean ctrl = mc != null && (InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL));
        boolean shift = mc != null && (InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT));
        int dir = delta > 0.0 ? 1 : -1;
        // Ctrl+scroll steps the hovered leaf's value — and only that leaf's, so it can never move
        // a setting the player is not looking at. In a submenu that has values, Ctrl+scroll over
        // any other leaf is swallowed rather than falling through to the queue skip.
        if (ctrl && scaleLeafIn(this.input.currentWedges()) != null) {
            String hot = hotPipelineId();
            if (hot != null && stepScale(hot, dir, shift)) {
                playUi(ModSounds.SCALE.get(), scalePitch());
            }
            return true;
        }
        // Scroll down skips the running queued order — except in Formation, which owns the wheel.
        if (!inFormationSubmenu()) {
            if (delta >= 0.0 || !OrderQueueClient.isOn() || OrderQueueClient.labels().isEmpty()) {
                return super.mouseScrolled(mouseX, mouseY, delta);
            }
            OrderQueueClient.skip();
            playUi(ModSounds.SCALE.get(), scalePitch());
            return true;
        }
        if (hotFormationShape() == null) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }

        if (ctrl) {
            formationFilter = formationFilter.cycle(dir);
        } else {
            float step = dir * STRETCH_STEP;
            if (shift) {
                formationWidth = IFormationMember.clampStretch(formationWidth + step);
            } else {
                formationLength = IFormationMember.clampStretch(formationLength + step);
            }
        }
        playUi(ModSounds.SCALE.get(), scalePitch());
        return true;
    }

    /** @return whether a value moved */
    private static boolean stepScale(String pipelineId, int dir, boolean coarse) {
        if (QuickCommandRegistry.ID_SUPPORT_MORTAR.equals(pipelineId)) {
            int next = RadioSettings.stepDelay(supportDelay, dir);
            boolean moved = next != supportDelay;
            supportDelay = next;
            return moved;
        }
        OrderScales.Scale scale = OrderScales.forPipeline(pipelineId);
        return scale != null && OrderScales.step(scale, dir, coarse);
    }

    private static boolean hasScale(String pipelineId) {
        return QuickCommandRegistry.ID_SUPPORT_MORTAR.equals(pipelineId)
                || OrderScales.forPipeline(pipelineId) != null;
    }

    /** The first leaf in {@code wedges} that carries a value, or null. */
    @Nullable
    private static WedgeEntry.PipelineEntry scaleLeafIn(List<WedgeEntry> wedges) {
        for (WedgeEntry e : wedges) {
            if (e instanceof WedgeEntry.PipelineEntry leaf && hasScale(leaf.pipelineId())) return leaf;
        }
        return null;
    }

    private boolean hotEnabled() {
        String id = hotPipelineId();
        return id == null || isEnabled(id);
    }

    /** Whether a leaf can fire right now; a disabled leaf is drawn grey and ignores clicks. */
    private boolean isEnabled(String pipelineId) {
        if (QuickAirClient.isAirClientAction(pipelineId)) return QuickAirClient.isEnabled(pipelineId);
        return switch (pipelineId) {
            case QuickCommandRegistry.ID_BOARD_PASSENGER -> this.aim.entity() != null;
            case QuickCommandRegistry.ID_REACH_GUARD -> reachGuardKnown();
            // Releases waiters into the player's own hull, so it means nothing on foot.
            case QuickCommandRegistry.ID_BOARD_QUEUED -> this.minecraft != null && this.minecraft.player != null
                    && this.minecraft.player.getVehicle() instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
            default -> true;
        };
    }

    /**
     * Reach Guard needs a guard position somewhere. While none of the player's hulls is known to the
     * client yet (no marker sync), stay enabled and let the server answer.
     */
    private static boolean reachGuardKnown() {
        boolean knownOwn = false;
        for (VehicleMarker m : MapMarkers.markers()) {
            if (m.allegiance() != VehicleMarker.Allegiance.OWN) continue;
            if (m.hasGuard()) return true;
            knownOwn = true;
        }
        return !knownOwn;
    }

    private void onCommit() {
        String hotLabel = hotLeafLabel();
        RadialInputState.CommitResult result = this.input.commitHot();
        if (result instanceof RadialInputState.CommitResult.FiredLeaf leaf) {
            String id = leaf.pipelineId();
            // A Select leaf toggles a group and keeps the wheel open: pick several, then go order.
            if (QuickCommandRegistry.isSelectLeaf(id)) {
                SelectLeaves.toggle(id);
                return;
            }
            if (OrderQueueClient.queueable(id) && this.minecraft != null) {
                // Queue mode: the order's packets are bracketed and held server-side. The wheel
                // stays open to queue more, unless the order needs a world pick first.
                List<Integer> units = QuickAirClient.isAirClientAction(id)
                        ? QuickAirClient.pilotsFor(id)
                        : WheelScope.units(id, null);
                OrderQueueClient.begin(id, hotLabel, units);
                boolean fired = firePipeline(id);
                OrderQueueClient.endOrAwaitPick();
                if (fired && (CommanderOverlayRenderer.isSelectingPosition
                        || CommanderOverlayRenderer.isSelectingTarget
                        || ClientEvents.hasPendingOrderPick())) {
                    closeQuiet();
                }
            } else if (firePipeline(id)) {
                closeQuiet();
            }
            // Client-side reject (no units, etc.): keep the wheel open.
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

        switch (pipelineId) {
            // Player-level orders: the server resolves who (FOB lists, the nearest medic, own hull).
            case QuickCommandRegistry.ID_QUICK_ROUTE_FOB, QuickCommandRegistry.ID_REVIVE_CALL -> {
                NetworkHandler.CHANNEL.sendToServer(new PacketQuickCommand(pipelineId, List.of()));
                return true;
            }
            // Release passenger-only board waiters into the player's current hull.
            case QuickCommandRegistry.ID_BOARD_QUEUED -> {
                NetworkHandler.CHANNEL.sendToServer(new PacketClearBoarding());
                return true;
            }
            case QuickCommandRegistry.ID_QUICK_PATROL -> {
                return BoardKeybind.orderAreaTask(OrderScales.patrolRadius(), IVehiclePatrol.MODE_PATROL);
            }
            case QuickCommandRegistry.ID_QUICK_SEARCH -> {
                return BoardKeybind.orderAreaTask(OrderScales.searchRadius(), IVehiclePatrol.MODE_SEARCH);
            }
            case QuickCommandRegistry.ID_DISMISS -> {
                return BoardKeybind.orderAreaTask(0, PacketPatrolVehicle.MODE_DISMISS);
            }
            case QuickCommandRegistry.ID_BAIL_OUT -> {
                return BoardKeybind.orderBailOut();
            }
            case QuickCommandRegistry.ID_PARATROOP -> {
                return BoardKeybind.orderParatroop();
            }
            case QuickCommandRegistry.ID_REACH_GUARD -> {
                return BoardKeybind.withOwnedUnits(null, "message.tacz_sewv.guard.reach.none",
                        (player, ids) -> NetworkHandler.CHANNEL.sendToServer(new PacketReachGuard(ids)));
            }
            // Pick modes: armed now, completed by the next in-world click once the wheel closes.
            case QuickCommandRegistry.ID_ESCORT -> {
                return ClientEvents.armEscort();
            }
            case QuickCommandRegistry.ID_SET_GUARD -> {
                return ClientEvents.armGuardPosition();
            }
            case QuickCommandRegistry.ID_TOW -> {
                return ClientEvents.armTowRecovery();
            }
            case QuickCommandRegistry.ID_PLATOON_JOIN -> {
                return ClientEvents.armJoinPlatoon();
            }
            case QuickCommandRegistry.ID_PLATOON_EXIT -> {
                return BoardKeybind.withOwnedUnits(null, "message.tacz_sewv.platoon.exit.none",
                        (player, ids) -> NetworkHandler.CHANNEL.sendToServer(new PacketExitPlatoon(ids)));
            }
            case QuickCommandRegistry.ID_PLATOON_AUTO_ORDERS -> {
                return BoardKeybind.withOwnedUnits(pmc -> pmc instanceof PmcCommanderEntity,
                        "message.tacz_sewv.platoon.auto_orders.none",
                        (player, ids) -> NetworkHandler.CHANNEL.sendToServer(new PacketToggleAutoOrders(ids)));
            }
            // Board the hull under the crosshair; with nothing aimed, Board auto-assigns nearby seats.
            case QuickCommandRegistry.ID_BOARD_PASSENGER -> {
                return this.aim.entity() != null && BoardKeybind.orderBoard(this.aim.entity(), true);
            }
            case QuickCommandRegistry.ID_QUICK_BOARD -> {
                if (this.aim.entity() != null) return BoardKeybind.orderBoard(this.aim.entity(), false);
            }
            default -> {
            }
        }

        // Support: a radio call at the crosshair (the view is frozen while the wheel is open).
        RadioSettings.State support = QuickCommandRegistry.supportSettings(pipelineId, supportDelay);
        if (support != null) {
            ClientEvents.callRadio(support);
            return true;
        }

        // Air client actions: player rappel + takeoff/landing (no PacketQuickCommand).
        if (QuickAirClient.isAirClientAction(pipelineId)) {
            return QuickAirClient.fire(pipelineId);
        }

        List<Integer> units = WheelScope.units(pipelineId, null);
        if (units.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.translatable(WheelScope.emptyKey("message.tacz_sewv.quick_command.need_selection"))
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }

        // Attack That: arm SEM target pick with the scope's units as the snapshot.
        if (QuickCommandRegistry.ID_QUICK_ATTACK.equals(pipelineId)) {
            TdtSelection.writeSnapshotIds(units);
            CommanderOverlayRenderer.isSelectingTarget = true;
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.select_target")
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return true;
        }

        // Move To: arm SEM's position pick with the scope's units, same shape as Attack That.
        if (QuickCommandRegistry.ID_QUICK_MOVE.equals(pipelineId)) {
            TdtSelection.writeSnapshotIds(units);
            CommanderOverlayRenderer.isSelectingPosition = true;
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.tdt.select_position")
                            .withStyle(ChatFormatting.GREEN),
                    true);
            return true;
        }

        // Formation: PacketVehicleFormation with stretch — not PacketQuickCommand.
        if (QuickCommandRegistry.isFormationPipeline(pipelineId)) {
            return fireFormation(mc, pipelineId, units);
        }

        // Entrench resolves the aimed block server-side (trench networks are server data).
        BlockPos aimBlock = QuickCommandRegistry.ID_QUICK_ENTRENCH.equals(pipelineId) ? this.aim.block() : null;
        NetworkHandler.CHANNEL.sendToServer(
                new PacketQuickCommand(pipelineId, units, WheelScope.explicit(), aimBlock));
        return true;
    }

    private boolean fireFormation(Minecraft mc, String pipelineId, List<Integer> unitIds) {
        FormationShape shape = QuickCommandRegistry.formationShapeOf(pipelineId);
        if (shape == null || mc.player == null || mc.level == null) return false;
        List<PmcUnitEntity> all = resolvePmcList(mc, unitIds);
        List<PmcUnitEntity> units = FormationComposition.filter(all, formationFilter);
        if (units.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.translatable(FormationComposition.MSG_NONE_MATCH)
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        // Filter already enforced purity; resolve is belt-and-suspenders for the packet path.
        FormationComposition.Kind kind = FormationComposition.resolve(units);
        if (kind == null) {
            mc.player.displayClientMessage(
                    Component.translatable(FormationComposition.MSG_INVALID)
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        // The heading the player is looking along, snapped to 45° so a formation lines up with the obvious
        // compass points; the axis is that heading's nearest cardinal and still marks the unit as formed.
        float yaw = Mth.wrapDegrees(Math.round(mc.player.getYRot() / 45.0f) * 45.0f);
        Direction axis = Direction.fromYRot(yaw);
        // Alt anchors the formation where the player stands now instead of following them.
        long window = mc.getWindow().getWindow();
        FormationAnchorMode anchorMode = InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT)
                ? FormationAnchorMode.POSITION : FormationAnchorMode.PLAYER;
        int rowSize = PacketVehicleFormation.DEFAULT_ROW_SIZE;
        double baseline = FormationComposition.baselineSpacing(kind);
        int slots = VehicleFormation.slotCountFor(units);
        if (VehicleFormation.slotsOverlap(mc.player.position(), axis, shape, slots, rowSize,
                baseline, formationWidth, formationLength)) {
            mc.player.displayClientMessage(
                    Component.translatable(FormationComposition.MSG_OVERLAP)
                            .withStyle(ChatFormatting.GRAY),
                    true);
            return false;
        }
        List<Integer> filteredIds = new ArrayList<>(units.size());
        for (PmcUnitEntity pmc : units) filteredIds.add(pmc.getId());
        BoardKeybind.orderFormation(filteredIds, shape, IFormationMember.axisOf(axis), yaw, rowSize,
                formationWidth, formationLength, anchorMode);
        return true;
    }

    private static List<PmcUnitEntity> resolvePmcList(Minecraft mc, List<Integer> unitIds) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (int id : unitIds) {
            Entity e = mc.level.getEntity(id);
            if (e instanceof PmcUnitEntity pmc) out.add(pmc);
        }
        return out;
    }

    private String hotLeafLabel() {
        int hot = this.input.hotIndex();
        List<WedgeEntry> wedges = this.input.currentWedges();
        return hot >= 0 && hot < wedges.size() ? wedges.get(hot).label() : "";
    }

    private boolean inFormationSubmenu() {
        if (this.input.depth() <= 1) return false;
        for (WedgeEntry e : this.input.currentWedges()) {
            if (e instanceof WedgeEntry.PipelineEntry leaf
                    && QuickCommandRegistry.isFormationPipeline(leaf.pipelineId())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private String hotPipelineId() {
        int hot = this.input.hotIndex();
        List<WedgeEntry> wedges = this.input.currentWedges();
        if (hot < 0 || hot >= wedges.size()) return null;
        return wedges.get(hot) instanceof WedgeEntry.PipelineEntry leaf ? leaf.pipelineId() : null;
    }

    @Nullable
    private FormationShape hotFormationShape() {
        String id = hotPipelineId();
        return id == null ? null : QuickCommandRegistry.formationShapeOf(id);
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
        this.filterLerp = Mth.lerp(FILTER_LERP_SPEED, this.filterLerp,
                (float) formationFilter.ordinal());
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
        boolean[] checked = new boolean[wedges.size()];
        for (int i = 0; i < wedges.size(); i++) {
            if (wedges.get(i) instanceof WedgeEntry.PipelineEntry leaf) {
                enabled[i] = isEnabled(leaf.pipelineId());
                checked[i] = QuickCommandRegistry.isSelectLeaf(leaf.pipelineId())
                        && SelectLeaves.checked(leaf.pipelineId());
            } else {
                enabled[i] = true;
            }
        }
        RadialWheelDraw.renderRing(g, this.font, cx, cy, inner, outer, wedges, this.wedgeHot,
                this.menuAlpha, LABEL, LABEL_HOT, enabled, checked,
                this.input.accumX(), this.input.accumY());

        FormationShape previewShape = hotFormationShape();
        if (inFormationSubmenu() && previewShape != null && this.minecraft != null
                && this.minecraft.player != null && this.minecraft.level != null) {
            List<Integer> ids = WheelScope.units(QuickCommandRegistry.ID_FORM_WEDGE, null);
            List<PmcUnitEntity> filtered = FormationComposition.filter(
                    resolvePmcList(this.minecraft, ids), formationFilter);
            int slots = VehicleFormation.slotCountFor(filtered);
            double baseline = FormationComposition.baselineSpacing(formationFilter);
            FormationPreviewDraw.render(g, this.font, cx, cy, outer, this.menuAlpha,
                    previewShape, slots, baseline, formationWidth, formationLength,
                    PacketVehicleFormation.DEFAULT_ROW_SIZE, formationFilter, this.filterLerp,
                    HUB_FILL, LABEL_HOT);
        }

        renderScaleCard(g, cx, cy, outer, wedges);

        if (OrderQueueClient.isOn()) {
            OrderQueueDraw.renderBox(g, this.font, cx, cy, outer, this.menuAlpha,
                    OrderQueueClient.labels(), HUB_FILL, LABEL);
        }
        int labelReach = RadialWheelDraw.labelReach(outer);
        OrderQueueDraw.renderModeText(g, this.font, cx, cy, labelReach,
                this.menuAlpha, OrderQueueClient.isOn(), OrderQueueClient.toggledAtMs());
        SelectionBarDraw.render(g, this.font, cx, cy, labelReach, this.menuAlpha,
                TdtSelection.selectedCount(), this.nearbyCount, QuickCommandKeybind.SELECT_ALL,
                QuickCommandKeybind.SELECT_NONE, QuickCommandKeybind.LIVE_SELECT, HUB_FILL, LABEL);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * The value card for this submenu: the hovered leaf's when it carries one, otherwise the
     * first value leaf's, dimmed — so a value is never shown as if it belonged to the hovered order.
     */
    private void renderScaleCard(GuiGraphics g, int cx, int cy, int outer, List<WedgeEntry> wedges) {
        WedgeEntry.PipelineEntry shown = scaleLeafIn(wedges);
        if (shown == null) return;
        String hot = hotPipelineId();
        boolean live = hot != null && hasScale(hot);
        if (live) {
            for (WedgeEntry e : wedges) {
                if (e instanceof WedgeEntry.PipelineEntry leaf && leaf.pipelineId().equals(hot)) shown = leaf;
            }
        }
        int widest = 0;
        for (WedgeEntry e : wedges) widest = Math.max(widest, this.font.width(e.label()));
        ScaleCardDraw.render(g, this.font, cx, cy, this.width, outer, widest, this.menuAlpha,
                cardFor(shown.pipelineId()), live, shown.accentRgb(), HUB_FILL, LABEL_HOT);
    }

    private static ScaleCardDraw.Card cardFor(String pipelineId) {
        if (QuickCommandRegistry.ID_SUPPORT_MORTAR.equals(pipelineId)) {
            String value = supportDelay <= 0
                    ? I18n.get("gui.tacz_sewv.support.delay.off")
                    : I18n.get("gui.tacz_sewv.support.delay.seconds", supportDelay);
            return new ScaleCardDraw.Card(I18n.get("gui.tacz_sewv.support.delay.label"), value,
                    I18n.get("gui.tacz_sewv.support.delay.seconds", MAX_SUPPORT_DELAY),
                    I18n.get("gui.tacz_sewv.support.delay.mortar_only"),
                    supportDelay / (float) MAX_SUPPORT_DELAY, supportDelay > 0);
        }
        OrderScales.Scale scale = OrderScales.forPipeline(pipelineId);
        if (scale == null) throw new IllegalArgumentException(pipelineId);
        boolean altitude = scale == OrderScales.Scale.ALTITUDE;
        String title = altitude
                ? I18n.get("gui.tacz_sewv.wheel.scale.altitude")
                : I18n.get("gui.tacz_sewv.wheel.scale.radius");
        String tag = I18n.get("gui.tacz_sewv.wheel.scale.tag." + scale.name().toLowerCase(java.util.Locale.ROOT));
        int value = OrderScales.get(scale);
        return new ScaleCardDraw.Card(title, I18n.get("gui.tacz_sewv.wheel.scale.blocks", value),
                I18n.get("gui.tacz_sewv.wheel.scale.blocks", scale.max), tag, scale.fraction(value), true);
    }
}
