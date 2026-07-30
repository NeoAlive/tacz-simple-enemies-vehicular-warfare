package com.neoalive.tacz_sewv.network;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.client.InvasionHudClient;
import com.neoalive.tacz_sewv.invasion.InvasionHud;
import com.neoalive.tacz_sewv.invasion.InvasionTags;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C invasion match HUD + per-vehicle side colours for the SBW hover overlay.
 * {@code clear=true} tears the widget down.
 */
public class PacketInvasionHud {

    private final boolean clear;
    private final InvasionHud.Snapshot snapshot;
    private final List<int[]> vehicleSides; // [entityId, side]

    public static PacketInvasionHud clearPacket() {
        return new PacketInvasionHud(true, null, List.of());
    }

    public static PacketInvasionHud of(ServerLevel level, InvasionHud.Snapshot snapshot,
                                       InvasionHud.Layout layout) {
        List<int[]> sides = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof VehicleEntity)) continue;
            if (!entity.getPersistentData().getBoolean(InvasionTags.SPAWN)) continue;
            String team = entity.getPersistentData().getString(InvasionTags.TEAM);
            byte side = InvasionHud.sideOfTeam(team, layout.teamA(), layout.teamB());
            if (side == InvasionHud.SIDE_NEUTRAL) continue;
            sides.add(new int[]{entity.getId(), side});
        }
        return new PacketInvasionHud(false, snapshot, sides);
    }

    private PacketInvasionHud(boolean clear, InvasionHud.Snapshot snapshot, List<int[]> vehicleSides) {
        this.clear = clear;
        this.snapshot = snapshot;
        this.vehicleSides = vehicleSides;
    }

    public PacketInvasionHud(FriendlyByteBuf buf) {
        this.clear = buf.readBoolean();
        if (this.clear) {
            this.snapshot = null;
            this.vehicleSides = List.of();
            return;
        }
        this.snapshot = InvasionHud.Snapshot.decode(buf);
        int n = buf.readVarInt();
        List<int[]> sides = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sides.add(new int[]{buf.readVarInt(), buf.readByte()});
        }
        this.vehicleSides = sides;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.clear);
        if (this.clear) return;
        this.snapshot.encode(buf);
        buf.writeVarInt(this.vehicleSides.size());
        for (int[] pair : this.vehicleSides) {
            buf.writeVarInt(pair[0]);
            buf.writeByte(pair[1]);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (this.clear || this.snapshot == null) {
                InvasionHudClient.clear();
            } else {
                InvasionHudClient.accept(this.snapshot, this.vehicleSides);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
