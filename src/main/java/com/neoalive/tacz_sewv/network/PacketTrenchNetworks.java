package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.MapTrenchMarkers;
import com.neoalive.tacz_sewv.map.TrenchMarker;

/** Server→client sync of trench-network map markers. */
public class PacketTrenchNetworks {

    private final List<TrenchMarker> markers;

    public PacketTrenchNetworks(List<TrenchMarker> markers) {
        this.markers = markers;
    }

    public PacketTrenchNetworks(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<TrenchMarker> read = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            read.add(new TrenchMarker(
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readResourceKey(Registries.DIMENSION),
                    buf.readVarInt(),
                    buf.readBoolean()));
        }
        this.markers = read;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.markers.size());
        for (TrenchMarker marker : this.markers) {
            buf.writeVarInt(marker.networkId());
            buf.writeDouble(marker.x());
            buf.writeDouble(marker.y());
            buf.writeDouble(marker.z());
            buf.writeResourceKey(marker.dimension());
            buf.writeVarInt(marker.cellCount());
            buf.writeBoolean(marker.hasEmplacement());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MapTrenchMarkers.accept(this.markers)));
        ctx.get().setPacketHandled(true);
    }
}
