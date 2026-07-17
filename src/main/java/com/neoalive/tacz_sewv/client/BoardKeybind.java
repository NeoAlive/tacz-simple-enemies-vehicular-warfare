package com.neoalive.tacz_sewv.client;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.FormationShape;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketBoardVehicle;
import com.neoalive.tacz_sewv.network.PacketDismountVehicle;
import com.neoalive.tacz_sewv.network.PacketManMortar;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;

import java.util.ArrayList;
import java.util.List;

/** Board/dismount orders sent from the Tactical Data Terminal ({@link TdtScreen}). */
public class BoardKeybind {

    /**
     * Order owned units to crew {@code target} — a vehicle to ride or a mortar to stand
     * beside. The TDT captures the aim when its screen opens and passes it here; a
     * null/non-vehicle target hints to look at one.
     */
    public static void orderBoard(@Nullable Entity target) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        // The server validates ownership and reports the result — no optimistic
        // client-side "order sent" message that could lie on failure.
        //
        // A mortar is a VehicleEntity too, but it has no seats to ride: crewing one
        // means standing beside it, which is a different order entirely. Check it first,
        // since the vehicle branch would otherwise swallow it and send units to board
        // something they can never sit in.
        if (target instanceof MortarEntity mortar) {
            NetworkHandler.CHANNEL.sendToServer(new PacketManMortar(unitIds, mortar.getId()));
        } else if (target instanceof VehicleEntity vehicle) {
            NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, vehicle.getId()));
        } else {
            hint(player, "message.tacz_sewv.board.no_vehicle");
        }
    }

    public static void orderDismount() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketDismountVehicle(unitIds));
    }

    /**
     * Order owned crews to patrol a {@code radius}-block circle around the player. The server
     * filters to ground-vehicle drivers and uses the player's own position as the origin; the TDT
     * has already checked the radius floor before calling this.
     */
    public static void orderPatrol(int radius) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(unitIds, radius));
    }

    /**
     * Form owned crews into {@code shape} along {@code axisInt} (the cardinal captured when the TDT
     * opened). {@code rowSize} is the LINE units-per-row, ignored by the other shapes. The server
     * numbers the slots and re-checks ownership.
     */
    public static void orderFormation(FormationShape shape, int axisInt, int rowSize) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        List<Integer> unitIds = gatherOwnedUnits(mc, player);
        if (unitIds.isEmpty()) {
            hint(player, "message.tacz_sewv.board.no_units");
            return;
        }

        NetworkHandler.CHANNEL.sendToServer(new PacketVehicleFormation(unitIds, shape, axisInt, rowSize));
    }

    // This player's units within the configured radius; the server re-checks
    // ownership per unit before acting on the order.
    private static List<Integer> gatherOwnedUnits(Minecraft mc, Player player) {
        double radius = SewvConfig.BOARD_SCAN_RADIUS.get();
        List<Integer> unitIds = new ArrayList<>();
        List<PmcUnitEntity> units = mc.level.getEntitiesOfClass(
                PmcUnitEntity.class, player.getBoundingBox().inflate(radius));
        for (PmcUnitEntity pmc : units) {
            if (pmc.isOwnedBy(player)) unitIds.add(pmc.getId());
        }
        return unitIds;
    }

    private static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
