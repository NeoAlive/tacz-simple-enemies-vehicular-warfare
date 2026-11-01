package com.neoalive.tacz_sewv.wing.data;

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

import com.neoalive.tacz_sewv.wing.physics.WingAirframe;

/**
 * The live fixed-wing airframe table: {@code data/<ns>/sewv/wing/*.json}, reloaded with the
 * datapacks exactly like {@code HeliAirframes}. Until a reload has happened, and if every file
 * fails, it falls back to the copy of the shipped file inside the mod jar.
 *
 * <p>Hull to class: an exact registry id in {@code hulls}; else the first {@code clues} substring
 * of the lower-cased id; else a role class from what SBW's data does say about the hull (a gunship
 * if it is very wide, a light or dive-bombing prop if it has no retractable gear, an attack jet
 * otherwise), logged once per id so packs know to add a row. Unlike the helicopter table there is
 * no size scaling of derived rows: a fixed wing's handling is its class, not its hitbox.
 */
public final class WingAirframes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BUNDLED = "/data/tacz_sewv/sewv/wing/airframes.json";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    /** Hitbox width at or past which an unlisted hull is taken for a gunship / transport. */
    private static final double GUNSHIP_WIDTH = 8.0;
    /** Hitbox width under which a gearless unlisted hull is taken for a light aircraft. */
    private static final double LIGHT_WIDTH = 2.0;

    private static volatile WingAirframeData.Result active = bundled();
    private static volatile int generation;

    private WingAirframes() {}

    /** Bumped on every reload; runtimes re-resolve their airframe when it changes. */
    public static int generation() {
        return generation;
    }

    /** The airframe for a hull, or null if the table is empty. */
    public static WingAirframe resolve(String registryId, boolean hasGear, double hullWidth) {
        WingAirframeData.Result r = active;
        if (r == null || r.classes().isEmpty()) return null;
        String id = registryId == null ? "" : registryId.toLowerCase(Locale.ROOT);
        String cls = r.hulls().get(id);
        if (cls == null) {
            for (WingAirframeData.Clue c : r.clues()) {
                if (id.contains(c.contains().toLowerCase(Locale.ROOT))) {
                    cls = c.className();
                    break;
                }
            }
        }
        if (cls != null && r.classes().containsKey(cls)) return r.classes().get(cls);
        String role = roleClass(hasGear, hullWidth);
        WingAirframe af = r.classes().get(role);
        if (LOGGED.add(id)) {
            LOGGER.info("[sewv wing] no airframe row for {}: flying role class '{}' (add a row to data/<ns>/sewv/wing to tune)",
                    id, af != null ? role : r.classes().keySet().iterator().next());
        }
        return af != null ? af : r.classes().values().iterator().next();
    }

    /** The role class for a hull nothing names. */
    public static String roleClass(boolean hasGear, double hullWidth) {
        if (hullWidth >= GUNSHIP_WIDTH) return "gunship";
        if (!hasGear) return hullWidth < LIGHT_WIDTH ? "light_prop" : "dive_bomber";
        return "attack_jet";
    }

    static WingAirframeData.Result bundled() {
        try (InputStream in = WingAirframes.class.getResourceAsStream(BUNDLED)) {
            if (in == null) return null;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return WingAirframeData.parse(List.of(Map.entry("bundled", root)));
        } catch (Exception e) {
            LOGGER.error("[sewv wing] bundled airframe table unreadable", e);
            return null;
        }
    }

    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/wing");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            List<Map.Entry<String, JsonObject>> sorted = new ArrayList<>();
            files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(e -> e.getValue().isJsonObject())
                    .forEach(e -> sorted.add(Map.entry(e.getKey().toString(), e.getValue().getAsJsonObject())));
            WingAirframeData.Result r = WingAirframeData.parse(sorted);
            r.warnings().forEach(w -> LOGGER.warn("[sewv wing] {}", w));
            if (r.classes().isEmpty()) {
                LOGGER.error("[sewv wing] no usable airframe rows; keeping the bundled table");
                r = bundled();
            } else {
                LOGGER.info("[sewv wing] loaded {} airframe classes, {} hull mappings", r.classes().size(), r.hulls().size());
            }
            active = r;
            generation++;
        }
    }
}
