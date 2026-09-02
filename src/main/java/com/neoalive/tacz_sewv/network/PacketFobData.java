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
 * Bidirectional FOB GUI sync. C→S refresh carries anchor pos + screen kind; S→C carries snapshot.
 */
public class PacketFobData {

    private final boolean refreshRequest;
    @Nullable
    private final BlockPos anchorPos;
    @Nullable
    private final FobGuiSnapshot.GuiKind kind;
    @Nullable
    private final FobGuiSnapshot snapshot;

    /** Server → client: open or refresh GUI */
    public PacketFobData(FobGuiSnapshot snapshot) {
        this.refreshRequest = false;
        this.anchorPos = null;
        this.kind = null;
        this.snapshot = snapshot;
    }

    /** Client → server: re-run layout clearance and refresh */
    public static PacketFobData request(BlockPos anchorPos, FobGuiSnapshot.GuiKind kind) {
        return new PacketFobData(anchorPos, kind);
    }

    private PacketFobData(BlockPos anchorPos, FobGuiSnapshot.GuiKind kind) {
        this.refreshRequest = true;
        this.anchorPos = anchorPos;
        this.kind = kind;
        this.snapshot = null;
    }

    public PacketFobData(FriendlyByteBuf buf) {
        this.refreshRequest = buf.readBoolean();
        if (this.refreshRequest) {
            this.anchorPos = buf.readBlockPos();
            this.kind = FobGuiSnapshot.GuiKind.decode(buf);
            this.snapshot = null;
        } else {
            this.anchorPos = null;
            this.kind = null;
            this.snapshot = FobGuiSnapshot.decode(buf);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.refreshRequest);
        if (this.refreshRequest) {
            buf.writeBlockPos(this.anchorPos);
            this.kind.encode(buf);
        } else {
            FobGuiSnapshot.encode(buf, this.snapshot);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !this.refreshRequest || this.anchorPos == null || this.kind == null) {
                    return;
                }
                if (!FobNetworking.isOwner(player, this.anchorPos, player.serverLevel())) return;
                FobNetworking.sendRefresh(player, this.anchorPos, this.kind);
            } else if (this.snapshot != null) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        com.neoalive.tacz_sewv.client.fob.FobClient.acceptData(this.snapshot));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
