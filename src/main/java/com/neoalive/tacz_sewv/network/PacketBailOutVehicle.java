package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;

/**
 * Player-triggered emergency bail — expands seed unit ids to <b>every</b> owned PMC passenger
 * on those hulls, then queues {@link BailOutSupport#TAG_MANUAL_BAIL} for
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.BailOutVehicleGoal} (parachute + scramble).
 */
public class PacketBailOutVehicle {

    private final List<Integer> unitIds;

    public PacketBailOutVehicle(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public PacketBailOutVehicle(FriendlyByteBuf buf) {
        this.unitIds = PacketLists.readUnitIds(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(this.unitIds, FriendlyByteBuf::writeVarInt);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (com.neoalive.tacz_sewv.command.quick.OrderQueue.capture(player, () -> handle(ctx))) return;

            int queued = BailOutSupport.requestManualBailExpanded(
                    player, this.unitIds, BailOutSupport.everyone());

            NetworkHandler.orderFeedback(player, "bail_out", "message.tacz_sewv.bail_out", queued,
                    ChatFormatting.YELLOW, queued);
        });
        ctx.get().setPacketHandled(true);
    }
}
