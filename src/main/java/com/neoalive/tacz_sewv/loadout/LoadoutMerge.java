package com.neoalive.tacz_sewv.loadout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The whole "layer over SEM's raw loadout files" transform, as a pure function so it can be checked
 * headless ({@code selfCheckLoadout}). Keys are {@code namespace:path} strings — the same shape as
 * the {@code ResourceLocation}s SEM's reload listener hands its {@code apply}.
 *
 * <p>Why the RAW map and not SEM's parsed pool: whichever parser is live (base SEM or SEM
 * Extended) reads our output, so extended keys work where supported and are ignored where not,
 * and nothing here ever constructs a {@code UnitLoadout}.
 */
public final class LoadoutMerge {

    public static final String NAMESPACE = "simpleenemymod";
    /** Never {@code loadouts.json}: that is the path SEM's default and the Berezka config mod write. */
    public static final String LAYER_FILE = "tacz_sewv_layer";

    private LoadoutMerge() {}

    /** One faction's layer: what the editor saves. */
    public static final class Faction {
        /** Off = the hook leaves this faction's files untouched (the exact fallback). */
        public boolean managed;
        /** REPLACE: drop every other file of the faction; false = INHERIT + add. */
        public boolean replace;
        public final List<LoadoutRow> rows = new ArrayList<>();
        /** {@link #hideKey}s of inherited entries to remove. */
        public final Set<String> hidden = new HashSet<>();

        public Faction copy() {
            Faction f = new Faction();
            f.managed = this.managed;
            f.replace = this.replace;
            this.rows.forEach(r -> f.rows.add(r.copy()));
            f.hidden.addAll(this.hidden);
            return f;
        }
    }

    /** {@code "ns:ru_units/loadouts"} → {@code "ru_units"} (SEM's own faction rule: first path segment). */
    public static String folderOf(String fileId) {
        int colon = fileId.indexOf(':');
        String path = colon >= 0 ? fileId.substring(colon + 1) : fileId;
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    public static String layerFileId(String folder) {
        return NAMESPACE + ":" + folder + "/" + LAYER_FILE;
    }

    public static String hideKey(String fileId, String loadoutName) {
        return fileId + "#" + loadoutName;
    }

    /**
     * @param raw       file id → parsed JSON as SEM's listener saw it (never mutated here)
     * @param layers    folder ({@code ru_units}…) → layer; rows must already be validated
     * @param isSbw     gun id → an SBW gun. Those rows never reach SEM (its equipper can only build
     *                  TACZ guns and would leave the unit empty-handed); see {@link #sbwPool}
     * @param extended  SEM Extended present: emit {@code weight}; otherwise weight becomes duplicate
     *                  entries, because base SEM picks uniformly (emitting both would double-count)
     */
    public static Map<String, JsonElement> merge(Map<String, JsonElement> raw, Map<String, Faction> layers,
                                                 boolean extended, Predicate<String> isSbw) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            String fileId = e.getKey();
            Faction f = layers.get(folderOf(fileId));
            if (f == null || !f.managed || fileId.equals(layerFileId(folderOf(fileId)))) {
                out.put(fileId, e.getValue());
                continue;
            }
            // An empty REPLACE must not leave the faction with no pool at all.
            if (f.replace && hasLiveRows(f, isSbw)) continue;
            out.put(fileId, withoutHidden(fileId, f, e.getValue()));
        }
        for (var e : layers.entrySet()) {
            Faction f = e.getValue();
            if (!f.managed || !hasLiveRows(f, isSbw)) continue;
            out.put(layerFileId(e.getKey()), layerFile(f, extended, isSbw));
        }
        return out;
    }

    /** A faction's SBW rows and the fraction of spawns that should draw from them. */
    public record SbwPool(List<LoadoutRow> rows, double share) {
        public static final SbwPool EMPTY = new SbwPool(List.of(), 0.0);
    }

    /**
     * The SBW half of one faction's pool. SEM keeps rolling its (TACZ-only) table as usual; after
     * spawn a unit is re-armed from {@code rows} with probability {@code share}, which makes every
     * row's overall odds exactly what the editor's % column shows: {@code share = sbw weight /
     * (sbw weight + weight of every entry SEM will actually roll)}.
     *
     * <p>SEM's entries are counted off the MERGED map, so hides, REPLACE and the Extended weight
     * rule are already applied: with Extended an entry weighs its {@code weight}, without it every
     * entry weighs 1 (the layer file already expanded weight into duplicates). REPLACE with no TACZ
     * rows of its own keeps SEM's files — {@code merge} must never leave SEM an empty pool, it throws
     * — so there the SBW rows take every spawn: share 1.
     *
     * @param merged {@link #merge}'s output
     */
    public static SbwPool sbwPool(Map<String, JsonElement> merged, String folder, Faction f,
                                  boolean extended, Predicate<String> isSbw) {
        if (f == null || !f.managed) return SbwPool.EMPTY;
        List<LoadoutRow> rows = new ArrayList<>();
        int sbwWeight = 0;
        for (LoadoutRow r : f.rows) {
            if (r.weight <= 0 || !isSbw.test(r.gunId)) continue;
            rows.add(r.copy());
            sbwWeight += Math.min(r.weight, LoadoutRow.MAX_WEIGHT);
        }
        if (rows.isEmpty()) return SbwPool.EMPTY;
        if (f.replace && !hasLiveRows(f, isSbw)) return new SbwPool(List.copyOf(rows), 1.0);

        double semWeight = 0;
        for (var e : merged.entrySet()) {
            if (!folderOf(e.getKey()).equals(folder) || !e.getValue().isJsonObject()) continue;
            JsonObject root = e.getValue().getAsJsonObject();
            if (!root.has("loadouts") || !root.get("loadouts").isJsonObject()) continue;
            for (var le : root.getAsJsonObject("loadouts").entrySet()) {
                if (!le.getValue().isJsonObject()) continue;
                JsonObject o = le.getValue().getAsJsonObject();
                double w = 1;
                if (extended && o.has("weight")) {
                    try {
                        w = Math.max(1, o.get("weight").getAsInt()); // same floor as the editor % column
                    } catch (RuntimeException ignored) {
                        // SEM Extended's own default applies
                    }
                }
                semWeight += w;
            }
        }
        return new SbwPool(List.copyOf(rows), sbwWeight / (sbwWeight + semWeight));
    }

    /** Live TACZ rows: only those replace SEM files or go into the layer file. */
    private static boolean hasLiveRows(Faction f, Predicate<String> isSbw) {
        return f.rows.stream().anyMatch(r -> r.weight > 0 && !isSbw.test(r.gunId));
    }

    private static JsonElement withoutHidden(String fileId, Faction f, JsonElement file) {
        if (f.hidden.isEmpty() || !file.isJsonObject()) return file;
        JsonObject root = file.getAsJsonObject();
        if (!root.has("loadouts") || !root.get("loadouts").isJsonObject()) return file;
        JsonObject loadouts = root.getAsJsonObject("loadouts");
        boolean touched = false;
        for (String name : loadouts.keySet()) {
            if (f.hidden.contains(hideKey(fileId, name))) { touched = true; break; }
        }
        if (!touched) return file;
        JsonObject copy = root.deepCopy();
        JsonObject kept = new JsonObject();
        for (var le : copy.getAsJsonObject("loadouts").entrySet()) {
            if (!f.hidden.contains(hideKey(fileId, le.getKey()))) kept.add(le.getKey(), le.getValue());
        }
        copy.add("loadouts", kept);
        return copy;
    }

    private static JsonObject layerFile(Faction f, boolean extended, Predicate<String> isSbw) {
        JsonObject loadouts = new JsonObject();
        for (LoadoutRow r : f.rows) {
            if (r.weight <= 0 || isSbw.test(r.gunId)) continue;
            int copies = extended ? 1 : Math.min(r.weight, LoadoutRow.MAX_WEIGHT);
            for (int i = 1; i <= copies; i++) {
                JsonObject row = r.toJson();
                if (!extended) row.remove("weight");
                loadouts.add(uniqueKey(loadouts, copies == 1 ? r.name : r.name + "#" + i), row);
            }
        }
        JsonObject root = new JsonObject();
        root.add("loadouts", loadouts);
        return root;
    }

    private static String uniqueKey(JsonObject into, String wanted) {
        String key = wanted;
        for (int n = 2; into.has(key); n++) key = wanted + "~" + n;
        return key;
    }
}
