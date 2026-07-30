package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.util.InvasionBlockEditor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: write capture_point config from the editor. */
public class PacketSaveCapturePoint {

    private final BlockPos pos;
    private final int pointId;
    private final boolean showBillboard;
    private final double billboardYOffset;
    private final int timeToCaptureSeconds;
    private final int radiusInBlocks;
    private final String ownedTeam;

    public PacketSaveCapturePoint(BlockPos pos, int pointId, boolean showBillboard, double billboardYOffset,
                                  int timeToCaptureSeconds, int radiusInBlocks, String ownedTeam) {
        this.pos = pos;
        this.pointId = pointId;
        this.showBillboard = showBillboard;
        this.billboardYOffset = billboardYOffset;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
    }

    public PacketSaveCapturePoint(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.pointId = buf.readVarInt();
        this.showBillboard = buf.readBoolean();
        this.billboardYOffset = buf.readDouble();
        this.timeToCaptureSeconds = buf.readVarInt();
        this.radiusInBlocks = buf.readVarInt();
        this.ownedTeam = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.pointId);
        buf.writeBoolean(this.showBillboard);
        buf.writeDouble(this.billboardYOffset);
        buf.writeVarInt(this.timeToCaptureSeconds);
        buf.writeVarInt(this.radiusInBlocks);
        buf.writeUtf(this.ownedTeam);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !InvasionBlockEditor.mayEdit(player)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof CapturePointBlockEntity be)) return;

            be.setPointId(this.pointId);
            be.setShowBillboard(this.showBillboard);
            be.setBillboardYOffset(this.billboardYOffset);
            be.setTimeToCaptureSeconds(Math.max(1, this.timeToCaptureSeconds));
            be.setRadiusInBlocks(Math.max(1, this.radiusInBlocks));
            be.setOwnedTeam(this.ownedTeam);

            BlockState state = level.getBlockState(this.pos);
            level.sendBlockUpdated(this.pos, state, state, 3);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.gui.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
