package com.neoalive.tacz_sewv.loadout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * One loadout entry in SEM's {@code unit_loadouts} JSON shape. Pure data — no Minecraft types — so
 * {@link LoadoutMerge} and its self-check run headless.
 *
 * <p>Keys we do not model are kept verbatim in {@link #extra}: adopting a row written by another
 * mod must not silently drop whatever that mod's parser reads.
 */
public final class LoadoutRow {

    /** Optional id-valued keys (SEM base reads the first three; SEM Extended reads all). */
    public static final List<String> ID_KEYS = List.of(
            "scope_id", "muzzle_id", "grip_id",
            "stock_id", "mag_id", "laser_id",
            "helmet_id", "chest_plate_id", "leggings_id", "boots_id");

    public static final int MAX_WEIGHT = 100;

    private static final Set<String> MODELLED = Set.of("gun_id", "ammo_count", "fire_mode", "weight");

    public String name = "loadout";
    public String gunId = "";
    public int ammo = 30;
    /** AUTO / SEMI (+ BURST, honoured by SEM Extended only). */
    public String fireMode = "SEMI";
    public int weight = 1;
    public final Map<String, String> ids = new LinkedHashMap<>();
    public JsonObject extra = new JsonObject();

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("gun_id", this.gunId);
        o.addProperty("ammo_count", this.ammo);
        o.addProperty("fire_mode", this.fireMode);
        o.addProperty("weight", this.weight);
        for (String key : ID_KEYS) {
            String id = this.ids.get(key);
            if (id != null && !id.isEmpty()) o.addProperty(key, id);
        }
        this.extra.entrySet().forEach(e -> o.add(e.getKey(), e.getValue().deepCopy()));
        return o;
    }

    /** Null when SEM itself would skip the entry (a required field missing or the wrong type). */
    @Nullable
    public static LoadoutRow fromJson(String name, JsonObject o) {
        try {
            if (!o.has("gun_id") || !o.has("ammo_count") || !o.has("fire_mode")) return null;
            LoadoutRow r = new LoadoutRow();
            r.name = name;
            r.gunId = o.get("gun_id").getAsString();
            r.ammo = o.get("ammo_count").getAsInt();
            r.fireMode = o.get("fire_mode").getAsString().toUpperCase(Locale.ROOT);
            r.weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            for (var e : o.entrySet()) {
                String k = e.getKey();
                if (MODELLED.contains(k)) continue;
                if (ID_KEYS.contains(k) && e.getValue().isJsonPrimitive()) {
                    r.ids.put(k, e.getValue().getAsString());
                } else {
                    r.extra.add(k, e.getValue().deepCopy());
                }
            }
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public LoadoutRow copy() {
        LoadoutRow r = new LoadoutRow();
        r.name = this.name;
        r.gunId = this.gunId;
        r.ammo = this.ammo;
        r.fireMode = this.fireMode;
        r.weight = this.weight;
        r.ids.putAll(this.ids);
        r.extra = this.extra.deepCopy();
        return r;
    }
}
