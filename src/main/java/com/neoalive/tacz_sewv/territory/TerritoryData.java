package com.neoalive.tacz_sewv.territory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which players have Territory Mode switched on. World SavedData rather than player NBT so a unit that
 * loads in while its commander is offline can still be checked (see {@code TerritoryManager.onJoin}).
 */
public class TerritoryData extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_territory";

    private final Set<UUID> modeOn = new HashSet<>();
    /**
     * Players who have had at least one readable claim while Territory Mode was watching. Persisted, because
     * "had claims, now none" has to survive a restart to stay distinguishable from "OpenPAC not readable yet".
     */
    private final Set<UUID> seenClaims = new HashSet<>();
    /** Drawn manual line per player, per dimension (chunks in drag order). Display state, not a saved preset. */
    private final Map<UUID, Map<String, long[]>> lines = new HashMap<>();
    /** Baked Advance Plan per player, per dimension: one at a time, replaced by the next successful bake. */
    private final Map<UUID, Map<String, AdvancePlan>> plans = new HashMap<>();

    public static TerritoryData load(CompoundTag nbt) {
        TerritoryData data = new TerritoryData();
        ListTag list = nbt.getList("modeOn", Tag.TAG_INT_ARRAY);
        for (Tag t : list) data.modeOn.add(NbtUtils.loadUUID(t));
        for (Tag t : nbt.getList("seenClaims", Tag.TAG_INT_ARRAY)) data.seenClaims.add(NbtUtils.loadUUID(t));
        for (Tag t : nbt.getList("lines", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            data.lines.computeIfAbsent(entry.getUUID("player"), u -> new HashMap<>())
                    .put(entry.getString("dim"), entry.getLongArray("chunks"));
        }
        for (Tag t : nbt.getList("plans", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            data.plans.computeIfAbsent(entry.getUUID("player"), u -> new HashMap<>())
                    .put(entry.getString("dim"), AdvancePlan.load(entry.getCompound("plan")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (UUID id : modeOn) list.add(NbtUtils.createUUID(id));
        nbt.put("modeOn", list);
        ListTag seen = new ListTag();
        for (UUID id : seenClaims) seen.add(NbtUtils.createUUID(id));
        nbt.put("seenClaims", seen);
        ListTag drawn = new ListTag();
        for (Map.Entry<UUID, Map<String, long[]>> player : lines.entrySet()) {
            for (Map.Entry<String, long[]> dim : player.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player.getKey());
                entry.putString("dim", dim.getKey());
                entry.putLongArray("chunks", dim.getValue());
                drawn.add(entry);
            }
        }
        nbt.put("lines", drawn);
        ListTag planList = new ListTag();
        for (Map.Entry<UUID, Map<String, AdvancePlan>> player : plans.entrySet()) {
            for (Map.Entry<String, AdvancePlan> dim : player.getValue().entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player.getKey());
                entry.putString("dim", dim.getKey());
                entry.put("plan", dim.getValue().save());
                planList.add(entry);
            }
        }
        nbt.put("plans", planList);
        return nbt;
    }

    public static TerritoryData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return new TerritoryData();
        return overworld.getDataStorage().computeIfAbsent(TerritoryData::load, TerritoryData::new, DATA_NAME);
    }

    public boolean isOn(UUID player) {
        return modeOn.contains(player);
    }

    /** The drawn line for {@code player} in {@code dim}; empty when none. */
    public long[] getLine(UUID player, String dim) {
        Map<String, long[]> perDim = lines.get(player);
        long[] line = perDim == null ? null : perDim.get(dim);
        return line == null ? new long[0] : line;
    }

    public void setLine(UUID player, String dim, long[] chunks) {
        lines.computeIfAbsent(player, u -> new HashMap<>()).put(dim, chunks);
        setDirty();
    }

    public void clearLine(UUID player, String dim) {
        Map<String, long[]> perDim = lines.get(player);
        if (perDim != null && perDim.remove(dim) != null) setDirty();
    }

    /** A unit died: it leaves every plan snapshot for good (a dead unit is never "temporarily unavailable"). */
    public void forgetUnit(UUID unit) {
        boolean changed = false;
        for (Map<String, AdvancePlan> perDim : plans.values()) {
            for (AdvancePlan plan : perDim.values()) changed |= plan.units().remove(unit);
        }
        if (changed) setDirty();
    }

    /** The baked plan for {@code player} in {@code dim}, or null. */
    public AdvancePlan getPlan(UUID player, String dim) {
        Map<String, AdvancePlan> perDim = plans.get(player);
        return perDim == null ? null : perDim.get(dim);
    }

    public void setPlan(UUID player, String dim, AdvancePlan plan) {
        plans.computeIfAbsent(player, u -> new HashMap<>()).put(dim, plan);
        setDirty();
    }

    public void clearPlan(UUID player, String dim) {
        Map<String, AdvancePlan> perDim = plans.get(player);
        if (perDim != null && perDim.remove(dim) != null) setDirty();
    }

    /** Mode off: every dimension's plan goes with it. */
    public void clearPlans(UUID player) {
        if (plans.remove(player) != null) setDirty();
    }

    /** Mode off: every dimension's line goes with it. */
    public void clearLines(UUID player) {
        if (lines.remove(player) != null) setDirty();
    }

    public boolean hasSeenClaims(UUID player) {
        return seenClaims.contains(player);
    }

    public void markSeenClaims(UUID player) {
        if (seenClaims.add(player)) setDirty();
    }

    public void set(UUID player, boolean on) {
        if (on ? modeOn.add(player) : modeOn.remove(player)) setDirty();
    }
}
