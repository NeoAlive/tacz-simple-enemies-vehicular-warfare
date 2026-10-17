package com.neoalive.tacz_sewv.entity.ai.formation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

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

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

/**
 * Which formation each play role takes. A datapack file (shipped at
 * {@code data/tacz_sewv/sewv/formations/formations.json}), loaded and swapped wholesale exactly like
 * {@code UtilityWeights}: a file that names a row replaces that row, unknown keys warn and are
 * skipped, and {@link #fallback()} is a minimal safety net — every role on a single firing line,
 * which is what the command layer did before formations existed — not a second copy of the table.
 *
 * <p>Rows are keyed by role ({@code base_of_fire}, {@code maneuver}, {@code maneuver_left}, …),
 * optionally prefixed by a play key to override one play ({@code pursuit/maneuver}).
 * {@link #lookup} tries the play-specific row first.
 *
 * <p>Spacing is in world blocks before {@code TacticalScale}; the caller scales it.
 */
public final class FormationTemplates {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile FormationTemplates active = fallback();

    public static FormationTemplates active() {
        return active;
    }

    /** Which way a laid formation faces. */
    public enum Facing {
        /** Toward the enemy along the battle axis — a line across it. */
        AXIS,
        /** From the formation's anchor toward the enemy centroid — a wing turned in on the pocket. */
        ENEMY
    }

    /**
     * One row. {@code rowSize} 0 means "one row holding everyone", which is what a firing line is.
     */
    public record Template(FormationShape shape, double spacing, int rowSize,
                           FormationSpec.Placement placement, Facing facing) {

        public FormationSpec spec(int members, double scale) {
            return new FormationSpec(this.shape, this.spacing * scale, 1.0, 1.0,
                    this.rowSize > 0 ? this.rowSize : Math.max(1, members), this.placement);
        }

        /** The same row reflected across the heading — see {@link FormationShape#mirror}. */
        public Template mirror() {
            return new Template(this.shape.mirror(), this.spacing, this.rowSize, this.placement, this.facing);
        }
    }

    /** Fallback spacing: the pre-formation firing line's 16 blocks. */
    static final double FALLBACK_SPACING = 16.0;
    /** Rows are clamped into this band so a typo cannot stack a formation on one block or scatter it. */
    static final double MIN_SPACING = 4.0;
    static final double MAX_SPACING = 128.0;

    private static final Template FALLBACK_ROW = new Template(FormationShape.LINE, FALLBACK_SPACING, 0,
            FormationSpec.Placement.CENTRED, Facing.AXIS);

    private final Map<String, Template> rows;

    private FormationTemplates(Map<String, Template> rows) {
        this.rows = rows;
    }

    /** Row for {@code roleKey} in {@code playKey}: the play-specific row, else the role's, else the fallback line. */
    public Template lookup(@Nullable String playKey, String roleKey) {
        if (playKey != null) {
            Template specific = this.rows.get(playKey + "/" + roleKey);
            if (specific != null) return specific;
        }
        Template row = this.rows.get(roleKey);
        return row != null ? row : FALLBACK_ROW;
    }

    /** True when a loaded row is keyed exactly {@code key} (no fallback involved). */
    public boolean defines(String key) {
        return this.rows.containsKey(key);
    }

    /** Smallest spacing any row asks for, before scaling — the self-check's arrival invariant reads it. */
    public double minSpacing() {
        double min = FALLBACK_ROW.spacing();
        for (Template t : this.rows.values()) min = Math.min(min, t.spacing());
        return min;
    }

    public static FormationTemplates fallback() {
        return new FormationTemplates(Map.of());
    }

    public static FormationTemplates parse(Map<ResourceLocation, JsonElement> files) {
        Map<String, Template> rows = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            JsonObject root;
            try {
                root = GsonHelper.convertToJsonObject(file.getValue(), "formations");
            } catch (RuntimeException e) {
                LOGGER.error("[sewv] Formation templates {} is not a JSON object - skipped: {}",
                        file.getKey(), e.getMessage());
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                Template row = readRow(file.getKey(), entry.getKey(), entry.getValue());
                if (row != null) rows.put(entry.getKey().toLowerCase(Locale.ROOT), row);
            }
        }
        if (rows.isEmpty()) {
            LOGGER.error("[sewv] No usable formation templates found - every play role falls back to a firing line.");
            return fallback();
        }
        LOGGER.info("[sewv] Loaded {} formation templates from {} file(s)", rows.size(), files.size());
        return new FormationTemplates(rows);
    }

    @Nullable
    private static Template readRow(ResourceLocation source, String key, JsonElement element) {
        JsonObject obj;
        try {
            obj = GsonHelper.convertToJsonObject(element, key);
        } catch (RuntimeException e) {
            LOGGER.warn("[sewv] Formation templates {}: '{}' is not an object - ignored", source, key);
            return null;
        }
        for (String field : obj.keySet()) {
            if (!field.equals("shape") && !field.equals("spacing") && !field.equals("rowSize")
                    && !field.equals("placement") && !field.equals("facing")) {
                LOGGER.warn("[sewv] Formation templates {}: '{}.{}' is not a known field - ignored", source, key, field);
            }
        }
        FormationShape shape = byName(FormationShape.class, GsonHelper.getAsString(obj, "shape", "line"));
        FormationSpec.Placement placement = byName(FormationSpec.Placement.class,
                GsonHelper.getAsString(obj, "placement", "centred"));
        Facing facing = byName(Facing.class, GsonHelper.getAsString(obj, "facing", "axis"));
        if (shape == null || placement == null || facing == null) {
            LOGGER.warn("[sewv] Formation templates {}: '{}' has an unknown shape/placement/facing - ignored",
                    source, key);
            return null;
        }
        double spacing;
        int rowSize;
        try {
            spacing = Mth.clamp(GsonHelper.getAsDouble(obj, "spacing", FALLBACK_SPACING), MIN_SPACING, MAX_SPACING);
            rowSize = Math.max(0, GsonHelper.getAsInt(obj, "rowSize", 0));
        } catch (RuntimeException e) {
            LOGGER.warn("[sewv] Formation templates {}: '{}' has a non-numeric spacing/rowSize - ignored", source, key);
            return null;
        }
        return new Template(shape, spacing, rowSize, placement, facing);
    }

    @Nullable
    private static <E extends Enum<E>> E byName(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/formations");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
        }
    }
}
