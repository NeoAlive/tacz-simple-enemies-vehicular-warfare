package com.neoalive.tacz_sewv.territory;

import java.util.HashSet;
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

    public static TerritoryData load(CompoundTag nbt) {
        TerritoryData data = new TerritoryData();
        ListTag list = nbt.getList("modeOn", Tag.TAG_INT_ARRAY);
        for (Tag t : list) data.modeOn.add(NbtUtils.loadUUID(t));
        for (Tag t : nbt.getList("seenClaims", Tag.TAG_INT_ARRAY)) data.seenClaims.add(NbtUtils.loadUUID(t));
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
