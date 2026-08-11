package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.fml.loading.FMLPaths;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Per-world vehicle spawn pools. Seeded from COMMON config defaults on first create;
 * edited in-game via the pool clipboard / {@code /sewv pool}. COMMON toml is never written.
 */
public class WorldVehiclePools extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_vehicle_pools";
    private static final String LEGACY_COMMON_CONFIG = "tacz_sewv-common.toml";

    private static final List<String> DEFAULT_RU_GROUND = List.of(
            "superbwarfare:t_90a", "superbwarfare:bmp_2");
    private static final List<String> DEFAULT_US_GROUND = List.of(
            "superbwarfare:m_1a_2", "superbwarfare:bradley");
    private static final List<String> DEFAULT_PMC_GROUND = List.of(
            "superbwarfare:t_90a");
    private static final List<String> DEFAULT_RU_HELI = List.of("superbwarfare:mi_28");
    private static final List<String> DEFAULT_US_HELI = List.of("superbwarfare:ah_6");
    private static final List<String> DEFAULT_PMC_HELI = List.of("superbwarfare:ah_6");
    private static final List<String> DEFAULT_SHIPS = List.of("superbwarfare:speedboat");
    private static final List<String> DEFAULT_RU_PLANES = List.of("superbwarfare:kv_16");
    private static final List<String> DEFAULT_US_PLANES = List.of("superbwarfare:a_10a");
    private static final List<String> DEFAULT_PMC_PLANES = List.of("superbwarfare:a_10a");
    /** Fixed AT launchers; kornet/ags resolve only when VVP is loaded. */
    private static final List<String> DEFAULT_TOW = List.of(
            "superbwarfare:tow", "vvp:kornet", "vvp:ags_30");

    private static Map<TankFaction, Map<Category, List<String>>> legacyPools;
    private static boolean legacyPoolsLoaded;

    public enum Category {
        GROUND, SHIP, PLANE, HELI, TOW
    }

    private final Map<TankFaction, Map<Category, List<String>>> pools = new EnumMap<>(TankFaction.class);

    public WorldVehiclePools() {
        seedInitialDefaults();
    }

    public static WorldVehiclePools load(CompoundTag nbt) {
        WorldVehiclePools data = new WorldVehiclePools();
        data.pools.clear();
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            boolean heliFromNbt = nbt.contains(key(faction, Category.HELI), Tag.TAG_LIST);
            for (Category cat : Category.values()) {
                String key = key(faction, cat);
                List<String> list = new ArrayList<>();
                if (nbt.contains(key, Tag.TAG_LIST)) {
                    ListTag tags = nbt.getList(key, Tag.TAG_STRING);
                    for (int i = 0; i < tags.size(); i++) {
                        list.add(tags.getString(i));
                    }
                } else {
                    list.addAll(builtInDefaults(faction, cat));
                }
                byCat.put(cat, list);
            }
            if (!heliFromNbt) {
                migrateHelisFromGround(byCat);
            }
            data.pools.put(faction, byCat);
        }
        data.setDirty(); // persist HELI keys / migration even if nothing else changed
        return data;
    }

    /** Move heli-looking ids out of GROUND into HELI (once, when HELI NBT was absent). */
    private static void migrateHelisFromGround(Map<Category, List<String>> byCat) {
        List<String> ground = byCat.get(Category.GROUND);
        List<String> heli = byCat.computeIfAbsent(Category.HELI, c -> new ArrayList<>());
        if (ground == null) return;
        Iterator<String> it = ground.iterator();
        while (it.hasNext()) {
            String id = it.next();
            if (!looksLikeHeli(id)) continue;
            it.remove();
            if (!heli.contains(id)) heli.add(id);
        }
        if (heli.isEmpty()) {
            // Still empty after strip — keep built-in heli defaults already seeded.
        }
    }

    static boolean looksLikeHeli(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("mi_28") || lower.contains("ah_6") || lower.contains("heli")
                || lower.contains("apache") || lower.contains("ka_52") || lower.contains("ka52")
                || lower.contains("mi_24") || lower.contains("mi_8") || lower.contains("uh_")
                || lower.contains("huey") || lower.contains("v-22") || lower.contains("nh_90")
                || lower.contains("littlebird") || lower.contains("ah-64");
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
                .computeIfAbsent(category, c -> new ArrayList<>(builtInDefaults(faction, c)));
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
        seedBuiltInDefaults();
        setDirty();
    }

    public void resetToDefaults(TankFaction faction, Category category) {
        pools.computeIfAbsent(faction, f -> new EnumMap<>(Category.class))
                .put(category, new ArrayList<>(builtInDefaults(faction, category)));
        setDirty();
    }

    private void seedBuiltInDefaults() {
        pools.clear();
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, new ArrayList<>(builtInDefaults(faction, cat)));
            }
            pools.put(faction, byCat);
        }
    }

    private void seedInitialDefaults() {
        pools.clear();
        for (TankFaction faction : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category cat : Category.values()) {
                byCat.put(cat, new ArrayList<>(initialDefaults(faction, cat)));
            }
            // Legacy GROUND may still list helis — peel them into HELI.
            migrateHelisFromGround(byCat);
            pools.put(faction, byCat);
        }
    }

    private static String key(TankFaction faction, Category category) {
        return faction.name().toLowerCase() + "_" + category.name().toLowerCase();
    }

    public static List<String> builtInDefaults(TankFaction faction, Category category) {
        return switch (category) {
            case GROUND -> switch (faction) {
                case RU -> DEFAULT_RU_GROUND;
                case US -> DEFAULT_US_GROUND;
                case PMC -> DEFAULT_PMC_GROUND;
            };
            case HELI -> switch (faction) {
                case RU -> DEFAULT_RU_HELI;
                case US -> DEFAULT_US_HELI;
                case PMC -> DEFAULT_PMC_HELI;
            };
            case SHIP -> switch (faction) {
                case RU, US, PMC -> DEFAULT_SHIPS;
            };
            case PLANE -> switch (faction) {
                case RU -> DEFAULT_RU_PLANES;
                case US -> DEFAULT_US_PLANES;
                case PMC -> DEFAULT_PMC_PLANES;
            };
            case TOW -> DEFAULT_TOW;
        };
    }

    private static List<String> initialDefaults(TankFaction faction, Category category) {
        List<String> legacy = legacyDefaults(faction, category);
        return legacy != null ? legacy : builtInDefaults(faction, category);
    }

    private static List<String> legacyDefaults(TankFaction faction, Category category) {
        loadLegacyPoolsOnce();
        if (legacyPools == null) return null;
        Map<Category, List<String>> byCategory = legacyPools.get(faction);
        if (byCategory == null) return null;
        List<String> ids = byCategory.get(category);
        return ids == null || ids.isEmpty() ? null : ids;
    }

    private static void loadLegacyPoolsOnce() {
        if (legacyPoolsLoaded) return;
        legacyPoolsLoaded = true;
        var path = FMLPaths.CONFIGDIR.get().resolve(LEGACY_COMMON_CONFIG);
        if (!java.nio.file.Files.isRegularFile(path)) return;

        try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
            config.load();
            Map<TankFaction, Map<Category, List<String>>> loaded = new EnumMap<>(TankFaction.class);
            loadLegacy(loaded, TankFaction.RU, Category.GROUND, config, "vehicle_pools.ruVehiclePool");
            loadLegacy(loaded, TankFaction.US, Category.GROUND, config, "vehicle_pools.usVehiclePool");
            loadLegacy(loaded, TankFaction.PMC, Category.GROUND, config, "vehicle_pools.pmcVehiclePool");
            loadLegacy(loaded, TankFaction.RU, Category.SHIP, config, "vehicle_pools.ruShipPool");
            loadLegacy(loaded, TankFaction.US, Category.SHIP, config, "vehicle_pools.usShipPool");
            loadLegacy(loaded, TankFaction.PMC, Category.SHIP, config, "vehicle_pools.pmcShipPool");
            loadLegacy(loaded, TankFaction.RU, Category.PLANE, config, "vehicle_pools.ruPlanePool");
            loadLegacy(loaded, TankFaction.US, Category.PLANE, config, "vehicle_pools.usPlanePool");
            loadLegacy(loaded, TankFaction.PMC, Category.PLANE, config, "vehicle_pools.pmcPlanePool");
            loadLegacy(loaded, TankFaction.RU, Category.HELI, config, "vehicle_pools.ruHeliPool");
            loadLegacy(loaded, TankFaction.US, Category.HELI, config, "vehicle_pools.usHeliPool");
            loadLegacy(loaded, TankFaction.PMC, Category.HELI, config, "vehicle_pools.pmcHeliPool");
            legacyPools = loaded.isEmpty() ? null : loaded;
        } catch (RuntimeException ignored) {
            legacyPools = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadLegacy(Map<TankFaction, Map<Category, List<String>>> loaded, TankFaction faction,
                                   Category category, CommentedFileConfig config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> list)) return;
        List<String> ids = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String id && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return;
        loaded.computeIfAbsent(faction, f -> new EnumMap<>(Category.class)).put(category, ids);
    }
}
