package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.InvasionEditorClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** Server → client: open the capture_point config screen. */
public class PacketOpenCapturePointGui {

    private final BlockPos pos;
    private final int pointId;
    private final boolean showBillboard;
    private final double billboardYOffset;
    private final int timeToCaptureSeconds;
    private final int radiusInBlocks;
    private final String ownedTeam;
    private final List<String> teams;

    public PacketOpenCapturePointGui(BlockPos pos, int pointId, boolean showBillboard, double billboardYOffset,
                                     int timeToCaptureSeconds, int radiusInBlocks, String ownedTeam,
                                     List<String> teams) {
        this.pos = pos;
        this.pointId = pointId;
        this.showBillboard = showBillboard;
        this.billboardYOffset = billboardYOffset;
        this.timeToCaptureSeconds = timeToCaptureSeconds;
        this.radiusInBlocks = radiusInBlocks;
        this.ownedTeam = ownedTeam == null ? "" : ownedTeam;
        this.teams = teams;
    }

    public PacketOpenCapturePointGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.pointId = buf.readVarInt();
        this.showBillboard = buf.readBoolean();
        this.billboardYOffset = buf.readDouble();
        this.timeToCaptureSeconds = buf.readVarInt();
        this.radiusInBlocks = buf.readVarInt();
        this.ownedTeam = buf.readUtf();
        this.teams = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.pointId);
        buf.writeBoolean(this.showBillboard);
        buf.writeDouble(this.billboardYOffset);
        buf.writeVarInt(this.timeToCaptureSeconds);
        buf.writeVarInt(this.radiusInBlocks);
        buf.writeUtf(this.ownedTeam);
        PacketOpenPoolEditor.writeStringList(buf, this.teams);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                InvasionEditorClient.openCapturePoint(
                        this.pos, this.pointId, this.showBillboard, this.billboardYOffset,
                        this.timeToCaptureSeconds, this.radiusInBlocks, this.ownedTeam, this.teams)));
        ctx.get().setPacketHandled(true);
    }
}
