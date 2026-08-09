package com.neoalive.tacz_sewv.client;

import java.util.List;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.client.util.CommanderRayTrace;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.invasion.InvasionHudClient;
import com.neoalive.tacz_sewv.init.ModItems;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketEscort;
import com.neoalive.tacz_sewv.network.PacketSetGuardPosition;

// FORGE bus, client dist: opens the Tactical Data Terminal, and runs Escort / Guard selection modes.
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public class ClientEvents {

    /** Reach for picking the vehicle to escort — SEM's own selection raytrace distance. */
    private static final double ESCORT_PICK_RANGE = 50.0;
    private static final double CLIENT_DISCOVERY_RADIUS = 512.0;
    /** Re-show the selection prompt this often (goal-agnostic client ticks) so it doesn't fade mid-mode. */
    private static final int PROMPT_REFRESH_TICKS = 40;

    // Escort selection mode: after the player picks Escort in the TDT, the next in-world left-click
    // designates the vehicle and a right-click cancels. The units to order are captured up front
    // (SEM's menu selection, or nearby owned units), so the click only has to supply the target.
    private static boolean pendingEscort = false;
    private static List<Integer> pendingEscortUnits = List.of();

    // GUARD_POSITION pick: same arm pattern as escort, but left-click captures a block via pick().
    private static boolean pendingGuard = false;
    private static List<Integer> pendingGuardUnits = List.of();

    private static int promptCooldown = 0;

    /**
     * Arm escort selection mode. Called by the TDT's Escort button, which then closes the screen.
     * Units are SEM's commander-menu selection if there is one (the flow the feature is built
     * around), otherwise the owned units near the player, so it still does something without a
     * selection. Refuses to arm with no units.
     */
    public static void armEscort() {
        clearGuard();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.escort.no_units");
            return;
        }
        pendingEscort = true;
        pendingEscortUnits = units;
        promptCooldown = 0;
    }

    /**
     * Arm GUARD_POSITION block pick (TDT, Xaero-free). Left-click a block to set; right-click cancels.
     */
    public static void armGuardPosition() {
        clearEscort();
        List<Integer> units = snapshotOwnedUnits();
        if (units.isEmpty()) {
            hint("message.tacz_sewv.guard.no_units");
            return;
        }
        pendingGuard = true;
        pendingGuardUnits = units;
        promptCooldown = 0;
    }

    private static List<Integer> snapshotOwnedUnits() {
        return TdtSelection.resolve(CLIENT_DISCOVERY_RADIUS);
    }

    /**
     * Left click (attack) drives selection modes first, then opens the TDT with the terminal in hand.
     */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

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

        if (pendingGuard) {
            if (event.isAttack()) {
                HitResult hit = mc.player.pick(HelicopterKeybind.LAND_PICK_RANGE, 0.0F, false);
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

        if (!event.isAttack()) return;
        if (!mc.player.getMainHandItem().is(ModItems.TACTICAL_DATA_TERMINAL.get())) return;
        TdtScreen.open();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!pendingEscort && !pendingGuard) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            if (mc.player == null) {
                clearEscort();
                clearGuard();
            }
            return;
        }
        if (--promptCooldown <= 0) {
            promptCooldown = PROMPT_REFRESH_TICKS;
            if (pendingEscort) hint("message.tacz_sewv.escort.select");
            else hint("message.tacz_sewv.guard.select");
        }
    }

    private static void clearEscort() {
        pendingEscort = false;
        pendingEscortUnits = List.of();
        promptCooldown = 0;
    }

    private static void clearGuard() {
        pendingGuard = false;
        pendingGuardUnits = List.of();
        promptCooldown = 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MapMarkers.clear();
        MapTrenchMarkers.clear();
        InvasionHudClient.clear();
        clearEscort();
        clearGuard();
    }

    private static void hint(String key) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
        }
    }
}
