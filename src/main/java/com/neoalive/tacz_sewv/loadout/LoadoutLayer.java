package com.neoalive.tacz_sewv.loadout;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Per-world loadout layer (one {@link LoadoutMerge.Faction} per SEM faction folder). Persisted like
 * {@code WorldVehiclePools}; one JSON string per faction keeps the row codec in one place
 * ({@link LoadoutRow#toJson}). Nothing here is written to any datapack or third-party config.
 */
public class LoadoutLayer extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_loadouts";
    private static final Gson GSON = new Gson();

    private final Map<String, LoadoutMerge.Faction> factions = new HashMap<>();

    public static String folderOf(TankFaction faction) {
        return faction.name().toLowerCase(java.util.Locale.ROOT) + "_units";
    }

    /** Overworld data storage, or null before the overworld exists (server start-up). */
    @Nullable
    public static LoadoutLayer get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return null;
        return overworld.getDataStorage().computeIfAbsent(LoadoutLayer::load, LoadoutLayer::new, DATA_NAME);
    }

    public static LoadoutLayer load(CompoundTag nbt) {
        LoadoutLayer data = new LoadoutLayer();
        for (TankFaction faction : TankFaction.values()) {
            String folder = folderOf(faction);
            if (!nbt.contains(folder, Tag.TAG_STRING)) continue;
            try {
                data.factions.put(folder, decode(JsonParser.parseString(nbt.getString(folder)).getAsJsonObject()));
            } catch (RuntimeException ignored) {
                // A corrupt entry must not take the world down — that faction just reverts to unmanaged.
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        this.factions.forEach((folder, f) -> nbt.putString(folder, GSON.toJson(encode(f))));
        return nbt;
    }

    /** A copy — callers edit freely and hand it back through {@link #set}. */
    public LoadoutMerge.Faction faction(String folder) {
        LoadoutMerge.Faction f = this.factions.get(folder);
        return f == null ? new LoadoutMerge.Faction() : f.copy();
    }

    public void set(String folder, LoadoutMerge.Faction f) {
        this.factions.put(folder, f.copy());
        setDirty();
    }

    public Map<String, LoadoutMerge.Faction> snapshot() {
        Map<String, LoadoutMerge.Faction> out = new HashMap<>();
        this.factions.forEach((k, v) -> out.put(k, v.copy()));
        return out;
    }

    private static JsonObject encode(LoadoutMerge.Faction f) {
        JsonObject o = new JsonObject();
        o.addProperty("managed", f.managed);
        o.addProperty("replace", f.replace);
        JsonArray hidden = new JsonArray();
        f.hidden.forEach(hidden::add);
        o.add("hidden", hidden);
        JsonArray rows = new JsonArray();
        for (LoadoutRow r : f.rows) {
            JsonObject row = new JsonObject();
            row.addProperty("name", r.name);
            row.add("row", r.toJson());
            rows.add(row);
        }
        o.add("rows", rows);
        return o;
    }

    private static LoadoutMerge.Faction decode(JsonObject o) {
        LoadoutMerge.Faction f = new LoadoutMerge.Faction();
        f.managed = o.has("managed") && o.get("managed").getAsBoolean();
        f.replace = o.has("replace") && o.get("replace").getAsBoolean();
        if (o.has("hidden")) o.getAsJsonArray("hidden").forEach(e -> f.hidden.add(e.getAsString()));
        if (o.has("rows")) {
            for (JsonElement e : o.getAsJsonArray("rows")) {
                JsonObject row = e.getAsJsonObject();
                LoadoutRow r = LoadoutRow.fromJson(row.get("name").getAsString(), row.getAsJsonObject("row"));
                if (r != null) f.rows.add(r);
            }
        }
        return f;
    }
}
