package com.neoalive.tacz_sewv.zone;

import java.util.UUID;

/** Run with {@code ./gradlew selfCheckSpawnZones}. Pure layer and geometry checks, no world. */
public final class SpawnZonesSelfCheck {

    private SpawnZonesSelfCheck() {}

    private static ZoneLayers.Beacon beacon(int bx, int bz, int radius, boolean powered) {
        long pos = ((long) (bx & 0x3FFFFFF) << 38) | ((long) (bz & 0x3FFFFFF) << 12) | 64;
        return new ZoneLayers.Beacon(pos, bx >> 4, bz >> 4, UUID.randomUUID(), radius, powered);
    }

    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");

        // packing round-trips, negatives included
        for (int[] c : new int[][]{{0, 0}, {-1, 5}, {123456, -98765}, {-30000000 >> 4, 30000000 >> 4}}) {
            long k = ZoneGeometry.pack(c[0], c[1]);
            assert ZoneGeometry.x(k) == c[0] && ZoneGeometry.z(k) == c[1] : "pack " + c[0] + "," + c[1];
        }

        // precedence: beacon > manual nozone > priority > neutral
        ZoneLayers l = new ZoneLayers();
        long a = ZoneGeometry.pack(0, 0);
        assert l.type(a) == ZoneType.NEUTRAL;
        assert l.setManual(a, ZoneType.PRIORITY);
        assert !l.setManual(a, ZoneType.PRIORITY) : "same type is no change";
        assert l.type(a) == ZoneType.PRIORITY;
        assert l.priorityChunks().length == 1;
        assert l.setManual(a, ZoneType.NOZONE);
        assert l.type(a) == ZoneType.NOZONE && l.priorityChunks().length == 0;
        l.setManual(a, ZoneType.PRIORITY);
        ZoneLayers.Beacon b1 = beacon(8, 8, 2, true);
        l.putBeacon(b1);
        assert l.type(a) == ZoneType.NOZONE : "beacon beats manual priority";
        assert l.manualType(a) == ZoneType.PRIORITY : "manual layer untouched by beacon";
        assert l.beaconCoverCount() == 25;

        // overlap refcount: breaking one keeps the other's chunks
        ZoneLayers.Beacon b2 = beacon(16 * 3 + 8, 8, 2, true);
        l.putBeacon(b2);
        assert l.beaconCoverCount() == 25 + 25 - 10 : "two r2 squares 3 chunks apart share 2x5";
        l.removeBeacon(b1.pos);
        assert l.beaconCovered(ZoneGeometry.pack(2, 0)) : "shared chunk still covered by second beacon";
        assert !l.beaconCovered(ZoneGeometry.pack(-2, 0)) : "first beacon's own chunk released";
        assert l.type(a) == ZoneType.PRIORITY : "manual zone shows again once the beacon is gone";
        assert l.beaconCoverCount() == 25;

        // radius change and power loss apply as deltas
        l.putBeacon(beacon(16 * 3 + 8, 8, 4, true));
        assert l.beaconCoverCount() == 81;
        l.putBeacon(beacon(16 * 3 + 8, 8, 4, false));
        assert l.beaconCoverCount() == 0 : "unpowered beacon covers nothing";
        int rev = l.revision();
        l.putBeacon(beacon(16 * 3 + 8, 8, 4, false));
        assert l.revision() == rev : "identical update is not a change";

        // clearManual by type and area
        ZoneLayers m = new ZoneLayers();
        for (int x = -5; x <= 5; x++) m.setManual(ZoneGeometry.pack(x, 0), x % 2 == 0 ? ZoneType.NOZONE : ZoneType.PRIORITY);
        int prio = m.manualOf(ZoneType.PRIORITY).length;
        assert m.clearManual(ZoneType.PRIORITY, null) == prio;
        assert m.manualOf(ZoneType.PRIORITY).length == 0 && m.priorityChunks().length == 0;
        assert m.clearManual(null, k -> Math.abs(ZoneGeometry.x(k)) <= 2) == 3;

        // cost and radius clamp
        assert ZoneGeometry.chunksCovered(2) == 25 && ZoneGeometry.chunksCovered(10) == 441;
        assert ZoneGeometry.upkeepPerTick(10, 1) == 441 && ZoneGeometry.upkeepPerTick(3, 4) == 196;
        assert ZoneGeometry.upkeepPerTick(2, 0.15) == 4 && ZoneGeometry.upkeepPerTick(10, 0.15) == 67;
        assert ZoneGeometry.clampRadius(0, 10) == 2 && ZoneGeometry.clampRadius(99, 10) == 10;
        assert ZoneGeometry.clampRadius(9, 6) == 6;

        // ring pick never leaves [min, max] and is null when nothing fits
        long[] cands = new long[400];
        int i = 0;
        for (int x = -10; x < 10; x++) for (int z = -10; z < 10; z++) cands[i++] = ZoneGeometry.pack(x, z);
        for (double roll = 0; roll < 1; roll += 0.013) {
            long k = ZoneGeometry.pickInRing(cands, 0, 0, 40, 90, k2 -> true, roll);
            assert k != Long.MIN_VALUE;
            double d = Math.sqrt(ZoneGeometry.distSqToChunkCentre(0, 0, k));
            assert d >= 40 && d <= 90 : "picked outside ring: " + d;
        }
        assert ZoneGeometry.pickInRing(cands, 0, 0, 1000, 2000, k2 -> true, 0.5) == Long.MIN_VALUE;
        for (double roll = 0; roll < 1; roll += 0.05) {
            long k = ZoneGeometry.pickInRing(cands, 0, 0, 0, 200, k2 -> ZoneGeometry.x(k2) >= 3, roll);
            assert ZoneGeometry.x(k) >= 3 : "filter ignored";
        }
        assert ZoneGeometry.pickInRing(cands, 0, 0, 0, 200, k2 -> false, 0.5) == Long.MIN_VALUE;
        assert ZoneGeometry.pickInRing(new long[0], 0, 0, 0, 100, k2 -> true, 0.5) == Long.MIN_VALUE;

        // nearest allowed chunk
        long near = ZoneGeometry.nearestAllowed(0, 0, 3, k -> ZoneGeometry.x(k) >= 2);
        assert near != Long.MIN_VALUE && ZoneGeometry.x(near) == 2;
        assert ZoneGeometry.nearestAllowed(0, 0, 1, k -> ZoneGeometry.x(k) >= 2) == Long.MIN_VALUE;
        assert ZoneGeometry.nearestAllowed(4, 4, 0, k -> true) == ZoneGeometry.pack(4, 4);

        // client run merge: sorted by row then x, same-kind neighbours merged, signs respected
        var kinds = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        for (int x = -3; x <= 2; x++) kinds.put(ZoneGeometry.pack(x, -1), (byte) 1);
        kinds.put(ZoneGeometry.pack(3, -1), (byte) 2);
        kinds.put(ZoneGeometry.pack(5, -1), (byte) 2);
        kinds.put(ZoneGeometry.pack(0, 4), (byte) 3);
        var runs = com.neoalive.tacz_sewv.client.zone.ZoneViewClient.runs(kinds);
        assert runs.length == 4 : "runs " + runs.length;
        assert runs[0].cz() == -1 && runs[0].x0() == -3 && runs[0].x1() == 2 && runs[0].kind() == 1;
        assert runs[1].x0() == 3 && runs[1].x1() == 3 && runs[1].kind() == 2 : "kind change splits a run";
        assert runs[2].x0() == 5 : "gap splits a run";
        assert runs[3].cz() == 4;

        System.out.println("SpawnZonesSelfCheck: OK");
    }
}
