package com.neoalive.tacz_sewv.crew;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.util.RandomUtil;

/**
 * PMC name/surname pools, grouped into pack-configurable categories (e.g. CHINESE / ENGLISH /
 * RUSSIAN). A datapack file (default shipped at {@code data/tacz_sewv/sewv/names/pools.json}),
 * loaded and swapped wholesale exactly like {@link com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights}
 * and {@link com.neoalive.tacz_sewv.ballistics.TranslationTable}: a file naming a category
 * replaces that category's NAME/SURNAME lists entirely, unknown/malformed entries warn and are
 * skipped, and {@link #fallback()} is a deliberately minimal safety net rather than a copy of the
 * shipped presets.
 *
 * <p>Unlike those two, categories here are not a fixed enum — pack makers can add or remove
 * cultures freely, so they are plain (upper-cased) string keys.
 */
public final class NamePools {

    /** Sentinel category: draws name and surname independently from every category's pooled
     * union, so a roll can cross cultures (e.g. a Chinese given name with an English surname). */
    public static final String RANDOM = "RANDOM";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile NamePools active = fallback();

    public static NamePools active() {
        return active;
    }

    public record RolledIdentity(String name, String surname, String category) {
    }

    /** Draw-without-replacement bag over a fixed string pool; reshuffles via {@link RandomUtil}
     * once exhausted, same idea as {@link com.neoalive.tacz_sewv.init.ModSounds.SoundPool}. */
    private static final class ShuffleBag {
        private final List<String> pool;
        private int cursor;

        ShuffleBag(List<String> pool) {
            this.pool = new ArrayList<>(pool);
            this.cursor = this.pool.size(); // forces a shuffle before the first draw
        }

        String draw(RandomSource random) {
            if (cursor >= pool.size()) {
                RandomUtil.shuffle(pool, random);
                cursor = 0;
            }
            return pool.get(cursor++);
        }
    }

    private record NamePool(ShuffleBag names, ShuffleBag surnames) {
    }

    private final Map<String, NamePool> categories;
    private final ShuffleBag allNames;
    private final ShuffleBag allSurnames;

    private NamePools(Map<String, NamePool> categories) {
        this.categories = categories;
        List<String> names = new ArrayList<>();
        List<String> surnames = new ArrayList<>();
        for (NamePool pool : categories.values()) {
            names.addAll(pool.names.pool);
            surnames.addAll(pool.surnames.pool);
        }
        this.allNames = new ShuffleBag(names);
        this.allSurnames = new ShuffleBag(surnames);
    }

    /** Sorted category keys, for the TDT cycle list and preference validation. Does not include
     * the {@link #RANDOM} sentinel. */
    public List<String> categoryKeys() {
        return new ArrayList<>(new TreeMap<>(categories).keySet());
    }

    /**
     * Rolls a name+surname. {@link #RANDOM} (case-insensitive) draws from the pooled union of
     * every category; any other key looks up that category's own bags. An unresolvable key
     * (e.g. a stale player preference after a {@code /reload} removed a category) falls back to
     * {@link #RANDOM} behavior rather than erroring.
     */
    public RolledIdentity roll(RandomSource random, String categoryOrRandom) {
        if (categoryOrRandom == null || RANDOM.equalsIgnoreCase(categoryOrRandom)) {
            return new RolledIdentity(allNames.draw(random), allSurnames.draw(random), RANDOM);
        }
        String key = categoryOrRandom.toUpperCase(Locale.ROOT);
        NamePool pool = categories.get(key);
        if (pool == null) {
            return new RolledIdentity(allNames.draw(random), allSurnames.draw(random), RANDOM);
        }
        return new RolledIdentity(pool.names.draw(random), pool.surnames.draw(random), key);
    }

    /**
     * The safety net when the datapack file is missing or unreadable: one minimal placeholder
     * category, just enough that {@link #roll} never has an empty pool to draw from. Deliberately
     * not a copy of the shipped presets (see {@code UtilityWeights.fallback()}).
     */
    public static NamePools fallback() {
        Map<String, NamePool> categories = new TreeMap<>();
        categories.put("DEFAULT", new NamePool(
                new ShuffleBag(List.of("Alex", "Sam")),
                new ShuffleBag(List.of("Carter", "Reyes"))));
        return new NamePools(categories);
    }

    public static NamePools parse(Map<ResourceLocation, JsonElement> files) {
        Map<String, NamePool> categories = new TreeMap<>();
        int applied = 0;

        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(file.getValue(), "name pool");
            } catch (RuntimeException e) {
                LOGGER.error("[sewv] Name pool {} is not a JSON object - skipped: {}",
                        file.getKey(), e.getMessage());
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey().toUpperCase(Locale.ROOT);
                if (RANDOM.equals(key)) {
                    LOGGER.warn("[sewv] Name pool {}: category 'RANDOM' is reserved - ignored", file.getKey());
                    continue;
                }
                NamePool pool = readCategory(file.getKey(), entry.getKey(), entry.getValue());
                if (pool == null) continue;
                categories.put(key, pool);
                applied++;
            }
        }

        if (applied == 0) {
            LOGGER.error("[sewv] No usable name pool categories found - falling back to a placeholder pool.");
            return fallback();
        }
        LOGGER.info("[sewv] Loaded {} name pool categories from {} file(s)", applied, files.size());
        return new NamePools(categories);
    }

    private static NamePool readCategory(ResourceLocation source, String key, JsonElement element) {
        JsonObject obj;
        try {
            obj = GsonHelper.convertToJsonObject(element, key);
        } catch (RuntimeException e) {
            LOGGER.error("[sewv] Name pool {}: '{}' is not an object - skipped", source, key);
            return null;
        }
        List<String> names = readStringArray(source, key, obj, "NAME");
        List<String> surnames = readStringArray(source, key, obj, "SURNAME");
        if (names.isEmpty() || surnames.isEmpty()) {
            LOGGER.warn("[sewv] Name pool {}: '{}' has an empty NAME or SURNAME list - skipped", source, key);
            return null;
        }
        return new NamePool(new ShuffleBag(names), new ShuffleBag(surnames));
    }

    private static List<String> readStringArray(ResourceLocation source, String category, JsonObject obj, String field) {
        List<String> out = new ArrayList<>();
        if (!obj.has(field)) return out;
        try {
            for (JsonElement e : GsonHelper.convertToJsonArray(obj.get(field), field)) {
                out.add(e.getAsString());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[sewv] Name pool {}: '{}.{}' is not a string array - ignored", source, category, field);
        }
        return out;
    }

    /**
     * Loads {@code data/<namespace>/sewv/names/*.json} and installs the result. Registered on
     * {@code AddReloadListenerEvent} in {@link com.neoalive.tacz_sewv.TaczSewv}, so the pools
     * follow the server's datapacks and {@code /reload} re-reads them without a restart.
     */
    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/names");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
        }
    }
}
