package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;
import com.neoalive.tacz_sewv.fob.FobNetworking;

/**
 * Bidirectional FOB GUI sync. C→S carries only {@code refresh=true} + command pos; S→C carries snapshot.
 */
public class PacketFobData {

    private final boolean refreshRequest;
    @Nullable
    private final BlockPos commandPos;
    @Nullable
    private final FobGuiSnapshot snapshot;

    /** Server → client: open or refresh GUI */
    public PacketFobData(FobGuiSnapshot snapshot) {
        this.refreshRequest = false;
        this.commandPos = null;
        this.snapshot = snapshot;
    }

    /** Client → server: request refresh */
    public static PacketFobData request(BlockPos commandPos) {
        return new PacketFobData(commandPos);
    }

    private PacketFobData(BlockPos commandPos) {
        this.refreshRequest = true;
        this.commandPos = commandPos;
        this.snapshot = null;
    }

    public PacketFobData(FriendlyByteBuf buf) {
        this.refreshRequest = buf.readBoolean();
        if (this.refreshRequest) {
            this.commandPos = buf.readBlockPos();
            this.snapshot = null;
        } else {
            this.commandPos = null;
            this.snapshot = FobGuiSnapshot.decode(buf);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.refreshRequest);
        if (this.refreshRequest) {
            buf.writeBlockPos(this.commandPos);
        } else {
            FobGuiSnapshot.encode(buf, this.snapshot);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !this.refreshRequest || this.commandPos == null) return;
                if (!FobNetworking.isOwner(player, this.commandPos, player.serverLevel())) return;
                FobNetworking.sendRefresh(player, this.commandPos);
            } else if (this.snapshot != null) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        com.neoalive.tacz_sewv.client.fob.FobClient.acceptData(this.snapshot));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
