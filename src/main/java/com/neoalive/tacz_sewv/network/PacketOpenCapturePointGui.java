package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.invasion.InvasionEditorClient;

/** Server → client: open the capture_point config screen. */
public class PacketOpenCapturePointGui {

    private final BlockPos pos;
    private final int pointId;
    private final int timeToCaptureSeconds;
    private final int radiusInBlocks;
    private final String ownedTeam;
    private final boolean invisible;
    private final List<String> teams;

    public PacketOpenCapturePointGui(BlockPos pos, int pointId, int timeToCaptureSeconds, int radiusInBlocks,
                                     String ownedTeam, boolean invisible, List<String> teams) {
        this.pos = pos;
        this.pointId = pointId;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.invisible = invisible;
        this.teams = teams;
    }

    public PacketOpenCapturePointGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.pointId = buf.readVarInt();
        this.timeToCaptureSeconds = buf.readVarInt();
        this.radiusInBlocks = buf.readVarInt();
        this.ownedTeam = buf.readUtf();
        this.invisible = buf.readBoolean();
        this.teams = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.pointId);
        buf.writeVarInt(this.timeToCaptureSeconds);
        buf.writeVarInt(this.radiusInBlocks);
        buf.writeUtf(this.ownedTeam);
        buf.writeBoolean(this.invisible);
        PacketOpenPoolEditor.writeStringList(buf, this.teams);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionEditorClient.openCapturePoint(
                        this.pos, this.pointId, this.timeToCaptureSeconds, this.radiusInBlocks,
                        this.ownedTeam, this.invisible, this.teams)));
        ctx.get().setPacketHandled(true);
    }
}
