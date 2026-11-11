package com.neoalive.tacz_sewv.client.zone;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSpawnZoneState;
import com.neoalive.tacz_sewv.zone.ZoneGeometry;

/**
 * Client copy of the spawn zones for the operator map view, replaced wholesale on every push.
 * Chunks are pre-merged into horizontal runs so a frame only projects and fills them.
 */
public final class ZoneViewClient {

    /** Display kinds, in precedence order. */
    public static final byte PRIORITY = 1, MANUAL_NOZONE = 2, BEACON_NOZONE = 3;

    /** One horizontal run of same-kind chunks: row cz, chunks x0..x1 inclusive. */
    public record Run(int cz, int x0, int x1, byte kind) {}

    public record Beacon(int x, int z, int radius, boolean powered) {}

    public record Snapshot(String dimension, Long2ByteOpenHashMap kinds, Run[] runs, Beacon[] beacons) {}

    private static final Snapshot EMPTY = new Snapshot("", new Long2ByteOpenHashMap(), new Run[0], new Beacon[0]);

    private static volatile boolean active;
    private static volatile Snapshot snapshot = EMPTY;

    private ZoneViewClient() {}

    public static boolean active() {
        return active;
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static void openView() {
        active = true;
        NetworkHandler.CHANNEL.sendToServer(new com.neoalive.tacz_sewv.network.PacketZoneView(true));
    }

    public static void closeView() {
        if (!active) return;
        active = false;
        snapshot = EMPTY;
        if (Minecraft.getInstance().getConnection() != null) {
            NetworkHandler.CHANNEL.sendToServer(new com.neoalive.tacz_sewv.network.PacketZoneView(false));
        }
    }

    public static void clear() {
        active = false;
        snapshot = EMPTY;
    }

    public static void accept(PacketSpawnZoneState p) {
        if (!active) return;
        Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
        for (long k : p.priority) kinds.put(k, PRIORITY);
        for (long k : p.manualNo) kinds.put(k, MANUAL_NOZONE);
        for (long k : p.beaconNo) kinds.put(k, BEACON_NOZONE);
        Beacon[] beacons = new Beacon[p.beaconPos.length];
        for (int i = 0; i < beacons.length; i++) {
            long bp = p.beaconPos[i];
            beacons[i] = new Beacon(net.minecraft.core.BlockPos.getX(bp), net.minecraft.core.BlockPos.getZ(bp),
                    p.beaconRadius[i], p.beaconPowered[i]);
        }
        snapshot = new Snapshot(p.dimension, kinds, runs(kinds), beacons);
    }

    static Run[] runs(Long2ByteOpenHashMap kinds) {
        long[] keys = kinds.keySet().toLongArray();
        Long[] sorted = Arrays.stream(keys).boxed().toArray(Long[]::new);
        Arrays.sort(sorted, (a, b) -> {
            int za = ZoneGeometry.z(a), zb = ZoneGeometry.z(b);
            if (za != zb) return Integer.compare(za, zb);
            return Integer.compare(ZoneGeometry.x(a), ZoneGeometry.x(b));
        });
        java.util.ArrayList<Run> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < sorted.length) {
            long k = sorted[i];
            int cz = ZoneGeometry.z(k), x0 = ZoneGeometry.x(k), x1 = x0;
            byte kind = kinds.get(k);
            int j = i + 1;
            while (j < sorted.length) {
                long n = sorted[j];
                if (ZoneGeometry.z(n) != cz || ZoneGeometry.x(n) != x1 + 1 || kinds.get(n) != kind) break;
                x1++;
                j++;
            }
            out.add(new Run(cz, x0, x1, kind));
            i = j;
        }
        return out.toArray(new Run[0]);
    }
}
