package com.neoalive.tacz_sewv.crew;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Crew radio language per PMC owner, chosen in the TDT. Keyed by owner UUID so a hull keeps its
 * voice while its player is offline.
 */
public final class VoicePreference extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_voice_preferences";

    private final Map<UUID, String> voices = new HashMap<>();

    public static VoicePreference load(CompoundTag nbt) {
        VoicePreference data = new VoicePreference();
        ListTag list = nbt.getList("voices", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String key = tag.getString("voice");
            if (PmcVoices.valid(key)) data.voices.put(tag.getUUID("uuid"), key);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> e : voices.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("uuid", e.getKey());
            tag.putString("voice", e.getValue());
            list.add(tag);
        }
        nbt.put("voices", list);
        return nbt;
    }

    @Nullable
    private static VoicePreference store(@Nullable MinecraftServer server) {
        if (server == null) return null;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return overworld.getDataStorage().computeIfAbsent(VoicePreference::load, VoicePreference::new, DATA_NAME);
    }

    public static void set(Player player, String key) {
        VoicePreference data = store(player.getServer());
        if (data == null) return;
        key = PmcVoices.sanitize(key);
        if (PmcVoices.DEFAULT.equals(key)) data.voices.remove(player.getUUID());
        else data.voices.put(player.getUUID(), key);
        data.setDirty();
    }

    public static String get(Player player) {
        return get(player.getServer(), player.getUUID());
    }

    public static String get(@Nullable MinecraftServer server, @Nullable UUID owner) {
        if (owner == null) return PmcVoices.DEFAULT;
        VoicePreference data = store(server);
        return data == null ? PmcVoices.DEFAULT : data.voices.getOrDefault(owner, PmcVoices.DEFAULT);
    }
}
