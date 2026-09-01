package com.neoalive.tacz_sewv.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketAssignFobVehicle {

    private final BlockPos commandPos;
    private final UUID vehicleId;
    private final boolean assign;

    public PacketAssignFobVehicle(BlockPos commandPos, UUID vehicleId, boolean assign) {
        this.commandPos = commandPos;
        this.vehicleId = vehicleId;
        this.assign = assign;
    }

    public PacketAssignFobVehicle(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
        this.vehicleId = buf.readUUID();
        this.assign = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
        buf.writeUUID(this.vehicleId);
        buf.writeBoolean(this.assign);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!FobNetworking.isOwner(player, this.commandPos, player.serverLevel())) return;
            FobManager mgr = FobManager.get(player.serverLevel());
            if (this.assign) {
                mgr.assignVehicle(this.commandPos, this.vehicleId, player.serverLevel());
            } else {
                mgr.unassignVehicle(this.commandPos, this.vehicleId, player.serverLevel());
            }
            FobNetworking.sendRefresh(player, this.commandPos);
        });
        ctx.get().setPacketHandled(true);
    }
}
