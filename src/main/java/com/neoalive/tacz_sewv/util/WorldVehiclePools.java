package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world vehicle spawn pools. Seeded from COMMON config defaults on first create;
 * edited in-game via the pool clipboard / {@code /sewv pool}. COMMON toml is never written.
 */
public class WorldVehiclePools extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_vehicle_pools";

    public enum Category {
        GROUND, SHIP, PLANE
    }

    private final Map<TankFaction, Map<Category, List<String>>> pools = new EnumMap<>(TankFaction.class);

    public WorldVehiclePools() {
        seedFromConfig();
    }

    public static WorldVehiclePools load(CompoundTag nbt) {
        WorldVehiclePools data = new WorldVehiclePools();
        data.pools.clear();
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                String key = key(faction, cat);
                List<String> list = new ArrayList<>();
                if (nbt.contains(key, Tag.TAG_LIST)) {
                    ListTag tags = nbt.getList(key, Tag.TAG_STRING);
                    for (int i = 0; i < tags.size(); i++) {
                        list.add(tags.getString(i));
                    }
                } else {
                    list.addAll(configDefaults(faction, cat));
                }
                byCat.put(cat, list);
            }
            data.pools.put(faction, byCat);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        for (TankFaction faction : TankFaction.values()) {
            for (Category cat : Category.values()) {
                ListTag tags = new ListTag();
                for (String id : list(faction, cat)) {
                    tags.add(StringTag.valueOf(id));
                }
                nbt.put(key(faction, cat), tags);
            }
        }
        return nbt;
    }

    public static WorldVehiclePools get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        WorldVehiclePools::load,
                        WorldVehiclePools::new,
                        DATA_NAME
                );
            }
        }
        // Client / missing overworld: ephemeral defaults, never persisted.
        return new WorldVehiclePools();
    }

    public List<String> list(TankFaction faction, Category category) {
        return pools.computeIfAbsent(faction, f -> new EnumMap<>(Category.class))
                .computeIfAbsent(category, c -> new ArrayList<>(configDefaults(faction, c)));
    }

    public void set(TankFaction faction, Category category, List<String> ids) {
        List<String> copy = new ArrayList<>(ids);
        pools.computeIfAbsent(faction, f -> new EnumMap<>(Category.class)).put(category, copy);
        setDirty();
    }

    public boolean add(TankFaction faction, Category category, String id) {
        List<String> list = list(faction, category);
        if (list.contains(id)) return false;
        list.add(id);
        setDirty();
        return true;
    }

    public boolean remove(TankFaction faction, Category category, String id) {
        List<String> list = list(faction, category);
        if (!list.remove(id)) return false;
        setDirty();
        return true;
    }

    public void resetToDefaults() {
        seedFromConfig();
        setDirty();
    }

    public void resetToDefaults(TankFaction faction, Category category) {
        pools.computeIfAbsent(faction, f -> new EnumMap<>(Category.class))
                .put(category, new ArrayList<>(configDefaults(faction, category)));
        setDirty();
    }

    private void seedFromConfig() {
        pools.clear();
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, new ArrayList<>(configDefaults(faction, cat)));
            }
            pools.put(faction, byCat);
        }
    }

    private static String key(TankFaction faction, Category category) {
        return faction.name().toLowerCase() + "_" + category.name().toLowerCase();
    }

    public static List<? extends String> configDefaults(TankFaction faction, Category category) {
        return switch (category) {
            case GROUND -> switch (faction) {
                case RU -> SewvConfig.RU_VEHICLE_POOL.get();
                case US -> SewvConfig.US_VEHICLE_POOL.get();
                case PMC -> SewvConfig.PMC_VEHICLE_POOL.get();
            };
            case SHIP -> switch (faction) {
                case RU -> SewvConfig.RU_SHIP_POOL.get();
                case US -> SewvConfig.US_SHIP_POOL.get();
                case PMC -> SewvConfig.PMC_SHIP_POOL.get();
            };
            case PLANE -> switch (faction) {
                case RU -> SewvConfig.RU_PLANE_POOL.get();
                case US -> SewvConfig.US_PLANE_POOL.get();
                case PMC -> SewvConfig.PMC_PLANE_POOL.get();
            };
        };
    }
}
