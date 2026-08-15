package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.order.OrderFailure;
import com.neoalive.tacz_sewv.order.OrderReport;

/**
 * "Board my vehicle" — sent from {@code BoardMyVehicleKeybind} once the player is seated in the
 * hull they want their squad to fill. A passenger-only board order ({@link PacketBoardVehicle})
 * walks units up and holds them beside the hull ({@code BoardVehicleGoal}'s wait); this is the
 * release that lets them actually mount, so the player gets first pick of seat.
 */
public class PacketClearBoarding {

    private static final double SCAN_RADIUS = 128.0;

    public PacketClearBoarding() {
    }

    public PacketClearBoarding(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Player player = ctx.get().getSender();
            if (player == null) return;

            Entity mount = player.getVehicle();
            if (!(mount instanceof VehicleEntity vehicle)) {
                OrderReport.fail(player, OrderFailure.NOT_MOUNTED);
                return;
            }

            AABB area = vehicle.getBoundingBox().inflate(SCAN_RADIUS);
            int cleared = 0;
            for (PmcUnitEntity pmc : player.level().getEntitiesOfClass(PmcUnitEntity.class, area)) {
                if (!pmc.isOwnedBy(player)) continue;
                IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                if (!boarder.tacz_sewv$isBoarding() || !boarder.tacz_sewv$isPassengerOnly()) continue;
                if (boarder.tacz_sewv$getMountTargetId() != vehicle.getId()) continue;
                boarder.tacz_sewv$setBoardCleared(true);
                cleared++;
            }

            NetworkHandler.orderFeedback(player, "message.tacz_sewv.board.cleared", cleared,
                    ChatFormatting.GREEN, cleared);
        });
        ctx.get().setPacketHandled(true);
    }
}
