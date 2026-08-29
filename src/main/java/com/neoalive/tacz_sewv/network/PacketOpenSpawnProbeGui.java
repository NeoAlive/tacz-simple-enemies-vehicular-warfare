package com.neoalive.tacz_sewv.network;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.SpawnProbeClient;

/** Server → client: open the spawn_probe config screen. */
public class PacketOpenSpawnProbeGui {

    private final BlockPos pos;
    private final List<String> vehicleList;
    private final boolean preCrewedSpawn;
    private final List<String> catalog;

    public PacketOpenSpawnProbeGui(BlockPos pos, List<String> vehicleList, boolean preCrewedSpawn,
                                   List<String> catalog) {
        this.pos = pos;
        this.vehicleList = vehicleList;
        this.preCrewedSpawn = preCrewedSpawn;
        this.catalog = catalog;
    }

    public PacketOpenSpawnProbeGui(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.vehicleList = PacketOpenPoolEditor.readStringList(buf);
        this.preCrewedSpawn = buf.readBoolean();
        this.catalog = PacketOpenPoolEditor.readStringList(buf);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        PacketOpenPoolEditor.writeStringList(buf, this.vehicleList);
        buf.writeBoolean(this.preCrewedSpawn);
        PacketOpenPoolEditor.writeStringList(buf, this.catalog);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                SpawnProbeClient.openScreen(this.pos, this.vehicleList, this.preCrewedSpawn, this.catalog)));
        ctx.get().setPacketHandled(true);
    }
}
