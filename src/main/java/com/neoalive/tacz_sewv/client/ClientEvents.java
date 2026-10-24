package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.client.radial.WheelScope;
import com.neoalive.tacz_sewv.client.skin.CamoClient;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinClient;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.item.RadioSettings;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketEscort;
import com.neoalive.tacz_sewv.network.PacketJoinPlatoon;
import com.neoalive.tacz_sewv.network.PacketRadioCommand;
import com.neoalive.tacz_sewv.network.PacketSetGuardPosition;
import com.neoalive.tacz_sewv.network.PacketTowRecovery;

// FORGE bus, client dist: opens the Tactical Data Terminal (identity), and runs the in-world pick
// modes the Quick Wheel arms (Escort / Tow / Guard / Join Platoon / Live Selection).
@EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public class ClientEvents {

    /** Reach for picking the vehicle to escort — SEM's own selection raytrace distance. */
    private static final double ESCORT_PICK_RANGE = 50.0;
    /** Live Selection / escort entity pick — same as SEM commander reach. */
    private static final double LIVE_PICK_RANGE = 50.0;
    /**
     * Long-range block pick for Set Guard and the wheel's crosshair aim. {@code mc.hitResult} only
     * covers the player's ~5-block interaction range, useless for designating a spot across a field.
     */
    public static final double BLOCK_PICK_RANGE = 128.0;
    /** Re-show the selection prompt this often (goal-agnostic client ticks) so it doesn't fade mid-mode. */
    private static final int PROMPT_REFRESH_TICKS = 40;

    // Escort selection mode: after the player picks Escort in the TDT, the next in-world left-click
    // designates the vehicle and a right-click cancels. The units to order are captured up front
    // (SEM's menu selection, or nearby owned units), so the click only has to supply the target.
    private static boolean pendingEscort = false;
    private static List<Integer> pendingEscortUnits = List.of();

    private enum TowPickPhase { VICTIM, TOWER }
    private static boolean pendingTow = false;
    private static TowPickPhase pendingTowPhase = TowPickPhase.VICTIM;
    private static int pendingTowVictimId = -1;

    // GUARD_POSITION pick: same arm pattern as escort, but left-click captures a block via pick().
    private static boolean pendingGuard = false;
    private static List<Integer> pendingGuardUnits = List.of();

    // Live Selection: left-click toggles owned PMC / vehicle crew into the wheel selection;
    // right-click confirms (≥1) or cancels (empty). Armed from the wheel's Live key.
    private static boolean pendingLiveSelection = false;

    // Join Platoon pick: same arm pattern as escort, but the target is a PmcCommanderEntity —
    // SEM's own CommanderRayTrace deliberately skips PMCs, so this needs its own raycast.
    private static boolean pendingJoinPlatoon = false;
    private static List<Integer> pendingJoinPlatoonUnits = List.of();

    private static int promptCooldown = 0;

    /**
     * Arm escort selection mode (wheel Escort, which then closes). Units are the wheel's scope —
     * the selection, otherwise owned units near the player — captured now, so the click only has
     * to supply the vehicle. Refuses to arm with no units.
     *
     * @return whether the mode armed
     */
    public static boolean armEscort() {
        clearTow();
        clearGuard();
        clearLiveSelection();
        // Only a unit on foot can escort (EscortGoal walks it), so riders are not taken along.
        List<Integer> units = WheelScope.units(true, WheelScope.nearbyRadius(), null);
        if (units.isEmpty()) {
            hint("message.tacz_sewv.escort.no_units");
            return false;
        }
        pendingEscort = true;
        pendingEscortUnits = units;
        promptCooldown = 0;
        return true;
    }

    /** Two-click vehicle tow: victim hull first, tower hull second. */
    public static boolean armTowRecovery() {
        clearEscort();
        clearGuard();
        clearLiveSelection();
        clearJoinPlatoon();
        pendingTow = true;
        pendingTowPhase = TowPickPhase.VICTIM;
        pendingTowVictimId = -1;
        promptCooldown = 0;
        return true;
    }

    /**
     * Arm GUARD_POSITION block pick (wheel Set Guard). Left-click a block to set; right-click cancels.
     */
    public static boolean armGuardPosition() {
        clearTow();
        clearEscort();
        clearLiveSelection();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.guard.no_units");
            return false;
        }
        pendingGuard = true;
        pendingGuardUnits = units;
        promptCooldown = 0;
        return true;
    }

    /** Arm in-world Live Selection (wheel Live key). Left-click toggles, right-click finishes. */
    public static void armLiveSelection() {
        clearTow();
        clearEscort();
        clearGuard();
        pendingLiveSelection = true;
        promptCooldown = 0;
    }

    /**
     * Calls a radio fire mission at the crosshair, right now. The client sends only its view; the
     * server raycasts it ({@code RadioRaycast}) and what it hits — entity or block — picks the
     * mission. Shared by the radio panel's CALL and the quick wheel's Support leaves.
     */
    public static void callRadio(RadioSettings.State settings) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        NetworkHandler.CHANNEL.sendToServer(
                new PacketRadioCommand(settings, player.getYRot(), player.getXRot()));
    }

    /**
     * Arm Join Platoon selection mode (wheel Join Platoon, which then closes). The next left-click on an owned Commander sends the selected units to join its
     * platoon; the server re-validates ownership, type match and capacity per unit and reports
     * whichever ones did not take.
     */
    public static boolean armJoinPlatoon() {
        clearTow();
        clearEscort();
        clearGuard();
        clearLiveSelection();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.platoon.no_units");
            return false;
        }
        pendingJoinPlatoon = true;
        pendingJoinPlatoonUnits = units;
        promptCooldown = 0;
        return true;
    }

    /** The wheel's scope ({@link WheelScope}): the selection, else owned units nearby. */
    private static List<Integer> snapshotOwnedUnits() {
        return WheelScope.units(false, WheelScope.nearbyRadius(), null);
    }

    public static boolean isLiveSelecting() {
        return pendingLiveSelection;
    }

    /**
     * An armed pick that will send a unit order on its click (Escort, Set Guard). The wheel's queue
     * bracket stays open across it, the same way it does for SEM's Move To pick.
     */
    public static boolean hasPendingOrderPick() {
        return pendingEscort || pendingGuard;
    }

    /** Opening the wheel mid Live Selection ends it, keeping whatever was picked. */
    public static void endLiveSelectionQuietly() {
        clearLiveSelection();
    }

    /**
     * Left click (attack) drives selection modes first, then opens the TDT with the terminal in hand.
     */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;


        if (pendingLiveSelection) {
            if (event.isAttack()) {
                liveSelectClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                finishLiveSelection();
                event.setCanceled(true);
            }
            return;
        }

        if (pendingEscort) {
            if (event.isAttack()) {
                Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
                if (target instanceof VehicleEntity vehicle) {
                    NetworkHandler.CHANNEL.sendToServer(new PacketEscort(pendingEscortUnits, vehicle.getId()));
                    clearEscort();
                } else {
                    hint("message.tacz_sewv.escort.no_vehicle");
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearEscort();
                hint("message.tacz_sewv.escort.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingTow) {
            if (event.isAttack()) {
                towVehicleClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearTow();
                hint("message.tacz_sewv.tow.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingGuard) {
            if (event.isAttack()) {
                HitResult hit = mc.player.pick(BLOCK_PICK_RANGE, 0.0F, false);
                if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos pos = bhr.getBlockPos();
                    NetworkHandler.CHANNEL.sendToServer(new PacketSetGuardPosition(pendingGuardUnits, pos));
                    clearGuard();
                } else {
                    hint("message.tacz_sewv.guard.no_block");
                }
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearGuard();
                hint("message.tacz_sewv.guard.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (pendingJoinPlatoon) {
            if (event.isAttack()) {
                joinPlatoonClick(mc);
                event.setCanceled(true);
            } else if (event.isUseItem()) {
                clearJoinPlatoon();
                hint("message.tacz_sewv.platoon.join.cancelled");
                event.setCanceled(true);
            }
            return;
        }

        if (!event.isAttack()) return;
        if (!holdingTerminal(mc.player)) return;
        TdtScreen.open();
        event.setCanceled(true);
    }

    /** Holding the TDT must not break blocks — the left-click is reserved for opening / selecting. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (holdingTerminal(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Same for punching entities while the terminal is in the main hand. */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (holdingTerminal(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean holdingTerminal(Player player) {
        return player.getMainHandItem().is(ModItems.TACTICAL_DATA_TERMINAL.get());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!pendingEscort && !pendingTow && !pendingGuard && !pendingLiveSelection
                && !pendingJoinPlatoon) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            if (mc.player == null) {
                clearEscort();
                clearTow();
                clearGuard();
                clearLiveSelection();
                clearJoinPlatoon();
            }
            return;
        }

        // SuperbWarfare cancels mouse Pre events in armed seats — poll attack/use keys like TdtKeybind.
        if (mc.player.getVehicle() != null) {
            while (mc.options.keyAttack.consumeClick()) {
                confirmPendingAttack(mc);
            }
            while (mc.options.keyUse.consumeClick()) {
                cancelPending();
            }
        }

        if (--promptCooldown <= 0) {
            promptCooldown = PROMPT_REFRESH_TICKS;
            if (pendingLiveSelection) {
                hintLive();
            } else if (pendingEscort) {
                hint("message.tacz_sewv.escort.select");
            } else if (pendingTow) {
                hint(pendingTowPhase == TowPickPhase.VICTIM
                        ? "message.tacz_sewv.tow.pick_victim"
                        : "message.tacz_sewv.tow.pick_tower");
            } else if (pendingGuard) {
                hint("message.tacz_sewv.guard.select");
            } else if (pendingJoinPlatoon) {
                hint("message.tacz_sewv.platoon.join.select");
            }
        }
    }

    private static void confirmPendingAttack(Minecraft mc) {
        if (mc.player == null) return;
        if (pendingLiveSelection) {
            liveSelectClick(mc);
            return;
        }
        if (pendingEscort) {
            Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
            if (target instanceof VehicleEntity vehicle) {
                NetworkHandler.CHANNEL.sendToServer(new PacketEscort(pendingEscortUnits, vehicle.getId()));
                clearEscort();
            } else {
                hint("message.tacz_sewv.escort.no_vehicle");
            }
            return;
        }
        if (pendingTow) {
            towVehicleClick(mc);
            return;
        }
        if (pendingGuard) {
            HitResult hit = mc.player.pick(BLOCK_PICK_RANGE, 0.0F, false);
            if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                NetworkHandler.CHANNEL.sendToServer(new PacketSetGuardPosition(pendingGuardUnits, bhr.getBlockPos()));
                clearGuard();
            } else {
                hint("message.tacz_sewv.guard.no_block");
            }
            return;
        }
        if (pendingJoinPlatoon) {
            joinPlatoonClick(mc);
        }
    }

    private static void cancelPending() {
        if (pendingLiveSelection) {
            finishLiveSelection();
        } else if (pendingEscort) {
            clearEscort();
            hint("message.tacz_sewv.escort.cancelled");
        } else if (pendingTow) {
            clearTow();
            hint("message.tacz_sewv.tow.cancelled");
        } else if (pendingGuard) {
            clearGuard();
            hint("message.tacz_sewv.guard.cancelled");
        } else if (pendingJoinPlatoon) {
            clearJoinPlatoon();
            hint("message.tacz_sewv.platoon.join.cancelled");
        }
    }

    private static void joinPlatoonClick(Minecraft mc) {
        if (mc.player == null) return;
        Entity target = rayTraceCommander(mc.player, ESCORT_PICK_RANGE);
        if (target instanceof PmcCommanderEntity commander) {
            NetworkHandler.CHANNEL.sendToServer(new PacketJoinPlatoon(pendingJoinPlatoonUnits, commander.getId()));
            clearJoinPlatoon();
        } else {
            hint("message.tacz_sewv.platoon.join.no_commander");
        }
    }

    /** SEM's own {@code CommanderRayTrace} deliberately skips PMCs, so joining needs its own pick. */
    @Nullable
    private static Entity rayTraceCommander(Player player, double distance) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(distance));
        AABB box = player.getBoundingBox().expandTowards(look.scale(distance)).inflate(1.0D);

        Entity best = null;
        double closest = distance;
        for (Entity entity : player.level().getEntities(player, box)) {
            if (!(entity instanceof PmcCommanderEntity commander) || !commander.isOwnedBy(player)) continue;
            AABB bb = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = bb.clip(eye, reach);
            if (hit.isEmpty()) continue;
            double dist = eye.distanceTo(hit.get());
            if (dist < closest) {
                best = entity;
                closest = dist;
            }
        }
        return best;
    }

    private static void liveSelectClick(Minecraft mc) {
        if (mc.player == null) return;
        Entity hit = rayTraceSelectable(mc.player, LIVE_PICK_RANGE);
        if (hit == null) {
            hint("message.tacz_sewv.tdt.live_sel.miss");
            return;
        }
        if (!toggleLiveHit(mc.player, hit)) {
            hint("message.tacz_sewv.tdt.live_sel.miss");
            return;
        }
        promptCooldown = 0; // refresh the count on the next tick
    }

    /**
     * Right-click finish: the selection is already live in {@link TdtSelection}, so this only ends
     * the mode and says how to use it. Every wheel order now goes to the selection.
     */
    private static void finishLiveSelection() {
        int n = TdtSelection.selectedCount();
        clearLiveSelection();
        TdtSelection.clearGlow();
        if (n < 1) {
            hint("message.tacz_sewv.tdt.live_sel.cancelled");
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.tacz_sewv.wheel.live_sel.done", n,
                    QuickCommandKeybind.OPEN_WHEEL.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** Toggle an owned PMC, or the owned crew of a vehicle (group select / deselect). */
    private static boolean toggleLiveHit(Player player, Entity hit) {
        if (hit instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
            TdtSelection.toggle(pmc.getId());
            return true;
        }
        if (hit instanceof VehicleEntity vehicle) {
            List<Integer> crew = new ArrayList<>();
            for (Entity p : vehicle.getPassengers()) {
                if (p instanceof PmcUnitEntity pmc && pmc.isOwnedBy(player)) {
                    crew.add(pmc.getId());
                }
            }
            if (crew.isEmpty()) return false;
            boolean any = false;
            for (int id : crew) {
                if (TdtSelection.isSelected(id)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                for (int id : crew) TdtSelection.deselect(id);
            } else {
                for (int id : crew) TdtSelection.select(id);
            }
            return true;
        }
        return false;
    }

    /**
     * Entity pick that includes owned PMCs — SEM's {@link CommanderRayTrace#rayTraceEntity}
     * deliberately skips them (escort wants the hull).
     */
    private static Entity rayTraceSelectable(Player player, double distance) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 reach = eye.add(look.scale(distance));
        AABB box = player.getBoundingBox().expandTowards(look.scale(distance)).inflate(1.0D);

        Entity best = null;
        double closest = distance;
        for (Entity entity : player.level().getEntities(player, box)) {
            if (!(entity instanceof PmcUnitEntity) && !(entity instanceof VehicleEntity)) continue;
            AABB bb = entity.getBoundingBox().inflate(entity.getPickRadius());
            Optional<Vec3> hit = bb.clip(eye, reach);
            if (hit.isEmpty()) continue;
            double dist = eye.distanceTo(hit.get());
            if (dist < closest) {
                best = entity;
                closest = dist;
            }
        }
        return best;
    }

    private static void towVehicleClick(Minecraft mc) {
        Entity target = CommanderRayTrace.rayTraceEntity(mc.player, ESCORT_PICK_RANGE);
        if (!(target instanceof VehicleEntity vehicle)) {
            hint("message.tacz_sewv.tow.no_vehicle");
            return;
        }
        if (pendingTowPhase == TowPickPhase.VICTIM) {
            if (!TowRecoverySupport.isTowVictimCandidate(vehicle)) {
                hint("message.tacz_sewv.tow.cannot_be_towed");
                return;
            }
            pendingTowVictimId = vehicle.getId();
            pendingTowPhase = TowPickPhase.TOWER;
            promptCooldown = 0;
            hint("message.tacz_sewv.tow.pick_tower");
            return;
        }
        if (vehicle.getId() == pendingTowVictimId) {
            hint("message.tacz_sewv.tow.same_vehicle");
            return;
        }
        if (!TowRecoverySupport.isTowTowerCandidate(vehicle)) {
            hint("message.tacz_sewv.tow.cannot_tow");
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketTowRecovery(pendingTowVictimId, vehicle.getId()));
        clearTow();
    }

    private static void clearEscort() {
        pendingEscort = false;
        pendingEscortUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearTow() {
        pendingTow = false;
        pendingTowPhase = TowPickPhase.VICTIM;
        pendingTowVictimId = -1;
        promptCooldown = 0;
    }

    private static void clearGuard() {
        pendingGuard = false;
        pendingGuardUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearLiveSelection() {
        pendingLiveSelection = false;
        promptCooldown = 0;
    }

    private static void clearJoinPlatoon() {
        pendingJoinPlatoon = false;
        pendingJoinPlatoonUnits = List.of();
        promptCooldown = 0;
    }

    /**
     * SBW's projectile chunk loading is the usual cause of extreme lag; tell players the fix on join.
     * While SEM's ladder fallback is on, also point at it: in large battles it re-runs a block-column
     * ladder search every time a rifleman paths at a target 3+ blocks above it (a hull's crew, a
     * helicopter), which is pure cost on open maps. Both are the player's call, so this only says so.
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!com.neoalive.tacz_sewv.config.ClientConfig.flag(com.neoalive.tacz_sewv.config.ClientConfig.SHOW_LAG_TIP)) {
            return;
        }
        event.getPlayer().displayClientMessage(
                Component.translatable("message.tacz_sewv.lag_tip").withStyle(ChatFormatting.GOLD), false);
        if (com.neoalive.tacz_sewv.compat.SemConfigProbe.ladderFallbackEnabled()) {
            event.getPlayer().displayClientMessage(
                    Component.translatable("message.tacz_sewv.lag_tip_ladder").withStyle(ChatFormatting.GOLD),
                    false);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MapMarkers.clear();
        com.neoalive.tacz_sewv.client.territory.TerritoryClient.clear();
        AirportPlots.clear();
        InvasionHudClient.clear();
        VehicleSkinClient.clearAll();
        CamoClient.clearAll();
        HeliRunPhaseClient.clearAll();
        PlayerRappelClient.clearAll();
        PlaneLandingDebugClient.clearAll();
        clearEscort();
        clearTow();
        clearGuard();
        clearLiveSelection();
        clearJoinPlatoon();
        // Network ids mean nothing in the next session.
        TdtSelection.deselectAll();
    }

    private static void hint(String key) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
        }
    }

    private static void hintLive() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.tdt.live_sel.select",
                        TdtSelection.selectedCount()).withStyle(ChatFormatting.GRAY),
                true);
    }
}
