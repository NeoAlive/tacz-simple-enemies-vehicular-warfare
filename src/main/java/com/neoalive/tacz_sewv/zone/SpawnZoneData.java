package com.neoalive.tacz_sewv.zone;

import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension saved spawn zones: the manual layer and the beacon records. */
public class SpawnZoneData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_spawn_zones";

    private final ZoneLayers layers = new ZoneLayers();

    public static SpawnZoneData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SpawnZoneData::load, SpawnZoneData::new, DATA_NAME);
    }

    public static SpawnZoneData load(CompoundTag nbt) {
        SpawnZoneData data = new SpawnZoneData();
        long[] keys = nbt.getLongArray("ManualKeys");
        byte[] types = nbt.getByteArray("ManualTypes");
        int n = Math.min(keys.length, types.length);
        for (int i = 0; i < n; i++) data.layers.loadManual(keys[i], types[i]);
        ListTag list = nbt.getList("Beacons", Tag.TAG_COMPOUND);
        for (Tag t : list) {
            CompoundTag b = (CompoundTag) t;
            BlockPos pos = BlockPos.of(b.getLong("Pos"));
            UUID owner = b.hasUUID("Owner") ? b.getUUID("Owner") : null;
            data.layers.putBeacon(new ZoneLayers.Beacon(pos.asLong(), pos.getX() >> 4, pos.getZ() >> 4, owner,
                    ZoneGeometry.clampRadius(b.getInt("Radius"), ZoneGeometry.MAX_RADIUS), b.getBoolean("Powered")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        int n = this.layers.manualCount();
        long[] keys = new long[n];
        byte[] types = new byte[n];
        int i = 0;
        for (Long2ByteMap.Entry e : this.layers.manualView().long2ByteEntrySet()) {
            keys[i] = e.getLongKey();
            types[i] = e.getByteValue();
            i++;
        }
        nbt.putLongArray("ManualKeys", keys);
        nbt.putByteArray("ManualTypes", types);
        ListTag list = new ListTag();
        for (ZoneLayers.Beacon b : this.layers.beacons()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", b.pos);
            if (b.owner != null) t.putUUID("Owner", b.owner);
            t.putInt("Radius", b.radius);
            t.putBoolean("Powered", b.powered);
            list.add(t);
        }
        nbt.put("Beacons", list);
        return nbt;
    }

    public ZoneLayers layers() {
        return this.layers;
    }

    public ZoneType type(int cx, int cz) {
        return this.layers.type(ZoneGeometry.pack(cx, cz));
    }

    public boolean setManual(int cx, int cz, ZoneType type) {
        boolean changed = this.layers.setManual(ZoneGeometry.pack(cx, cz), type);
        if (changed) setDirty();
        return changed;
    }

    public int clearManual(ZoneType onlyType, java.util.function.LongPredicate where) {
        int n = this.layers.clearManual(onlyType, where);
        if (n > 0) setDirty();
        return n;
    }

    public void putBeacon(BlockPos pos, UUID owner, int radius, boolean powered) {
        int before = this.layers.revision();
        this.layers.putBeacon(new ZoneLayers.Beacon(pos.asLong(), pos.getX() >> 4, pos.getZ() >> 4, owner,
                radius, powered));
        if (this.layers.revision() != before) setDirty();
    }

    public void removeBeacon(BlockPos pos) {
        if (this.layers.removeBeacon(pos.asLong())) setDirty();
    }
}
