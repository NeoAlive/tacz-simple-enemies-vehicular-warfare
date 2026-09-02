package com.neoalive.tacz_sewv.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

public class PacketAssignFobLiving {

    private final BlockPos commandPos;
    private final UUID entityId;
    private final boolean assign;
    private final BlockPos anchorPos;
    private final FobGuiSnapshot.GuiKind kind;

    public PacketAssignFobLiving(BlockPos commandPos, UUID entityId, boolean assign,
                                 BlockPos anchorPos, FobGuiSnapshot.GuiKind kind) {
        this.commandPos = commandPos;
        this.entityId = entityId;
        this.assign = assign;
        this.anchorPos = anchorPos;
        this.kind = kind;
    }

    public PacketAssignFobLiving(FriendlyByteBuf buf) {
        this.commandPos = buf.readBlockPos();
        this.entityId = buf.readUUID();
        this.assign = buf.readBoolean();
        this.anchorPos = buf.readBlockPos();
        this.kind = FobGuiSnapshot.GuiKind.decode(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.commandPos);
        buf.writeUUID(this.entityId);
        buf.writeBoolean(this.assign);
        buf.writeBlockPos(this.anchorPos);
        this.kind.encode(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!FobNetworking.isOwner(player, this.anchorPos, player.serverLevel())) return;
            FobManager mgr = FobManager.get(player.serverLevel());
            if (this.assign) {
                mgr.assignLiving(this.commandPos, this.entityId, player.serverLevel());
            } else {
                mgr.unassignLiving(this.commandPos, this.entityId, player.serverLevel());
            }
            FobNetworking.sendRefresh(player, this.anchorPos, this.kind);
        });
        ctx.get().setPacketHandled(true);
    }
}
