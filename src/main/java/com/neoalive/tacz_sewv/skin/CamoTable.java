package com.neoalive.tacz_sewv.skin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Which camo number a faction wears in which biome. Pure data — no Minecraft classes, so
 * {@code selfCheckCamo} runs it headless.
 *
 * <p>The table is keyed the way skins are named: an entry {@code ru_1} is camo 1 for RU, and it
 * lists the biome ids it is apt for. When several entries of one faction list the same biome
 * (ru_1 and ru_3 are both temperate) they are equal candidates and {@link #pick} coin-flips
 * between them — nothing competes, nothing is preferred.
 *
 * <p><b>Phase-2 hook:</b> {@link #build} takes exactly the {@code "ru_1" → [biome ids]} shape a
 * config would carry. The hard-coded default is only expanded into that shape; a config loader
 * calls {@code build} and {@link #install}s the result.
 */
public final class CamoTable {

    // Every vanilla 1.20.1 biome sits in exactly one zone (the self-check asserts all 64).
    static final List<String> TEMPERATE = ids(
            "plains", "sunflower_plains", "forest", "flower_forest", "birch_forest",
            "old_growth_birch_forest", "dark_forest", "jungle", "sparse_jungle", "bamboo_jungle",
            "swamp", "mangrove_swamp", "meadow", "cherry_grove", "savanna", "savanna_plateau",
            "windswept_savanna", "windswept_hills", "windswept_forest", "river", "beach",
            "ocean", "deep_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "warm_ocean",
            "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark",
            // Nowhere a camo scheme was drawn for; the faction's temperate camo reads least odd.
            "the_void", "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley",
            "basalt_deltas", "the_end", "end_highlands", "end_midlands", "small_end_islands",
            "end_barrens");
    static final List<String> COLD = ids(
            "snowy_plains", "ice_spikes", "taiga", "snowy_taiga", "old_growth_pine_taiga",
            "old_growth_spruce_taiga", "grove", "snowy_slopes", "frozen_peaks", "jagged_peaks",
            "stony_peaks", "windswept_gravelly_hills", "frozen_river", "snowy_beach", "stony_shore",
            "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean");
    static final List<String> DESERT = ids("desert");
    static final List<String> BADLANDS = ids("badlands", "eroded_badlands", "wooded_badlands");

    /** faction key → biome id → candidate camo numbers (ascending). */
    private static volatile Map<String, Map<String, int[]>> table = build(defaults());

    private CamoTable() {
    }

    /** The hard-coded phase-1 entries, in the same shape a config would supply. */
    static Map<String, List<String>> defaults() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("pmc_1", concat(TEMPERATE, DESERT, BADLANDS));
        out.put("pmc_2", COLD);
        out.put("ru_1", concat(TEMPERATE, COLD));
        out.put("ru_2", concat(DESERT, BADLANDS));
        out.put("ru_3", TEMPERATE);
        out.put("us_1", COLD);
        out.put("us_2", TEMPERATE);
        out.put("us_3", concat(DESERT, BADLANDS));
        return out;
    }

    /**
     * {@code "<faction>_<n>" → biome ids} into the lookup table. A malformed key is skipped and
     * reported through {@code badKeys} (the caller decides how loudly); it never throws.
     */
    public static Map<String, Map<String, int[]>> build(Map<String, List<String>> entries, List<String> badKeys) {
        Map<String, Map<String, TreeSet<Integer>>> raw = new HashMap<>();
        for (Map.Entry<String, List<String>> e : entries.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            int under = key.lastIndexOf('_');
            int n;
            try {
                n = Integer.parseInt(key.substring(under + 1));
            } catch (RuntimeException ex) {
                badKeys.add(e.getKey());
                continue;
            }
            if (under <= 0 || n < 0 || e.getValue() == null) {
                badKeys.add(e.getKey());
                continue;
            }
            Map<String, TreeSet<Integer>> byBiome = raw.computeIfAbsent(key.substring(0, under), k -> new HashMap<>());
            for (String biome : e.getValue()) {
                if (biome != null) byBiome.computeIfAbsent(biome.toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(n);
            }
        }
        Map<String, Map<String, int[]>> out = new HashMap<>();
        raw.forEach((faction, byBiome) -> {
            Map<String, int[]> resolved = new HashMap<>();
            byBiome.forEach((biome, camos) -> resolved.put(biome, camos.stream().mapToInt(Integer::intValue).toArray()));
            out.put(faction, resolved);
        });
        return out;
    }

    static Map<String, Map<String, int[]>> build(Map<String, List<String>> entries) {
        return build(entries, new ArrayList<>());
    }

    /** Swap the live table (phase-2 config reload). */
    public static void install(Map<String, Map<String, int[]>> built) {
        table = built;
    }

    /** Candidate camo numbers for a faction in a biome; empty when nothing is mapped. */
    public static int[] candidates(String faction, String biomeId) {
        Map<String, int[]> byBiome = table.get(faction);
        int[] out = byBiome == null ? null : byBiome.get(biomeId);
        return out == null ? new int[0] : out;
    }

    /**
     * The camo for {@code faction} in {@code biomeId}, or {@code -1} if the biome is unmapped.
     * Overlapping entries are a uniform pick on {@code seed} — deterministic, so everything
     * sharing a seed (a squad and its tank in one region) lands on the same camo.
     */
    public static int pick(String faction, String biomeId, long seed) {
        int[] camos = candidates(faction, biomeId);
        if (camos.length == 0) return -1;
        if (camos.length == 1) return camos[0];
        // splitmix64 finaliser: adjacent region seeds must not all land on the same parity.
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return camos[(int) Math.floorMod(z, (long) camos.length)];
    }

    private static List<String> ids(String... paths) {
        List<String> out = new ArrayList<>(paths.length);
        for (String p : paths) out.add("minecraft:" + p);
        return List.copyOf(out);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> l : lists) out.addAll(l);
        return out;
    }
}
