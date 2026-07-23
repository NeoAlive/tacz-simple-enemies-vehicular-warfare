package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.util.VehicleMarker;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The only server→client packet on this channel: a player's own PMC-crewed vehicles, for the map
 * markers. Sent by {@code OwnedVehicleTracker} on a timer, to that one player.
 *
 * <p>Each player gets <b>only their own</b> hulls, which is the whole privacy model — a client can
 * never learn where anyone else's units are, so the map cannot be used to scout with. Ordering is
 * checked separately and independently: SEM's own order packet refuses a unit the sender does not
 * own, so neither side relies on the other to be the gate.
 *
 * <p>The handler is wrapped in {@link DistExecutor} because {@link MapMarkers} is client-only; on a
 * dedicated server this packet is never received, so that reference is never resolved.
 */
public class PacketOwnedVehicles {

    private final List<VehicleMarker> markers;

    public PacketOwnedVehicles(List<VehicleMarker> markers) {
        this.markers = markers;
    }

    public PacketOwnedVehicles(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<VehicleMarker> read = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            read.add(new VehicleMarker(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    VehicleMarker.Kind.byId(buf.readByte()),
                    buf.readResourceKey(Registries.DIMENSION)));
        }
        this.markers = read;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.markers.size());
        for (VehicleMarker marker : this.markers) {
            buf.writeVarInt(marker.driverId());
            buf.writeVarInt(marker.vehicleId());
            buf.writeDouble(marker.x());
            buf.writeDouble(marker.y());
            buf.writeDouble(marker.z());
            buf.writeFloat(marker.yaw());
            buf.writeByte(marker.kind().ordinal());
            buf.writeResourceKey(marker.dimension());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MapMarkers.accept(this.markers)));
        ctx.get().setPacketHandled(true);
    }
}
