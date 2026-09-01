package com.neoalive.tacz_sewv.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketAssignFobLiving {

    private final BlockPos commandPos;
    private final UUID entityId;
    private final boolean assign;

    public PacketAssignFobLiving(BlockPos commandPos, UUID entityId, boolean assign) {
        this.commandPos = commandPos;
        this.entityId = entityId;
        this.assign = assign;
    }

    public PacketAssignFobLiving(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
        this.entityId = buf.readUUID();
        this.assign = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
        buf.writeUUID(this.entityId);
        buf.writeBoolean(this.assign);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!FobNetworking.isOwner(player, this.commandPos, player.serverLevel())) return;
            FobManager mgr = FobManager.get(player.serverLevel());
            if (this.assign) {
                mgr.assignLiving(this.commandPos, this.entityId, player.serverLevel());
            } else {
                mgr.unassignLiving(this.commandPos, this.entityId, player.serverLevel());
            }
            FobNetworking.sendRefresh(player, this.commandPos);
        });
        ctx.get().setPacketHandled(true);
    }
}
