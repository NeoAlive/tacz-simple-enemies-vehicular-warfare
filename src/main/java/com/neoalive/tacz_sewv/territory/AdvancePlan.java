package com.neoalive.tacz_sewv.territory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A baked Advance Plan: the painted region split into layers, the arrows drawn for it, and how far it has got.
 *
 * <p>The geometry (region, layer per chunk, arrows) is fixed at bake time and never recomputed as claims grow; what
 * changes is the run state and the current layer. Everything here is id-free or UUID-based so it survives a reload; the
 * things that must NOT survive one (the soak deadline, the parent-loss streak, the last hold reason) live in
 * {@link AdvancePlanManager} as transient runtime state.
 */
public final class AdvancePlan {

    public static final byte STOPPED = 0;
    public static final byte RUNNING = 1;

    private final long[] region;
    /** Compact layer index of each region chunk, parallel to {@link #region}. */
    private final int[] layerOf;
    private final int layerCount;
    /** Arrows as flat groups of four: blockX, blockZ, dx, dz. */
    private final int[] arrows;

    private byte state = STOPPED;
    /** The layer being worked; equal to {@code layerCount} once every layer is claimed. */
    private int currentLayer;
    /** The units snapshotted at Start (empty while stopped and never started). */
    private final List<UUID> units = new ArrayList<>();

    private transient Set<Long> regionSet;

    public AdvancePlan(long[] region, int[] layerOf, int layerCount, int[] arrows) {
        this.region = region;
        this.layerOf = layerOf;
        this.layerCount = layerCount;
        this.arrows = arrows;
    }

    public long[] region() { return region; }
    public int[] layerOf() { return layerOf; }
    public int layerCount() { return layerCount; }
    public int[] arrows() { return arrows; }
    public byte state() { return state; }
    public int currentLayer() { return currentLayer; }
    public List<UUID> units() { return units; }

    public void setState(byte state) { this.state = state; }

    public void advanceLayer() { currentLayer++; }

    public Set<Long> regionSet() {
        if (regionSet == null) {
            Set<Long> set = new HashSet<>(region.length * 2);
            for (long key : region) set.add(key);
            regionSet = set;
        }
        return regionSet;
    }

    /** The chunks of layer {@code index}. */
    public Set<Long> layer(int index) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < region.length; i++) {
            if (layerOf[i] == index) out.add(region[i]);
        }
        return out;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("region", region);
        tag.putIntArray("layerOf", layerOf);
        tag.putInt("layerCount", layerCount);
        tag.putIntArray("arrows", arrows);
        tag.putByte("state", state);
        tag.putInt("current", currentLayer);
        ListTag list = new ListTag();
        for (UUID id : units) list.add(NbtUtils.createUUID(id));
        tag.put("units", list);
        return tag;
    }

    public static AdvancePlan load(CompoundTag tag) {
        AdvancePlan plan = new AdvancePlan(tag.getLongArray("region"), tag.getIntArray("layerOf"),
                tag.getInt("layerCount"), tag.getIntArray("arrows"));
        plan.state = tag.getByte("state");
        plan.currentLayer = tag.getInt("current");
        for (Tag t : tag.getList("units", Tag.TAG_INT_ARRAY)) plan.units.add(NbtUtils.loadUUID(t));
        return plan;
    }
}
