package com.neoalive.tacz_sewv.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import com.neoalive.tacz_sewv.client.zone.ZoneViewClient;

/**
 * Server to client: every spawn zone of the viewer's dimension, as packed chunk keys per layer,
 * plus the beacons (packed block pos, radius, powered). Replaces the client copy wholesale.
 */
public class PacketSpawnZoneState {

    public static final int MAX_PER_LAYER = 20000;
    private static final int MAX_BEACONS = 4096;

    public final String dimension;
    public final long[] priority;
    public final long[] manualNo;
    public final long[] beaconNo;
    public final long[] beaconPos;
    public final int[] beaconRadius;
    public final boolean[] beaconPowered;

    public PacketSpawnZoneState(String dimension, long[] priority, long[] manualNo, long[] beaconNo,
            long[] beaconPos, int[] beaconRadius, boolean[] beaconPowered) {
        this.dimension = dimension;
        this.priority = priority;
        this.manualNo = manualNo;
        this.beaconNo = beaconNo;
        this.beaconPos = beaconPos;
        this.beaconRadius = beaconRadius;
        this.beaconPowered = beaconPowered;
    }

    public PacketSpawnZoneState(FriendlyByteBuf buf) {
        this.dimension = buf.readUtf(256);
        this.priority = readLongs(buf, MAX_PER_LAYER);
        this.manualNo = readLongs(buf, MAX_PER_LAYER);
        this.beaconNo = readLongs(buf, MAX_PER_LAYER);
        int n = Math.min(buf.readVarInt(), MAX_BEACONS);
        this.beaconPos = new long[n];
        this.beaconRadius = new int[n];
        this.beaconPowered = new boolean[n];
        for (int i = 0; i < n; i++) {
            this.beaconPos[i] = buf.readLong();
            this.beaconRadius[i] = buf.readByte();
            this.beaconPowered[i] = buf.readBoolean();
        }
    }

    private static long[] readLongs(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("zone layer too large: " + n);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = buf.readLong();
        return out;
    }

    private static void writeLongs(FriendlyByteBuf buf, long[] a) {
        buf.writeVarInt(a.length);
        for (long v : a) buf.writeLong(v);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.dimension, 256);
        writeLongs(buf, this.priority);
        writeLongs(buf, this.manualNo);
        writeLongs(buf, this.beaconNo);
        int n = Math.min(this.beaconPos.length, MAX_BEACONS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeLong(this.beaconPos[i]);
            buf.writeByte(this.beaconRadius[i]);
            buf.writeBoolean(this.beaconPowered[i]);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ZoneViewClient.accept(this)));
        ctx.get().setPacketHandled(true);
    }
}
