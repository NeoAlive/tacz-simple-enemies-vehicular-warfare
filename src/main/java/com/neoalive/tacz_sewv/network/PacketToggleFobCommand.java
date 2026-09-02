package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketToggleFobCommand {

    private final BlockPos commandPos;

    public PacketToggleFobCommand(BlockPos commandPos) {
        this.commandPos = commandPos;
    }

    public PacketToggleFobCommand(FriendlyByteBuf buf) {
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
            FobManager.get(player.serverLevel()).toggleFobCommand(this.commandPos, player.serverLevel());
            FobNetworking.sendRefresh(player, this.commandPos, com.neoalive.tacz_sewv.fob.FobGuiSnapshot.GuiKind.COMMAND);
        });
        ctx.get().setPacketHandled(true);
    }
}
