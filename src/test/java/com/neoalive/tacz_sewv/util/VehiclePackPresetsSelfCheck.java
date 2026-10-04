package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoalive.tacz_sewv.client.editor.PoolPresetApply;
import com.neoalive.tacz_sewv.client.editor.PoolPresetApply.Mode;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Headless check of pack preset tables and apply semantics
 * ({@code ./gradlew selfCheckVehiclePacks}, needs {@code -ea}).
 */
public final class VehiclePackPresetsSelfCheck {

    public static void main(String[] args) {
        everyPackHasModIdAndTables();
        everyIdIsWellFormed();
        noDuplicatesWithinFactionCategory();
        factionCountsMatchTables();
        knownModIds();
        noneIsNoOp();
        addUnionsWithoutRemoving();
        replaceOverwritesDefinedCategoriesOnly();
        crossFactionDupesAllowed();
        System.out.println("VehiclePackPresetsSelfCheck OK");
    }

    private static void everyPackHasModIdAndTables() {
        for (Pack pack : VehiclePackPresets.packs()) {
            check(pack.modId != null && !pack.modId.isBlank(), pack + " modId");
            check(pack.langKey != null && pack.langKey.startsWith("gui."), pack + " langKey");
            Map<TankFaction, Map<Category, List<String>>> tables = VehiclePackPresets.tables(pack);
            check(tables != null && tables.size() == TankFaction.values().length, pack + " factions");
            for (TankFaction f : TankFaction.values()) {
                check(tables.get(f).size() == Category.values().length, pack + " " + f + " categories");
            }
        }
    }

    private static void everyIdIsWellFormed() {
        for (Pack pack : VehiclePackPresets.packs()) {
            for (String id : VehiclePackPresets.allIds(pack)) {
                check(id.indexOf(':') > 0 && !id.startsWith(":") && !id.endsWith(":"),
                        pack + " bad id " + id);
                // ResourceLocation rules: namespace [a-z0-9_.-], path [a-z0-9/._-]
                int colon = id.indexOf(':');
                String ns = id.substring(0, colon);
                String path = id.substring(colon + 1);
                check(ns.matches("[a-z0-9_.-]+"), pack + " namespace " + id);
                check(path.matches("[a-z0-9/._-]+"), pack + " path " + id);
            }
        }
    }

    private static void noDuplicatesWithinFactionCategory() {
        for (Pack pack : VehiclePackPresets.packs()) {
            Map<TankFaction, Map<Category, List<String>>> tables = VehiclePackPresets.tables(pack);
            for (TankFaction f : TankFaction.values()) {
                for (Category c : Category.values()) {
                    List<String> list = tables.get(f).get(c);
                    Set<String> seen = new HashSet<>();
                    for (String id : list) {
                        check(seen.add(id), pack + " duplicate " + f + "/" + c + " " + id);
                    }
                }
            }
        }
    }

    private static void factionCountsMatchTables() {
        for (Pack pack : VehiclePackPresets.packs()) {
            Map<TankFaction, Integer> counts = VehiclePackIndex.factionCounts(pack);
            for (TankFaction f : TankFaction.values()) {
                int expected = 0;
                for (Category c : Category.values()) {
                    expected += VehiclePackPresets.tables(pack).get(f).get(c).size();
                }
                check(counts.get(f) == expected, pack + " count " + f);
                check(VehiclePackPresets.factionCount(pack, f) == expected, pack + " factionCount " + f);
            }
            check(!VehiclePackPresets.allIds(pack).isEmpty(), pack + " must list at least one id");
        }
    }

    private static void knownModIds() {
        check(Pack.SUPERB_WARFARE.modId.equals("superbwarfare"), "sbw modId");
        check(Pack.FCP.modId.equals("fcp"), "fcp modId");
        check(Pack.DRAGONRISE.modId.equals("dragonrise_reforge"), "dr modId");
        check(Pack.ULTIMA_RATIO.modId.equals("ultimaratio"), "ur modId");
        check(Pack.NEO_ARMS.modId.equals("neoarms"), "neoarms modId");
        check(VehiclePackPresets.packs().length == 5, "five packs");
    }

    private static void noneIsNoOp() {
        Map<TankFaction, Map<Category, List<String>>> pools = blankPools();
        pools.get(TankFaction.RU).get(Category.GROUND).add("keep:me");
        PoolPresetApply.apply(Mode.NONE, Pack.SUPERB_WARFARE, pools);
        check(pools.get(TankFaction.RU).get(Category.GROUND).equals(List.of("keep:me")), "NONE no-op");
    }

    private static void addUnionsWithoutRemoving() {
        Map<TankFaction, Map<Category, List<String>>> pools = blankPools();
        pools.get(TankFaction.RU).get(Category.GROUND).add("keep:me");
        pools.get(TankFaction.RU).get(Category.GROUND).add("superbwarfare:t_90a");
        PoolPresetApply.apply(Mode.ADD, Pack.SUPERB_WARFARE, pools);
        List<String> ground = pools.get(TankFaction.RU).get(Category.GROUND);
        check(ground.contains("keep:me"), "ADD keeps prior");
        check(ground.contains("superbwarfare:t_90a"), "ADD has curated");
        check(ground.contains("superbwarfare:bmp_2"), "ADD merges more");
        long t90 = ground.stream().filter("superbwarfare:t_90a"::equals).count();
        check(t90 == 1, "ADD dedupes");
    }

    private static void replaceOverwritesDefinedCategoriesOnly() {
        Map<TankFaction, Map<Category, List<String>>> pools = blankPools();
        pools.get(TankFaction.RU).get(Category.GROUND).add("keep:ground");
        pools.get(TankFaction.RU).get(Category.GRENADE).add("keep:grenade");
        PoolPresetApply.apply(Mode.REPLACE, Pack.SUPERB_WARFARE, pools);
        List<String> ground = pools.get(TankFaction.RU).get(Category.GROUND);
        check(!ground.contains("keep:ground"), "REPLACE clears defined cat");
        check(ground.contains("superbwarfare:t_90a"), "REPLACE writes curated");
        check(pools.get(TankFaction.RU).get(Category.GRENADE).equals(List.of("keep:grenade")),
                "REPLACE leaves undefined cat");
    }

    private static void crossFactionDupesAllowed() {
        Map<TankFaction, Map<Category, List<String>>> fcp = VehiclePackPresets.tables(Pack.FCP);
        check(fcp.get(TankFaction.RU).get(Category.GROUND).contains("fcp:t72av"), "RU t72av");
        check(fcp.get(TankFaction.PMC).get(Category.GROUND).contains("fcp:t72av"), "PMC t72av dupe");
        Map<TankFaction, Map<Category, List<String>>> neo = VehiclePackPresets.tables(Pack.NEO_ARMS);
        check(neo.get(TankFaction.RU).get(Category.SHIP).contains("neoarms:steregushchiy"), "neo RU ship");
        check(neo.get(TankFaction.US).get(Category.GROUND).contains("neoarms:leopard_2a7v"), "neo US leo");
        check(!neo.get(TankFaction.PMC).get(Category.GROUND).contains("neoarms:leopard_2a7v"),
                "PMC skips tip-tier leo");
    }

    private static Map<TankFaction, Map<Category, List<String>>> blankPools() {
        Map<TankFaction, Map<Category, List<String>>> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) {
            Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
            for (Category c : Category.values()) {
                byCat.put(c, new ArrayList<>());
            }
            out.put(f, byCat);
        }
        return out;
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
