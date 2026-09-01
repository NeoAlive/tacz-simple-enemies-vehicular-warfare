package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketRouteToFob {

    private final BlockPos commandPos;

    public PacketRouteToFob(BlockPos commandPos) {
        this.commandPos = commandPos;
    }

    public PacketRouteToFob(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!FobNetworking.isOwner(player, this.commandPos, player.serverLevel())) return;
            FobNetworking.routeVehiclesToFob(player, this.commandPos);
            FobNetworking.sendRefresh(player, this.commandPos);
        });
        ctx.get().setPacketHandled(true);
    }
}
