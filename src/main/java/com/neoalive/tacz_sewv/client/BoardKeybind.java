package com.neoalive.tacz_sewv.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketBoardVehicle;
import com.neoalive.tacz_sewv.network.PacketDismountVehicle;
import com.neoalive.tacz_sewv.network.PacketManMortar;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/** Board/dismount orders sent from the Tactical Data Terminal ({@link TdtScreen}). */
public class BoardKeybind {

    private static final double CLIENT_DISCOVERY_RADIUS = 512.0;

    /**
     * Order owned units to crew {@code target} — a vehicle to ride or a mortar to stand
     * beside. The TDT captures the aim when its screen opens and passes it here; a
     * null/non-vehicle target hints to look at one. With {@code passengerOnly} the units fill
     * non-driver seats only (ignored for a mortar, which has no seats at all).
     */
    public static void orderBoard(@Nullable Entity target, boolean passengerOnly) {
        withOwnedUnits(pmc -> true, "message.tacz_sewv.board.no_units", (player, unitIds) -> {
            // A mortar is a VehicleEntity too, but it has no seats to ride: crewing one means
            // standing beside it, which is a different order entirely. Check it first, since
            // the vehicle branch would otherwise swallow it and send units to board something
            // they can never sit in.
            if (target instanceof MortarEntity mortar) {
                NetworkHandler.CHANNEL.sendToServer(new PacketManMortar(unitIds, mortar.getId()));
            } else if (target instanceof VehicleEntity vehicle) {
                NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, vehicle.getId(), passengerOnly));
            } else {
                hint(player, "message.tacz_sewv.board.no_vehicle");
            }
        });
    }

    public static void orderDismount() {
        withOwnedUnits(pmc -> true, "message.tacz_sewv.board.no_units",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketDismountVehicle(unitIds)));
    }

    /**
     * Order owned crews onto an area task over a {@code radius}-block circle around the player —
     * an endless patrol or a one-time search sweep ({@code mode}, see IVehiclePatrol). The server
     * filters to ground-vehicle drivers and uses the player's own position as the origin; the TDT
     * has already checked the radius floor before calling this.
     */
    public static void orderAreaTask(int radius, int mode) {
        withOwnedUnits(pmc -> true, "message.tacz_sewv.board.no_units",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(unitIds, radius, mode)));
    }

    /**
     * Form owned crews into {@code shape} along {@code axisInt} (the cardinal captured when the TDT
     * opened). {@code rowSize} is the LINE units-per-row, ignored by the other shapes. The server
     * numbers the slots and re-checks ownership.
     */
    public static void orderFormation(FormationShape shape, int axisInt, int rowSize) {
        withOwnedUnits(pmc -> true, "message.tacz_sewv.board.no_units",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(
                        new PacketVehicleFormation(unitIds, shape, axisInt, rowSize)));
    }

    /**
     * The shared preamble of every TDT order: this player's owned units within the configured
     * radius that pass {@code filter}, handed to {@code order} — or a GRAY hint when there are
     * none. The server re-checks ownership per unit before acting on whatever the order sends,
     * so a stale client list can never command another player's units.
     */
    static void withOwnedUnits(Predicate<PmcUnitEntity> filter, String emptyKey,
                               BiConsumer<Player, List<Integer>> order) {
        withOwnedUnits(CLIENT_DISCOVERY_RADIUS, filter, emptyKey, order);
    }

    /**
     * As above, but with an explicit {@code radius} — the aircraft command path passes a larger one
     * (planeCommandRadius) since planes fly high and far. The reach is HORIZONTAL and the query box is
     * made tall, so a plane cruising overhead is not dropped by a vertical extent the way a symmetric
     * box would drop it — the same fix the fire-mission scan uses.
     */
    static void withOwnedUnits(double radius, Predicate<PmcUnitEntity> filter, String emptyKey,
                               BiConsumer<Player, List<Integer>> order) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Ribbon / SEM snapshot first; otherwise the same owned-PMC cylinder as before.
        List<Integer> unitIds = new ArrayList<>();
        for (int id : TdtSelection.resolve(radius)) {
            if (!(mc.level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            if (!pmc.isOwnedBy(player) || !filter.test(pmc)) continue;
            unitIds.add(id);
        }

        if (unitIds.isEmpty()) {
            hint(player, emptyKey);
            return;
        }
        order.accept(player, unitIds);
    }

    static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
    }
}
