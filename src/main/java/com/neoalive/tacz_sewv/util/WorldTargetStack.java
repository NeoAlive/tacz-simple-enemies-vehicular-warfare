package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import com.neoalive.tacz_sewv.entity.ai.targeting.StackEntry;
import com.neoalive.tacz_sewv.entity.ai.targeting.TargetStack;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Per-world, per-faction target priority stack: an ordered Priority list (top first) and an
 * Excluded list. Edited with {@code /sewv targetpriority}. Rows are kept as text, so a row naming a
 * mod that is not installed survives and simply matches nothing.
 */
public class WorldTargetStack extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_target_stack";
    private static final String LEGACY_NAME = "tacz_sewv_target_priority";
    public static final int MAX_ROWS = 256;

    /** One faction's stack. Lists are the stored text rows, in order. */
    public record Stack(List<String> priority, List<String> excluded) {
        public Stack {
            priority = List.copyOf(priority);
            excluded = List.copyOf(excluded);
        }
    }

    /** The shipped Priority order; it carries the old soft-enemy tier order. */
    public static final List<String> DEFAULT_PRIORITY = List.of(
            "@xx_tripod_invasion",
            "cat:organoid", "cat:experiments",
            "group:hard_armour", "group:light_armour", "group:unarmoured_vehicle", "group:emplacements",
            "group:heavy_creature",
            "cat:infected", "@phayriosisreborn",
            "group:troops", "group:players",
            "group:aircraft",
            "cat:monster");

    private final Map<TankFaction, Stack> stacks = new EnumMap<>(TankFaction.class);

    public WorldTargetStack() {
        for (TankFaction f : TankFaction.values()) stacks.put(f, builtInDefault());
    }

    /** Default stack: the shipped order, and every category the old deny list excluded by default. */
    public static Stack builtInDefault() {
        List<String> excluded = new ArrayList<>();
        for (String cat : WorldTargetPriority.builtInExcluded()) excluded.add("cat:" + cat);
        return new Stack(DEFAULT_PRIORITY, excluded);
    }

    /** A stack carrying an old world's deny list into Excluded, with the default order. */
    public static Stack fromLegacy(Set<String> excludedCategories) {
        List<String> excluded = new ArrayList<>();
        for (String cat : excludedCategories) {
            StackEntry e = StackEntry.parse("cat:" + cat);
            if (e != null) excluded.add(e.text());
        }
        return new Stack(DEFAULT_PRIORITY, excluded);
    }

    public static WorldTargetStack load(CompoundTag nbt) {
        WorldTargetStack data = new WorldTargetStack();
        for (TankFaction f : TankFaction.values()) {
            String key = f.name().toLowerCase(Locale.ROOT);
            if (!nbt.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag tag = nbt.getCompound(key);
            data.stacks.put(f, new Stack(read(tag.getList("priority", Tag.TAG_STRING)),
                    read(tag.getList("excluded", Tag.TAG_STRING))));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, net.minecraft.core.HolderLookup.Provider registries) {
        for (TankFaction f : TankFaction.values()) {
            CompoundTag tag = new CompoundTag();
            Stack s = of(f);
            tag.put("priority", write(s.priority()));
            tag.put("excluded", write(s.excluded()));
            nbt.put(f.name().toLowerCase(Locale.ROOT), tag);
        }
        return nbt;
    }

    public static WorldTargetStack get(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ServerLevel overworld = serverLevel.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                var storage = overworld.getDataStorage();
                return storage.computeIfAbsent(new SavedData.Factory<>(() -> migrate(storage), (tag, registries) -> WorldTargetStack.load(tag)), DATA_NAME);
            }
        }
        return new WorldTargetStack();
    }

    /** First load on a world that has the old deny list: its exclusions move into Excluded. */
    private static WorldTargetStack migrate(net.minecraft.world.level.storage.DimensionDataStorage storage) {
        WorldTargetStack data = new WorldTargetStack();
        WorldTargetPriority legacy = storage.get(new SavedData.Factory<>(WorldTargetPriority::new, (tag, registries) -> WorldTargetPriority.load(tag)), LEGACY_NAME);
        if (legacy != null) {
            for (TankFaction f : TankFaction.values()) data.stacks.put(f, fromLegacy(legacy.excludedOf(f)));
            data.setDirty();
        }
        return data;
    }

    public Stack of(TankFaction faction) {
        return stacks.computeIfAbsent(faction, f -> builtInDefault());
    }

    /** Stores cleaned rows and drops the resolver's cache. */
    public void set(TankFaction faction, List<String> priority, List<String> excluded,
                    Predicate<String> isVehicleType) {
        stacks.put(faction, clean(priority, excluded, isVehicleType));
        setDirty();
        TargetStack.invalidate();
    }

    /**
     * Parsed, normalised, de-duplicated (a row in Priority is dropped from Excluded), capped at
     * {@link #MAX_ROWS} each; Excluded keeps only rows that may sit there.
     */
    public static Stack clean(@Nullable List<String> priority, @Nullable List<String> excluded,
                              Predicate<String> isVehicleType) {
        Set<String> p = new LinkedHashSet<>();
        if (priority != null) {
            for (String raw : priority) {
                StackEntry e = StackEntry.parse(raw);
                if (e != null && p.size() < MAX_ROWS) p.add(e.text());
            }
        }
        Set<String> x = new LinkedHashSet<>();
        if (excluded != null) {
            for (String raw : excluded) {
                StackEntry e = StackEntry.parse(raw);
                if (e == null || p.contains(e.text()) || !e.excludable(isVehicleType)) continue;
                if (x.size() < MAX_ROWS) x.add(e.text());
            }
        }
        return new Stack(new ArrayList<>(p), new ArrayList<>(x));
    }

    private static List<String> read(ListTag tags) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            String s = tags.getString(i);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static ListTag write(List<String> rows) {
        ListTag tags = new ListTag();
        for (String s : rows) tags.add(StringTag.valueOf(s));
        return tags;
    }
}
