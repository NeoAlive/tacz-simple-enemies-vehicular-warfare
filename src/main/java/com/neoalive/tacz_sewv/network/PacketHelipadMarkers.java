package com.neoalive.tacz_sewv.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.map.HelipadMarker;

/**
 * Server to client: every cleared helipad, sent with the owned-vehicle sync. Separate from
 * {@link PacketOwnedVehicles} because it is not per-player and would otherwise widen that wire format.
 */
public class PacketHelipadMarkers {

    private final List<HelipadMarker> markers;

    public PacketHelipadMarkers(List<HelipadMarker> markers) {
        this.markers = markers;
    }

    public PacketHelipadMarkers(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<HelipadMarker> read = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            read.add(new HelipadMarker(buf.readBlockPos(), buf.readResourceKey(Registries.DIMENSION)));
        }
        this.markers = read;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.markers.size());
        for (HelipadMarker marker : this.markers) {
            buf.writeBlockPos(marker.pos());
            buf.writeResourceKey(marker.dimension());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MapMarkers.acceptHelipads(this.markers)));
        ctx.get().setPacketHandled(true);
    }
}
