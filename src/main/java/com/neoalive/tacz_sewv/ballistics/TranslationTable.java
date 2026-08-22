package com.neoalive.tacz_sewv.ballistics;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.ballistics.BallisticClassifier.Category;

/**
 * Per-category factor that rescales a classified TaCZ bullet hit onto SBW's own damage scale.
 * A datapack file (default shipped at {@code data/tacz_sewv/sewv/ballistics/translation.json}),
 * loaded and swapped wholesale exactly like {@link com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights}:
 * a file that names a category replaces that category's row entirely, unknown categories/keys warn
 * and are skipped, and {@link #fallback()} is a deliberately minimal identity passthrough rather
 * than a second copy of the shipped anchors.
 */
public final class TranslationTable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile TranslationTable active = fallback();

    public static TranslationTable active() {
        return active;
    }

    private final Map<Category, CategoryFactors> categories;

    private TranslationTable(Map<Category, CategoryFactors> categories) {
        this.categories = categories;
    }

    /**
     * Scale factor for the direct-hit channel of one classified hit ({@code tacz:bullet}/
     * {@code tacz:bullet_ignore_armor} — the kinetic component every category has). {@code
     * armorIgnoreHalf} picks {@code penRemapFactor} over the base factor when the category defines
     * one (identity — same factor for both halves — by default); {@code engineType} then applies
     * that category's per-hull-class multiplier if the pack defined one for it. Identity (1.0) for
     * an unconfigured category or a null engine type.
     */
    public double factorFor(Category category, boolean armorIgnoreHalf, EngineType engineType) {
        CategoryFactors row = categories.get(category);
        return row == null ? 1.0 : row.factorFor(armorIgnoreHalf, engineType);
    }

    /**
     * Scale factor for the AoE-explosion channel — only reachable for {@link Category#EXPLOSIVE}:
     * TaCZ's own {@code ExplodeUtil} fires a SECOND, separate {@code hurt()} call for an exploding
     * bullet's blast damage, carrying vanilla {@code minecraft:explosion}/{@code player_explosion}
     * rather than any {@code tacz:bullet*} type — a completely untagged channel {@link #factorFor}
     * never sees, and (measured against SBW's own RPG: 340 direct / 80 AoE vs a TaCZ RPG-7's 20
     * direct / 120 AoE) usually the LARGER share of an explosive round's total damage. Same
     * engine-type override grid as the direct-hit channel; no armor-ignore split exists for this
     * channel (TaCZ never splits AoE damage the way it splits a direct hit), so there is no
     * pen-remap equivalent here.
     */
    public double aoeFactorFor(Category category, EngineType engineType) {
        CategoryFactors row = categories.get(category);
        return row == null ? 1.0 : row.aoeFactorFor(engineType);
    }

    private record CategoryFactors(double factor, Double penRemapFactor, double aoeFactor,
                                   Map<EngineType, Double> engineTypeOverrides) {
        double factorFor(boolean armorIgnoreHalf, EngineType engineType) {
            double base = armorIgnoreHalf && penRemapFactor != null ? penRemapFactor : factor;
            return withEngineOverride(base, engineType);
        }

        double aoeFactorFor(EngineType engineType) {
            return withEngineOverride(aoeFactor, engineType);
        }

        private double withEngineOverride(double base, EngineType engineType) {
            Double override = engineType == null ? null : engineTypeOverrides.get(engineType);
            return override != null ? base * override : base;
        }
    }

    /**
     * The safety net when the datapack file is missing or unreadable: identity passthrough for
     * every category, so a bullet keeps whatever the raw {@code tacz:bullet}/
     * {@code tacz:bullet_ignore_armor} amount was — same as if this feature did not exist.
     * Deliberately not a copy of the shipped anchors (see {@code UtilityWeights.fallback()}).
     */
    public static TranslationTable fallback() {
        Map<Category, CategoryFactors> categories = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            categories.put(c, identityRow());
        }
        return new TranslationTable(categories);
    }

    private static CategoryFactors identityRow() {
        return new CategoryFactors(1.0, null, 1.0, Map.of());
    }

    public static TranslationTable parse(Map<ResourceLocation, JsonElement> files) {
        Map<Category, CategoryFactors> categories = new EnumMap<>(Category.class);
        int applied = 0;

        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(file.getValue(), "translation");
            } catch (RuntimeException e) {
                LOGGER.error("[sewv] Ballistic translation {} is not a JSON object - skipped: {}",
                        file.getKey(), e.getMessage());
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Category category = byKey(entry.getKey());
                if (category == null) {
                    LOGGER.warn("[sewv] Ballistic translation {}: no such category '{}' - ignored",
                            file.getKey(), entry.getKey());
                    continue;
                }
                CategoryFactors row = readCategory(file.getKey(), entry.getKey(), entry.getValue());
                if (row == null) continue;
                categories.put(category, row);
                applied++;
            }
        }

        if (applied == 0) {
            LOGGER.error("[sewv] No usable ballistic translation entries found - "
                    + "falling back to identity passthrough (TaCZ bullets stay on TaCZ's own scale).");
            return fallback();
        }
        // A category no loaded file named keeps identity, same as a row UtilityWeights never saw.
        for (Category c : Category.values()) {
            categories.putIfAbsent(c, identityRow());
        }
        LOGGER.info("[sewv] Loaded {} ballistic translation categories from {} file(s)",
                applied, files.size());
        return new TranslationTable(categories);
    }

    private static CategoryFactors readCategory(ResourceLocation source, String key, JsonElement element) {
        JsonObject obj;
        try {
            obj = GsonHelper.convertToJsonObject(element, key);
        } catch (RuntimeException e) {
            LOGGER.error("[sewv] Ballistic translation {}: '{}' is not an object - skipped", source, key);
            return null;
        }

        double taczBaseline = readDouble(obj, "taczBaselineDamage", 0.0);
        double sbwReference = readDouble(obj, "sbwReferenceDamage", 0.0);
        double factor = taczBaseline > 0.0
                ? Mth.clamp(sbwReference / taczBaseline, 0.0, 100.0)
                : 1.0;

        Double penRemap = null;
        if (obj.has("penRemapFactor")) {
            penRemap = Mth.clamp(readDouble(obj, "penRemapFactor", 1.0), 0.0, 100.0);
        }

        // Only meaningful for EXPLOSIVE (see aoeFactorFor's javadoc), but read for every category
        // uniformly - an unset pair is identity (1.0), same as the direct-hit factor's own zero-
        // baseline fallback.
        double taczAoeBaseline = readDouble(obj, "taczAoeBaselineDamage", 0.0);
        double sbwAoeReference = readDouble(obj, "sbwAoeReferenceDamage", 0.0);
        double aoeFactor = taczAoeBaseline > 0.0
                ? Mth.clamp(sbwAoeReference / taczAoeBaseline, 0.0, 100.0)
                : 1.0;

        Map<EngineType, Double> overrides = Map.of();
        if (obj.has("engineTypeOverrides")) {
            overrides = readEngineOverrides(source, key, obj.get("engineTypeOverrides"));
        }

        return new CategoryFactors(factor, penRemap, aoeFactor, overrides);
    }

    private static Map<EngineType, Double> readEngineOverrides(ResourceLocation source, String categoryKey,
                                                                JsonElement element) {
        JsonObject obj;
        try {
            obj = GsonHelper.convertToJsonObject(element, "engineTypeOverrides");
        } catch (RuntimeException e) {
            LOGGER.warn("[sewv] Ballistic translation {}: '{}.engineTypeOverrides' is not an object - ignored",
                    source, categoryKey);
            return Map.of();
        }
        Map<EngineType, Double> overrides = new EnumMap<>(EngineType.class);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            EngineType type = byEngineKey(entry.getKey());
            if (type == null) {
                LOGGER.warn("[sewv] Ballistic translation {}: '{}.engineTypeOverrides.{}' is not an engine type - ignored",
                        source, categoryKey, entry.getKey());
                continue;
            }
            try {
                overrides.put(type, Mth.clamp(GsonHelper.convertToDouble(entry.getValue(), entry.getKey()), 0.0, 100.0));
            } catch (RuntimeException e) {
                LOGGER.warn("[sewv] Ballistic translation {}: '{}.engineTypeOverrides.{}' is not a number - ignored",
                        source, categoryKey, entry.getKey());
            }
        }
        return overrides;
    }

    private static double readDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key)) return fallback;
        try {
            return GsonHelper.convertToDouble(obj.get(key), key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Category byKey(String key) {
        for (Category c : Category.values()) {
            if (c.name().equalsIgnoreCase(key)) return c;
        }
        return null;
    }

    private static EngineType byEngineKey(String key) {
        for (EngineType t : EngineType.values()) {
            if (t.name().equalsIgnoreCase(key)) return t;
        }
        return null;
    }

    /**
     * Loads {@code data/<namespace>/sewv/ballistics/*.json} and installs the result. Registered on
     * {@code AddReloadListenerEvent} in {@link com.neoalive.tacz_sewv.TaczSewv}, so the table follows
     * the server's datapacks and {@code /reload} re-reads it without a restart.
     */
    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/ballistics");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
            // TaCZ's own gun index is rebuilt on the same reload - drop any cached BulletFacts so
            // they don't keep answering with pre-reload numbers.
            BulletFacts.clearCache();
        }
    }
}
