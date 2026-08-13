package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Per-world, per-faction MobCategory exclude lists. Edited via {@code /sewv targetPriority}.
 * Defaults match the old {@code instanceof Enemy} gate: {@code monster} allowed, everything else
 * excluded. Category names are strings so Forge-extended enum values survive unknown-on-load.
 */
public class WorldTargetPriority extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_target_priority";
    private static final String ALLOWED_DEFAULT = "monster";

    private static volatile List<String> CATALOG = List.of();

    private final Map<TankFaction, Set<String>> excluded = new EnumMap<>(TankFaction.class);

    public WorldTargetPriority() {
        seedDefaults();
    }

    public static void refreshCatalog() {
        List<String> names = new ArrayList<>();
        for (MobCategory cat : MobCategory.values()) {
            String name = cat.getName();
            if (name == null || name.isBlank()) continue;
            if (!names.contains(name)) names.add(name);
        }
        CATALOG = List.copyOf(names);
    }

    /** Vanilla + Forge-extended {@link MobCategory} names, cached on world load. */
    public static List<String> catalog() {
        if (CATALOG.isEmpty()) refreshCatalog();
        return CATALOG;
    }

    public static Set<String> builtInExcluded() {
        Set<String> out = new LinkedHashSet<>();
        for (String name : catalog()) {
            if (!ALLOWED_DEFAULT.equals(name)) out.add(name);
        }
        return out;
    }

    public static WorldTargetPriority load(CompoundTag nbt) {
        WorldTargetPriority data = new WorldTargetPriority();
        data.excluded.clear();
        for (TankFaction faction : TankFaction.values()) {
            String key = key(faction);
            if (nbt.contains(key, Tag.TAG_LIST)) {
                data.excluded.put(faction, readSet(nbt.getList(key, Tag.TAG_STRING)));
            } else {
                data.excluded.put(faction, builtInExcluded());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        for (TankFaction faction : TankFaction.values()) {
            nbt.put(key(faction), writeSet(excludedOf(faction)));
        }
        return nbt;
    }

    public static WorldTargetPriority get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.getDataStorage().computeIfAbsent(
                        WorldTargetPriority::load,
                        WorldTargetPriority::new,
                        DATA_NAME);
            }
        }
        return new WorldTargetPriority();
    }

    public Set<String> excludedOf(TankFaction faction) {
        return excluded.computeIfAbsent(faction, f -> builtInExcluded());
    }

    public boolean isExcluded(TankFaction faction, String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return true;
        return excludedOf(faction).contains(categoryName);
    }

    public void setExcluded(TankFaction faction, Set<String> names) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) cleaned.add(name);
            }
        }
        excluded.put(faction, cleaned);
        setDirty();
    }

    public void resetDefaults() {
        for (TankFaction faction : TankFaction.values()) {
            excluded.put(faction, builtInExcluded());
        }
        setDirty();
    }

    private void seedDefaults() {
        for (TankFaction faction : TankFaction.values()) {
            excluded.put(faction, builtInExcluded());
        }
    }

    private static String key(TankFaction faction) {
        return "excluded_" + faction.name().toLowerCase(Locale.ROOT);
    }

    private static Set<String> readSet(ListTag tags) {
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            String s = tags.getString(i);
            if (!s.isBlank()) set.add(s);
        }
        return set;
    }

    private static ListTag writeSet(Set<String> names) {
        ListTag tags = new ListTag();
        for (String name : names) tags.add(StringTag.valueOf(name));
        return tags;
    }
}
