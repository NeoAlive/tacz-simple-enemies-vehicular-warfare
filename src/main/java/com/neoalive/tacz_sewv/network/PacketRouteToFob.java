package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketRouteToFob {

    private final BlockPos commandPos;
    private final BlockPos anchorPos;
    private final FobGuiSnapshot.GuiKind kind;

    public PacketRouteToFob(BlockPos commandPos, BlockPos anchorPos, FobGuiSnapshot.GuiKind kind) {
        this.commandPos = commandPos;
        this.anchorPos = anchorPos;
        this.kind = kind;
    }

    public PacketRouteToFob(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
        this.anchorPos = buf.readBlockPos();
        this.kind = FobGuiSnapshot.GuiKind.decode(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
        buf.writeBlockPos(this.anchorPos);
        this.kind.encode(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!FobNetworking.isOwner(player, this.anchorPos, player.serverLevel())) return;
            FobNetworking.routeToFob(player, this.commandPos);
            FobNetworking.sendRefresh(player, this.anchorPos, this.kind);
        });
        ctx.get().setPacketHandled(true);
    }
}
