package com.neoalive.tacz_sewv.crew;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * A player's preferred {@link NamePools} category for their own future PMC recruits.
 *
 * <p>Server-authoritative, keyed by player UUID, backed by a world {@link SavedData} — same
 * shape as {@link com.neoalive.tacz_sewv.entity.ai.utility.PlayerDoctrineData} — rather than
 * {@code Player.getPersistentData()}: that per-entity-instance NBT does <b>not</b> survive a
 * player's death/respawn (a fresh {@code ServerPlayer} instance) without an explicit
 * {@code PlayerEvent.Clone} copy, which reads as exactly "the preference isn't persistent."
 *
 * <p>Set from the TDT's Identity/"Full Names" control ({@code PacketSetNameCategory}); read by
 * {@link NpcIdentity#issue} whenever a freshly spawned PMC unit already has an owner.
 */
public class NamePreference extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_name_preferences";

    private final Map<UUID, String> preferences = new HashMap<>();

    public NamePreference() {
    }

    public static NamePreference load(CompoundTag nbt) {
        NamePreference data = new NamePreference();
        if (nbt.contains("preferences", Tag.TAG_LIST)) {
            ListTag list = nbt.getList("preferences", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                data.preferences.put(tag.getUUID("uuid"), tag.getString("category"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> entry : preferences.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", entry.getKey());
            tag.putString("category", entry.getValue());
            list.add(tag);
        }
        nbt.put("preferences", list);
        return nbt;
    }

    private static NamePreference store(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        NamePreference::load, NamePreference::new, DATA_NAME);
            }
        }
        return new NamePreference(); // dummy fallback for client/missing overworld
    }

    public static void set(Player player, String category) {
        NamePreference data = store(player.level());
        data.preferences.put(player.getUUID(), category);
        data.setDirty();
    }

    public static String get(Player player, String fallback) {
        return store(player.level()).preferences.getOrDefault(player.getUUID(), fallback);
    }
}
