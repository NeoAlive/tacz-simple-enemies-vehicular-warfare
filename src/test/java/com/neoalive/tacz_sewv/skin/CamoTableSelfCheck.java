package com.neoalive.tacz_sewv.skin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code ./gradlew selfCheckCamo} — biome camo table. Plain main + assert, like the others. */
public final class CamoTableSelfCheck {

    private static final String[] FACTIONS = {"pmc", "ru", "us"};

    public static void main(String[] args) {
        // Every vanilla 1.20.1 biome in exactly one zone, and every faction dresses for all of them.
        List<String> all = new ArrayList<>();
        all.addAll(CamoTable.TEMPERATE);
        all.addAll(CamoTable.COLD);
        all.addAll(CamoTable.DESERT);
        all.addAll(CamoTable.BADLANDS);
        assert all.size() == 64 : "expected 64 vanilla biomes, got " + all.size();
        assert new HashSet<>(all).size() == 64 : "a biome sits in two zones";
        for (String f : FACTIONS) {
            for (String b : all) {
                assert CamoTable.candidates(f, b).length > 0 : f + " has no camo for " + b;
            }
        }

        // The authored table.
        assert only("us", "plains") == 2;
        assert only("us", "desert") == 3 && only("us", "badlands") == 3;
        assert only("us", "snowy_plains") == 1 && only("us", "taiga") == 1;
        assert only("ru", "desert") == 2 && only("ru", "eroded_badlands") == 2;
        assert only("ru", "snowy_slopes") == 1;
        assert only("pmc", "snowy_plains") == 2;
        assert only("pmc", "desert") == 1 && only("pmc", "plains") == 1;

        // Overlap is a coin flip that reaches every candidate, deterministically.
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            int n = CamoTable.pick("ru", "minecraft:plains", seed);
            assert n == 1 || n == 3 : "ru plains picked " + n;
            assert n == CamoTable.pick("ru", "minecraft:plains", seed) : "pick is not deterministic";
            seen.add(n);
        }
        assert seen.equals(Set.of(1, 3)) : "ru plains never reached " + seen;

        assert CamoTable.pick("ru", "somemod:weird_biome", 7) == -1;
        assert CamoTable.pick("nobody", "minecraft:plains", 7) == -1;

        // Phase-2 hook: a config-shaped map builds a table that replaces the default.
        List<String> bad = new ArrayList<>();
        Map<String, Map<String, int[]>> custom = CamoTable.build(
                Map.of("ru_5", List.of("minecraft:dark_forest"), "garbage", List.of("x")), bad);
        assert bad.equals(List.of("garbage")) : "bad keys " + bad;
        CamoTable.install(custom);
        assert CamoTable.pick("ru", "minecraft:dark_forest", 1) == 5;
        assert CamoTable.pick("ru", "minecraft:plains", 1) == -1;
        CamoTable.install(CamoTable.build(CamoTable.defaults()));

        System.out.println("CamoTableSelfCheck OK");
    }

    private static int only(String faction, String biome) {
        int[] c = CamoTable.candidates(faction, "minecraft:" + biome);
        assert c.length == 1 : faction + " " + biome + " has " + c.length + " candidates";
        return c[0];
    }
}
