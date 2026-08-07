package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.block.CapturePointBlockEntity;
import com.neoalive.tacz_sewv.invasion.InvasionBlockEditor;

/** Client → server: write capture_point config from the editor. */
public class PacketSaveCapturePoint {

    private final BlockPos pos;
    private final int pointId;
    private final int timeToCaptureSeconds;
    private final int radiusInBlocks;
    private final String ownedTeam;
    private final boolean invisible;

    public PacketSaveCapturePoint(BlockPos pos, int pointId, int timeToCaptureSeconds, int radiusInBlocks,
                                  String ownedTeam, boolean invisible) {
        this.pos = pos;
        this.pointId = pointId;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.invisible = invisible;
    }

    public PacketSaveCapturePoint(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.pointId = buf.readVarInt();
        this.timeToCaptureSeconds = buf.readVarInt();
        this.radiusInBlocks = buf.readVarInt();
        this.ownedTeam = buf.readUtf();
        this.invisible = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.pointId);
        buf.writeVarInt(this.timeToCaptureSeconds);
        buf.writeVarInt(this.radiusInBlocks);
        buf.writeUtf(this.ownedTeam);
        buf.writeBoolean(this.invisible);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !InvasionBlockEditor.mayEdit(player)) return;
            ServerLevel level = player.serverLevel();
            BlockEntity raw = level.getBlockEntity(this.pos);
            if (!(raw instanceof CapturePointBlockEntity be)) return;

            be.setPointId(this.pointId);
            be.setTimeToCaptureSeconds(Math.max(1, this.timeToCaptureSeconds));
            be.setRadiusInBlocks(Math.max(1, this.radiusInBlocks));
            be.setOwnedTeam(this.ownedTeam);
            be.setInvisible(this.invisible);

            BlockState state = level.getBlockState(this.pos);
            level.sendBlockUpdated(this.pos, state, state, 3);
            player.displayClientMessage(Component.translatable("message.tacz_sewv.invasion.gui.saved"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
