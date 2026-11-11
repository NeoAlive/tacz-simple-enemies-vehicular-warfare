package com.neoalive.tacz_sewv.zone;

import java.util.UUID;
import java.util.function.LongPredicate;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * The two zone layers of one dimension, with no Minecraft types so the self-check can run it.
 * Manual zones are set by operators. Beacon zones are derived from beacon records: each powered
 * beacon adds one to the cover count of every chunk in its square, so overlapping beacons work.
 */
public final class ZoneLayers {

    /** One beacon. {@code pos} is a packed BlockPos, {@code cx/cz} its chunk. */
    public static final class Beacon {
        public final long pos;
        public final int cx, cz;
        public UUID owner;
        public int radius;
        public boolean powered;

        public Beacon(long pos, int cx, int cz, UUID owner, int radius, boolean powered) {
            this.pos = pos;
            this.cx = cx;
            this.cz = cz;
            this.owner = owner;
            this.radius = radius;
            this.powered = powered;
        }
    }

    private final Long2ByteOpenHashMap manual = new Long2ByteOpenHashMap();
    private final Long2IntOpenHashMap beaconCover = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<Beacon> beacons = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet priority = new LongOpenHashSet();
    private long[] priorityArray = new long[0];
    private boolean priorityArrayStale;
    private int revision;

    public ZoneType type(long chunk) {
        if (this.beaconCover.get(chunk) > 0) return ZoneType.NOZONE;
        return ZoneType.byId(this.manual.get(chunk));
    }

    public ZoneType manualType(long chunk) {
        return ZoneType.byId(this.manual.get(chunk));
    }

    public boolean beaconCovered(long chunk) {
        return this.beaconCover.get(chunk) > 0;
    }

    /** No zone of either layer: every chunk is neutral. */
    public boolean isEmpty() {
        return this.manual.isEmpty() && this.beaconCover.isEmpty();
    }

    public int revision() {
        return this.revision;
    }

    /** Sets or clears ({@code NEUTRAL}) the manual zone of a chunk. True if anything changed. */
    public boolean setManual(long chunk, ZoneType type) {
        byte prev = this.manual.get(chunk);
        if (type == ZoneType.NEUTRAL) {
            if (prev == 0) return false;
            this.manual.remove(chunk);
        } else {
            if (prev == type.ordinal()) return false;
            this.manual.put(chunk, (byte) type.ordinal());
        }
        if (prev == ZoneType.PRIORITY.ordinal()) this.priority.remove(chunk);
        if (type == ZoneType.PRIORITY) this.priority.add(chunk);
        this.priorityArrayStale = true;
        this.revision++;
        return true;
    }

    /** Removes every manual zone matching the filter. Returns how many were removed. */
    public int clearManual(ZoneType onlyType, LongPredicate where) {
        LongArrayList doomed = new LongArrayList();
        for (Long2ByteMap.Entry e : this.manual.long2ByteEntrySet()) {
            if (onlyType != null && e.getByteValue() != onlyType.ordinal()) continue;
            if (where != null && !where.test(e.getLongKey())) continue;
            doomed.add(e.getLongKey());
        }
        for (int i = 0; i < doomed.size(); i++) setManual(doomed.getLong(i), ZoneType.NEUTRAL);
        return doomed.size();
    }

    public Beacon beacon(long pos) {
        return this.beacons.get(pos);
    }

    public int beaconCount() {
        return this.beacons.size();
    }

    public Iterable<Beacon> beacons() {
        return this.beacons.values();
    }

    /** Adds or updates a beacon, applying only the change to the cover counts. */
    public void putBeacon(Beacon next) {
        Beacon prev = this.beacons.get(next.pos);
        if (prev != null && prev.radius == next.radius && prev.powered == next.powered) {
            prev.owner = next.owner;
            return;
        }
        if (prev != null && prev.powered) cover(prev, -1);
        this.beacons.put(next.pos, next);
        if (next.powered) cover(next, 1);
        this.revision++;
    }

    public boolean removeBeacon(long pos) {
        Beacon prev = this.beacons.remove(pos);
        if (prev == null) return false;
        if (prev.powered) cover(prev, -1);
        this.revision++;
        return true;
    }

    private void cover(Beacon b, int delta) {
        ZoneGeometry.square(b.cx, b.cz, b.radius, k -> {
            int n = this.beaconCover.get(k) + delta;
            if (n <= 0) this.beaconCover.remove(k);
            else this.beaconCover.put(k, n);
        });
    }

    public long[] priorityChunks() {
        if (this.priorityArrayStale) {
            this.priorityArray = this.priority.toLongArray();
            this.priorityArrayStale = false;
        }
        return this.priorityArray;
    }

    public long[] manualOf(ZoneType type) {
        LongArrayList out = new LongArrayList();
        for (Long2ByteMap.Entry e : this.manual.long2ByteEntrySet())
            if (e.getByteValue() == type.ordinal()) out.add(e.getLongKey());
        return out.toLongArray();
    }

    public long[] beaconCovered() {
        LongArrayList out = new LongArrayList(this.beaconCover.size());
        for (LongIterator it = this.beaconCover.keySet().iterator(); it.hasNext(); ) out.add(it.nextLong());
        return out.toLongArray();
    }

    public int manualCount() {
        return this.manual.size();
    }

    public int beaconCoverCount() {
        return this.beaconCover.size();
    }

    public Long2ByteOpenHashMap manualView() {
        return this.manual;
    }

    /** Raw put used when loading saved data; does not bump the revision. */
    void loadManual(long chunk, byte type) {
        if (type <= 0 || type >= ZoneType.values().length) return;
        this.manual.put(chunk, type);
        if (type == ZoneType.PRIORITY.ordinal()) this.priority.add(chunk);
        this.priorityArrayStale = true;
    }
}
