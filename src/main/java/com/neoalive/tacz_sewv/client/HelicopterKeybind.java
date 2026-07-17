package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Helicopter takeoff/land orders sent from the Tactical Data Terminal ({@link TdtScreen}). */
public class HelicopterKeybind {

    /** Order owned helicopter pilots to climb to (and hold) {@code altitude} as their live cruise trim. */
    public static void orderTakeoff(int altitude) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedPilots(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.heli.takeoff.none");
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_TAKEOFF, null, altitude));
    }

    // How far out the landing-pad pick reaches. mc.hitResult only covers the player's
    // ~5-block interaction range — useless for designating a pad across the field — so
    // the TDT does its own long-range block pick when it opens.
    public static final double LAND_PICK_RANGE = 128.0;

    /**
     * Order owned helicopter pilots to set down on {@code pad}. The TDT captures the aimed
     * block when its screen opens and passes it here; a null pad hints to look at a block.
     */
    public static void orderLand(@Nullable BlockPos pad) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        if (pad == null) {
            hint(player, "message.tacz_sewv.heli.no_pad");
            return;
        }
        List<Integer> unitIds = gatherOwnedPilots(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.heli.land.none");
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new PacketHelicopterCommand(unitIds, IHelicopterPilot.HELI_CMD_LANDING, pad, 0));
    }

    // This player's units currently AT THE STICK of a helicopter (seat 0) within
    // the configured radius. Gunners, passengers and ground units are not flight
    // crews — sending every owned unit made the order feedback report one
    // "helicopter" per crew member. The server re-checks ownership and the pilot
    // seat per unit before acting on the order.
    private static List<Integer> gatherOwnedPilots(Minecraft mc, Player player) {
        double radius = SewvConfig.BOARD_SCAN_RADIUS.get();
        List<Integer> unitIds = new ArrayList<>();
        List<PmcUnitEntity> units = mc.level.getEntitiesOfClass(
                PmcUnitEntity.class, player.getBoundingBox().inflate(radius));
        for (PmcUnitEntity pmc : units) {
            if (pmc.isOwnedBy(player) && isHelicopterPilot(pmc)) unitIds.add(pmc.getId());
        }
        return unitIds;
    }

    // Engine info is applied from SBW's synced VehicleData on both sides (the
    // client runs its own helicopter physics), so this is client-safe.
    private static boolean isHelicopterPilot(PmcUnitEntity pmc) {
        return pmc.getVehicle() instanceof VehicleEntity v
                && v.getFirstPassenger() == pmc
                && v.getEngineInfo() instanceof EngineInfo.Helicopter;
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
