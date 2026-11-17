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
        ZoneDrawTool.reset();
        if (Minecraft.getInstance().getConnection() != null) {
            NetworkHandler.CHANNEL.sendToServer(new com.neoalive.tacz_sewv.network.PacketZoneView(false));
        }
    }

    public static void clear() {
        ZoneDrawTool.reset();
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

    /** Signed-sortable key: row in the high half, x biased in the low half so it sorts signed too. */
    private static long rowMajor(int cx, int cz) {
        return ((long) cz << 32) | ((cx & 0xFFFFFFFFL) ^ 0x80000000L);
    }

    private static int rowOf(long order) {
        return (int) (order >> 32);
    }

    private static int colOf(long order) {
        return (int) ((order & 0xFFFFFFFFL) ^ 0x80000000L);
    }

    /** Chunks merged into horizontal same-kind runs, sorted by row then x. */
    public static Run[] runs(Long2ByteOpenHashMap kinds) {
        long[] order = new long[kinds.size()];
        int n = 0;
        for (long k : kinds.keySet()) order[n++] = rowMajor(ZoneGeometry.x(k), ZoneGeometry.z(k));
        Arrays.sort(order);
        java.util.ArrayList<Run> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < order.length) {
            int cz = rowOf(order[i]), x0 = colOf(order[i]), x1 = x0;
            byte kind = kinds.get(ZoneGeometry.pack(x0, cz));
            int j = i + 1;
            while (j < order.length && rowOf(order[j]) == cz && colOf(order[j]) == x1 + 1
                    && kinds.get(ZoneGeometry.pack(x1 + 1, cz)) == kind) {
                x1++;
                j++;
            }
            out.add(new Run(cz, x0, x1, kind));
            i = j;
        }
        return out.toArray(new Run[0]);
    }
}
