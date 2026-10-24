package com.neoalive.tacz_sewv.client;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.client.radial.WheelScope;
import com.neoalive.tacz_sewv.compat.lv.LvCompat;
import com.neoalive.tacz_sewv.entity.ai.support.FormationAnchorMode;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketBailOutVehicle;
import com.neoalive.tacz_sewv.network.PacketBoardVehicle;
import com.neoalive.tacz_sewv.network.PacketManMortar;
import com.neoalive.tacz_sewv.network.PacketParatroop;
import com.neoalive.tacz_sewv.network.PacketPatrolVehicle;
import com.neoalive.tacz_sewv.network.PacketVehicleFormation;

/** Unit orders the Quick Wheel sends on packets of their own (not {@code PacketQuickCommand}). */
public class BoardKeybind {

    /** True for anything a Board order can crew: a hull to ride, or a mortar / Type63 to stand beside. */
    public static boolean isBoardable(@Nullable Entity target) {
        return target instanceof VehicleEntity || LvCompat.isCrewableHull(target);
    }

    /**
     * Order the wheel's units to crew {@code target} — a vehicle to ride or a mortar to stand
     * beside. With {@code passengerOnly} the units fill non-driver seats only (ignored for a mortar,
     * which has no seats at all).
     *
     * @return whether an order was sent
     */
    public static boolean orderBoard(Entity target, boolean passengerOnly) {
        // On foot only: a rider's board order would sit unused until it next dismounts.
        return withOwnedUnits(true, null, "message.tacz_sewv.board.no_units", (player, unitIds) -> {
            // A mortar is a VehicleEntity too, but it has no seats to ride: crewing one means
            // standing beside it, which is a different order entirely. Check it first, since
            // the vehicle branch would otherwise swallow it and send units to board something
            // they can never sit in.
            if (target instanceof MortarEntity || target instanceof Type63Entity) {
                NetworkHandler.CHANNEL.sendToServer(new PacketManMortar(unitIds, target.getId()));
            } else {
                // A Limitless Vehicles hull takes the same order and packet; the server routes it.
                NetworkHandler.CHANNEL.sendToServer(new PacketBoardVehicle(unitIds, target.getId(), passengerOnly));
            }
        });
    }

    /**
     * Emergency bail — server expands to every owned PMC on those hulls (parachute / scramble /
     * full stand-down). Not a simple dismount.
     */
    public static boolean orderBailOut() {
        return withOwnedUnits(pmc -> pmc.getVehicle() != null, "message.tacz_sewv.board.no_units",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketBailOutVehicle(unitIds)));
    }

    /**
     * Air assault drop — server expands to weaponless cargo only (same seat filter as rappel),
     * then queues the parachute bail pathway. Pilot / gunners stay aboard.
     */
    public static boolean orderParatroop() {
        return withOwnedUnits(pmc -> pmc.getVehicle() != null, "message.tacz_sewv.paratroop.none",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketParatroop(unitIds)));
    }

    /**
     * Order units onto an area task over a {@code radius}-block circle around the player: an
     * endless patrol or a one-time search sweep ({@code mode}, see IVehiclePatrol), or
     * {@code MODE_DISMISS} to stand them down. On foot a unit walks it; a crew hands it to its
     * driver (ground hulls and helicopters). The player's position is the origin.
     */
    public static boolean orderAreaTask(int radius, int mode) {
        return withOwnedUnits(null, "message.tacz_sewv.board.no_units",
                (player, unitIds) -> NetworkHandler.CHANNEL.sendToServer(new PacketPatrolVehicle(unitIds, radius, mode)));
    }

    /** Explicit unit ids already resolved by the wheel. */
    public static void orderFormation(List<Integer> unitIds, FormationShape shape, int axisInt, float yaw,
                                      int rowSize, float widthStretch, float lengthStretch,
                                      FormationAnchorMode anchorMode) {
        if (unitIds.isEmpty()) return;
        NetworkHandler.CHANNEL.sendToServer(
                new PacketVehicleFormation(unitIds, shape, axisInt, yaw, rowSize,
                        widthStretch, lengthStretch, anchorMode));
    }

    /**
     * The shared preamble of every such order: the wheel's units ({@link WheelScope} — the
     * selection, else owned units nearby) that pass {@code filter}, handed to {@code order} — or a
     * GRAY hint when there are none. The server re-checks ownership per unit before acting on
     * whatever the order sends, so a stale client list can never command another player's units.
     *
     * @param filter null when the order needs no client-side test (see {@link WheelScope#units})
     * @return whether {@code order} ran
     */
    public static boolean withOwnedUnits(@Nullable Predicate<PmcUnitEntity> filter, String emptyKey,
                                         BiConsumer<Player, List<Integer>> order) {
        return withOwnedUnits(false, filter, emptyKey, order);
    }

    private static boolean withOwnedUnits(boolean onFootOnly, @Nullable Predicate<PmcUnitEntity> filter,
                                          String emptyKey, BiConsumer<Player, List<Integer>> order) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return false;

        List<Integer> unitIds = WheelScope.units(onFootOnly, WheelScope.nearbyRadius(), filter);
        if (unitIds.isEmpty()) {
            hint(player, WheelScope.emptyKey(emptyKey));
            return false;
        }
        order.accept(player, unitIds);
        return true;
    }

    /** An order's outcome, so chat rather than the bar — see {@code PacketOrderFeedback.show}. */
    static void hint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), false);
    }
}
