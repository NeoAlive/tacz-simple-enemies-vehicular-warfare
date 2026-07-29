package com.neoalive.tacz_sewv.network;

import com.neoalive.tacz_sewv.client.MapMarkers;
import com.neoalive.tacz_sewv.util.BattleFieldMarker;
import com.neoalive.tacz_sewv.util.CrewFacts;
import com.neoalive.tacz_sewv.util.MarkerOrder;
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
 * Server→client map sync: crewed vehicles one player's side can see, plus optional debug
 * {@link BattleFieldMarker}s for groups those markers belong to.
 *
 * <p>Each player gets their own hulls plus whatever their side has spotted, and <b>never</b> another
 * player's units — a client cannot learn where anyone else's PMC is. Ordering is
 * checked separately and independently: SEM's own order packet refuses a unit the sender does not
 * own, so neither side relies on the other to be the gate.
 *
 * <p>The handler is wrapped in {@link DistExecutor} because {@link MapMarkers} is client-only; on a
 * dedicated server this packet is never received, so that reference is never resolved.
 */
public class PacketOwnedVehicles {

    private final List<VehicleMarker> markers;
    private final List<BattleFieldMarker> battleFields;

    public PacketOwnedVehicles(List<VehicleMarker> markers, List<BattleFieldMarker> battleFields) {
        this.markers = markers;
        this.battleFields = battleFields;
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
                    VehicleMarker.Allegiance.byId(buf.readByte()),
                    CrewFacts.Faction.byId(buf.readByte()),
                    MarkerOrder.decode(buf),
                    buf.readResourceKey(Registries.DIMENSION),
                    buf.readFloat(),
                    buf.readFloat(),
                    VehicleMarker.CommandRole.byId(buf.readByte()),
                    buf.readVarInt(),
                    VehicleMarker.PlayRole.byId(buf.readByte()),
                    buf.readInt() & 0xFFFFFF));
        }
        this.markers = read;

        int bfCount = buf.readVarInt();
        List<BattleFieldMarker> bfs = new ArrayList<>(bfCount);
        for (int i = 0; i < bfCount; i++) {
            bfs.add(new BattleFieldMarker(
                    buf.readVarInt(),
                    buf.readResourceKey(Registries.DIMENSION),
                    buf.readDouble(),
                    buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readUtf(64)));
        }
        this.battleFields = bfs;
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
            buf.writeByte(marker.allegiance().ordinal());
            buf.writeByte(marker.faction().ordinal());
            marker.order().encode(buf);
            buf.writeResourceKey(marker.dimension());
            buf.writeFloat(marker.healthFrac());
            buf.writeFloat(marker.energyFrac());
            buf.writeByte(marker.commandRole().ordinal());
            buf.writeVarInt(marker.groupId());
            buf.writeByte(marker.playRole().ordinal());
            buf.writeInt(marker.tintRgb() & 0xFFFFFF);
        }
        buf.writeVarInt(this.battleFields.size());
        for (BattleFieldMarker bf : this.battleFields) {
            buf.writeVarInt(bf.groupId());
            buf.writeResourceKey(bf.dimension());
            buf.writeDouble(bf.y());
            buf.writeDouble(bf.friendlyX());
            buf.writeDouble(bf.friendlyZ());
            buf.writeDouble(bf.enemyX());
            buf.writeDouble(bf.enemyZ());
            buf.writeFloat((float) bf.axisX());
            buf.writeFloat((float) bf.axisZ());
            buf.writeBoolean(bf.flankLeft());
            buf.writeDouble(bf.flankLeftX());
            buf.writeDouble(bf.flankLeftZ());
            buf.writeBoolean(bf.flankRight());
            buf.writeDouble(bf.flankRightX());
            buf.writeDouble(bf.flankRightZ());
            buf.writeUtf(bf.playLabel() != null ? bf.playLabel() : "", 64);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> MapMarkers.accept(this.markers, this.battleFields)));
        ctx.get().setPacketHandled(true);
    }
}
