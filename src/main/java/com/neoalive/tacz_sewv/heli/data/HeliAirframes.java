package com.neoalive.tacz_sewv.heli.data;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.heli.physics.Airframe;

/**
 * The live helicopter airframe table: {@code data/<ns>/sewv/heli/*.json}, reloaded with the
 * datapacks (the same pattern as {@code UtilityWeights}). Until a reload has happened, and if
 * every file fails, it falls back to the copy of the shipped file inside the mod jar.
 *
 * <p>Hull to class: an exact registry id in {@code hulls}; else the first {@code clues} substring
 * of the lower-cased id; else a role class ({@code utility} for transport hulls, {@code attack}
 * for heavy ones, {@code light} otherwise), logged once per id so packs know to add a row.
 */
public final class HeliAirframes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BUNDLED = "/data/tacz_sewv/sewv/heli/airframes.json";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile AirframeData.Result active = bundled();
    private static volatile int generation;

    private HeliAirframes() {}

    /** Bumped on every reload; runtimes re-resolve their airframe when it changes. */
    public static int generation() {
        return generation;
    }

    /** Size ratio range a derived row is scaled across; beyond it the role class is a poor model anyway. */
    private static final double LAMBDA_MIN = 0.5, LAMBDA_MAX = 2.0;

    /**
     * The airframe for a hull, or null if the table is empty. A hull with neither a row nor a clue
     * flies its role class Froude-scaled to its own width (plan O6), when the class carries a
     * {@code refWidth}; a scaled row that fails validation falls back to the class as written.
     */
    public static Airframe resolve(String registryId, boolean transport, boolean heavy, double hullWidth) {
        AirframeData.Result r = active;
        if (r == null || r.classes().isEmpty()) return null;
        String id = registryId == null ? "" : registryId.toLowerCase(Locale.ROOT);
        String cls = r.hulls().get(id);
        if (cls == null) {
            for (AirframeData.Clue c : r.clues()) {
                if (id.contains(c.contains().toLowerCase(Locale.ROOT))) {
                    cls = c.className();
                    break;
                }
            }
        }
        boolean derived = cls == null || !r.classes().containsKey(cls);
        if (derived) cls = transport ? "utility" : heavy ? "attack" : "light";
        Airframe af = r.classes().get(cls);
        if (af == null) return r.classes().values().iterator().next();
        if (!derived) return af;
        double lambda = af.refWidth > 0.0 && hullWidth > 0.0
                ? Math.max(LAMBDA_MIN, Math.min(LAMBDA_MAX, hullWidth / af.refWidth)) : 1.0;
        Airframe scaled = lambda == 1.0 ? af : AirframeData.scaled(af, lambda);
        List<String> problems = AirframeData.validate(scaled, AirframeData.CHECK_GRAVITY, Airframe.RHO0);
        if (LOGGED.add(id)) {
            LOGGER.info("[sewv heli] no airframe row for {}: flying role class '{}' scaled x{} to its width{}"
                            + " (add a row to data/<ns>/sewv/heli to tune)", id, cls,
                    String.format(Locale.ROOT, "%.2f", lambda),
                    problems.isEmpty() ? "" : "; scaled row invalid (" + String.join("; ", problems) + "), using it unscaled");
        }
        return problems.isEmpty() ? scaled : af;
    }

    static AirframeData.Result bundled() {
        try (InputStream in = HeliAirframes.class.getResourceAsStream(BUNDLED)) {
            if (in == null) return null;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return AirframeData.parse(List.of(Map.entry("bundled", root)));
        } catch (Exception e) {
            LOGGER.error("[sewv heli] bundled airframe table unreadable", e);
            return null;
        }
    }

    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/heli");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            List<Map.Entry<String, JsonObject>> sorted = new ArrayList<>();
            files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(e -> e.getValue().isJsonObject())
                    .forEach(e -> sorted.add(Map.entry(e.getKey().toString(), e.getValue().getAsJsonObject())));
            AirframeData.Result r = AirframeData.parse(sorted);
            r.warnings().forEach(w -> LOGGER.warn("[sewv heli] {}", w));
            if (r.classes().isEmpty()) {
                LOGGER.error("[sewv heli] no usable airframe rows; keeping the bundled table");
                r = bundled();
            } else {
                LOGGER.info("[sewv heli] loaded {} airframe classes, {} hull mappings", r.classes().size(), r.hulls().size());
            }
            active = r;
            generation++;
        }
    }
}
