package com.neoalive.tacz_sewv.entity.ai.suppression;

import java.util.EnumMap;
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
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * How hard each kind of incoming fire suppresses SEM infantry, and over what radius. A datapack file
 * (shipped at {@code data/tacz_sewv/sewv/suppression/suppression.json}), loaded and swapped wholesale
 * like {@link com.neoalive.tacz_sewv.ballistics.TranslationTable}: a file that names a kind replaces
 * that kind's row, unknown kinds warn and are skipped, and {@link #fallback()} suppresses nothing.
 *
 * <p>Its own directory, not {@code sewv/ai}: {@code UtilityWeights.Loader} parses every file there as
 * weights.
 *
 * <p>The amount is what one emission hands SEM's {@code addGenericSuppression}, which caps a single
 * add at 0.35 and debounces per source itself — so a full-auto stream from one gunner is not a
 * multiplier on this number. {@link Kind#EXPLOSION}'s radius is a multiple of the blast radius, capped
 * at 2: the detonation event only lists entities within twice the blast radius.
 */
public final class SuppressionTable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile SuppressionTable active = fallback();

    /** Largest single add SEM accepts; anything above is clamped by SEM anyway. */
    public static final float MAX_AMOUNT = 0.35f;
    /** Detonate's affected-entity list reaches twice the blast radius and no further. */
    public static final double MAX_EXPLOSION_RADIUS_MULT = 2.0;

    public enum Kind {
        /** A player's TaCZ round passing close by (SEM already emits for its own rifles and for impacts). */
        SMALL_ARMS,
        /** A vehicle machine gun / autocannon, aimed at a target. */
        MG,
        /** A vehicle main gun, aimed at a target. */
        CANNON,
        /** Any detonation: SBW shells, rockets, mortars, bombs, vanilla TNT. */
        EXPLOSION
    }

    public record Row(float amount, double radius) {}

    public static SuppressionTable active() {
        return active;
    }

    private final Map<Kind, Row> rows;

    private SuppressionTable(Map<Kind, Row> rows) {
        this.rows = rows;
    }

    public Row row(Kind kind) {
        return rows.get(kind);
    }

    /** The safety net: every kind suppresses nothing, same as if this feature did not exist. */
    public static SuppressionTable fallback() {
        Map<Kind, Row> rows = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) rows.put(k, new Row(0.0f, 0.0));
        return new SuppressionTable(rows);
    }

    public static SuppressionTable parse(Map<ResourceLocation, JsonElement> files) {
        Map<Kind, Row> rows = new EnumMap<>(Kind.class);
        int applied = 0;

        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(file.getValue(), "suppression");
            } catch (RuntimeException e) {
                LOGGER.error("[sewv] Suppression table {} is not a JSON object - skipped: {}",
                        file.getKey(), e.getMessage());
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Kind kind = byKey(entry.getKey());
                if (kind == null) {
                    LOGGER.warn("[sewv] Suppression table {}: no such kind '{}' - ignored",
                            file.getKey(), entry.getKey());
                    continue;
                }
                Row row = readRow(file.getKey(), kind, entry.getValue());
                if (row == null) continue;
                rows.put(kind, row);
                applied++;
            }
        }

        if (applied == 0) {
            LOGGER.error("[sewv] No usable suppression table entries found - vehicle and explosion fire "
                    + "will not suppress infantry.");
            return fallback();
        }
        for (Kind k : Kind.values()) rows.putIfAbsent(k, new Row(0.0f, 0.0));
        LOGGER.info("[sewv] Loaded {} suppression kinds from {} file(s)", applied, files.size());
        return new SuppressionTable(rows);
    }

    private static Row readRow(ResourceLocation source, Kind kind, JsonElement element) {
        JsonObject obj;
        try {
            obj = GsonHelper.convertToJsonObject(element, kind.name());
        } catch (RuntimeException e) {
            LOGGER.error("[sewv] Suppression table {}: '{}' is not an object - skipped", source, kind);
            return null;
        }
        float amount = (float) Mth.clamp(readDouble(obj, "amount", 0.0), 0.0, MAX_AMOUNT);
        double maxRadius = kind == Kind.EXPLOSION ? MAX_EXPLOSION_RADIUS_MULT : 32.0;
        double radius = Mth.clamp(readDouble(obj, "radius", 0.0), 0.0, maxRadius);
        return new Row(amount, radius);
    }

    private static double readDouble(JsonObject obj, String key, double fallback) {
        if (!obj.has(key)) return fallback;
        try {
            return GsonHelper.convertToDouble(obj.get(key), key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static Kind byKey(String key) {
        for (Kind k : Kind.values()) {
            if (k.name().equalsIgnoreCase(key)) return k;
        }
        return null;
    }

    /**
     * Loads {@code data/<namespace>/sewv/suppression/*.json}. Registered on
     * {@code AddReloadListenerEvent} in {@link com.neoalive.tacz_sewv.TaczSewv}.
     */
    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/suppression");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
        }
    }
}
