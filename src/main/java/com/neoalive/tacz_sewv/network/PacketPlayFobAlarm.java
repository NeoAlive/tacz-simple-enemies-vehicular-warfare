package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;
import com.neoalive.tacz_sewv.fob.ThreatEvaluator;

public class PacketPlayFobAlarm {

    private final BlockPos commandPos;

    public PacketPlayFobAlarm(BlockPos commandPos) {
        this.commandPos = commandPos;
    }

    public PacketPlayFobAlarm(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            if (!FobNetworking.isOwner(player, this.commandPos, level)) return;
            FobInstance fob = FobManager.get(level).getFob(this.commandPos);
            if (fob == null) return;
            ThreatEvaluator.playAlarm(level, fob, level.getGameTime());
        });
        ctx.get().setPacketHandled(true);
    }
}
