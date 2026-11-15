package com.neoalive.tacz_sewv.airport;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension set of helipads that have passed Check Clearance, by master block position. The
 * volume is derived ({@link HelipadClearance#volume}), so a land order or the map can resolve a pad
 * without loading its chunk. Same lifecycle as {@link AirportRegistry}: the block entity notes and
 * forgets itself.
 */
public class HelipadRegistry extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_helipads";

    private final Set<Long> pads = new HashSet<>();
    /** Pads voided while their block entity was unloaded; it un-clears itself on load. */
    private final Set<Long> stale = new HashSet<>();

    public static HelipadRegistry load(CompoundTag nbt) {
        HelipadRegistry data = new HelipadRegistry();
        ListTag list = nbt.getList("Pads", Tag.TAG_LONG);
        for (Tag t : list) data.pads.add(((LongTag) t).getAsLong());
        for (long p : nbt.getLongArray("Stale")) data.stale.add(p);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (long pos : this.pads) list.add(LongTag.valueOf(pos));
        nbt.put("Pads", list);
        nbt.putLongArray("Stale", this.stale.stream().mapToLong(Long::longValue).toArray());
        return nbt;
    }

    public static HelipadRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(HelipadRegistry::load, HelipadRegistry::new, DATA_NAME);
    }

    public void note(BlockPos pad) {
        if (this.pads.add(pad.asLong())) setDirty();
    }

    public void forget(BlockPos pad) {
        if (this.pads.remove(pad.asLong())) setDirty();
    }

    /** Forgets a pad whose block entity is not loaded and remembers to un-clear it on load. */
    public void invalidateUnloaded(BlockPos pad) {
        this.pads.remove(pad.asLong());
        this.stale.add(pad.asLong());
        setDirty();
    }

    /** True once for a pad voided while unloaded. */
    public boolean takeStale(BlockPos pad) {
        if (!this.stale.remove(pad.asLong())) return false;
        setDirty();
        return true;
    }

    /** Cheap membership test — prefer this over {@link #pads()} when the caller wants one answer. */
    public boolean contains(BlockPos pad) {
        return this.pads.contains(pad.asLong());
    }

    /** A copy, so callers can act on pads (and drop stale ones) while iterating. */
    public Set<BlockPos> pads() {
        Set<BlockPos> out = new HashSet<>(this.pads.size());
        for (long p : this.pads) out.add(BlockPos.of(p));
        return out;
    }
}
