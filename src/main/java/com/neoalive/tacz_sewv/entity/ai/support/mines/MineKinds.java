package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * How each mine item in the MINE pool is laid: which entity it becomes, whether it sits on the
 * ground, faces the enemy, or hangs on a wall, and how far apart two of them go. A datapack table
 * ({@code data/<ns>/sewv/mines/*.json}, item id to entry) so an addon mine needs no code. A file that
 * names an item replaces that item's whole entry.
 */
public final class MineKinds {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Placement {
        /** Flat on the ground, no facing (TM-62, BLU-43). */
        GROUND,
        /** On the ground, yawed at the expected approach (Claymore). */
        DIRECTIONAL,
        /** On a wall face, beam across the approach lane (EDD). */
        WALL;

        @Nullable
        static Placement parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public record Kind(ResourceLocation item, ResourceLocation entity, Placement placement, double spacing) {}

    private static volatile Map<ResourceLocation, Kind> active = fallback();
    private static final Set<ResourceLocation> WARNED = new HashSet<>();

    private MineKinds() {}

    /** The built-in SBW mines only. A safety net, not a second copy of the shipped table. */
    public static Map<ResourceLocation, Kind> fallback() {
        Map<ResourceLocation, Kind> out = new HashMap<>();
        put(out, "superbwarfare:tm_62", "superbwarfare:tm_62", Placement.GROUND, 4.0);
        put(out, "superbwarfare:blu_43_mine", "superbwarfare:blu_43", Placement.GROUND, 3.0);
        put(out, "superbwarfare:claymore_mine", "superbwarfare:claymore", Placement.DIRECTIONAL, 3.0);
        put(out, "superbwarfare:edd", "superbwarfare:edd", Placement.WALL, 4.0);
        return Map.copyOf(out);
    }

    private static void put(Map<ResourceLocation, Kind> out, String item, String entity, Placement p, double spacing) {
        ResourceLocation id = new ResourceLocation(item);
        out.put(id, new Kind(id, new ResourceLocation(entity), p, spacing));
    }

    /** The entry for a pool item, or null (warned once) when nothing says how to lay it. */
    @Nullable
    public static Kind of(ResourceLocation item) {
        Kind k = active.get(item);
        if (k == null && WARNED.add(item)) {
            LOGGER.warn("[sewv] Mine pool lists {} but no sewv/mines entry says how to lay it - skipped", item);
        }
        return k;
    }

    public static Map<ResourceLocation, Kind> all() {
        return active;
    }

    static Map<ResourceLocation, Kind> parse(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, Kind> out = new HashMap<>(fallback());
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            if (!file.getValue().isJsonObject()) {
                LOGGER.error("[sewv] Mine kinds {} is not a JSON object - skipped", file.getKey());
                continue;
            }
            for (Map.Entry<String, JsonElement> e : file.getValue().getAsJsonObject().entrySet()) {
                ResourceLocation item = ResourceLocation.tryParse(e.getKey());
                if (item == null || !e.getValue().isJsonObject()) {
                    LOGGER.error("[sewv] Mine kinds {}: bad entry '{}' - skipped", file.getKey(), e.getKey());
                    continue;
                }
                JsonObject o = e.getValue().getAsJsonObject();
                ResourceLocation entity = o.has("entity") ? ResourceLocation.tryParse(o.get("entity").getAsString()) : null;
                Placement p = o.has("placement") ? Placement.parse(o.get("placement").getAsString()) : null;
                double spacing = o.has("spacing") ? o.get("spacing").getAsDouble() : 3.0;
                if (entity == null || p == null) {
                    LOGGER.error("[sewv] Mine kinds {}: '{}' needs entity and placement - skipped",
                            file.getKey(), e.getKey());
                    continue;
                }
                out.put(item, new Kind(item, entity, p, Math.max(1.5, spacing)));
            }
        }
        return Map.copyOf(out);
    }

    public static final class Loader extends SimpleJsonResourceReloadListener {

        private static final Gson GSON = new GsonBuilder().setLenient().create();

        public Loader() {
            super(GSON, "sewv/mines");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                             ProfilerFiller profiler) {
            active = parse(files);
            WARNED.clear();
            LOGGER.info("[sewv] Loaded {} mine kinds", active.size());
        }
    }
}
