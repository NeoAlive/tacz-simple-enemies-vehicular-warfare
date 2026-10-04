package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * Hardcoded pack → faction × category vehicle-id tables for the pool editor presets.
 * Cross-faction duplicates are intentional (especially PMC). Exclusion is “not listed”.
 */
public final class VehiclePackPresets {

    public enum Pack {
        SUPERB_WARFARE("superbwarfare", "gui.tacz_sewv.pool.preset.pack.sbw"),
        FCP("fcp", "gui.tacz_sewv.pool.preset.pack.fcp"),
        DRAGONRISE("dragonrise_reforge", "gui.tacz_sewv.pool.preset.pack.dragonrise"),
        ULTIMA_RATIO("ultimaratio", "gui.tacz_sewv.pool.preset.pack.ultimaratio"),
        NEO_ARMS("neoarms", "gui.tacz_sewv.pool.preset.pack.neoarms");

        public final String modId;
        public final String langKey;

        Pack(String modId, String langKey) {
            this.modId = modId;
            this.langKey = langKey;
        }
    }

    private static final Map<Pack, Map<TankFaction, Map<Category, List<String>>>> TABLES = buildAll();

    private VehiclePackPresets() {}

    /** Immutable view of curated lists for a pack (empty maps/lists where nothing is defined). */
    public static Map<TankFaction, Map<Category, List<String>>> tables(Pack pack) {
        return TABLES.get(pack);
    }

    /** Every pack in declaration order. */
    public static Pack[] packs() {
        return Pack.values();
    }

    /** Unique curated ids across all factions and categories for one pack. */
    public static Set<String> allIds(Pack pack) {
        Set<String> out = new LinkedHashSet<>();
        Map<TankFaction, Map<Category, List<String>>> byFaction = TABLES.get(pack);
        for (TankFaction f : TankFaction.values()) {
            for (Category c : Category.values()) {
                out.addAll(byFaction.get(f).get(c));
            }
        }
        return out;
    }

    /** Curated entry count for one faction (sum across categories; cross-category dupes rare). */
    public static int factionCount(Pack pack, TankFaction faction) {
        int n = 0;
        for (Category c : Category.values()) {
            n += TABLES.get(pack).get(faction).get(c).size();
        }
        return n;
    }

    private static Map<Pack, Map<TankFaction, Map<Category, List<String>>>> buildAll() {
        Map<Pack, Map<TankFaction, Map<Category, List<String>>>> out = new EnumMap<>(Pack.class);
        out.put(Pack.SUPERB_WARFARE, sbw());
        out.put(Pack.FCP, fcp());
        out.put(Pack.DRAGONRISE, dragonrise());
        out.put(Pack.ULTIMA_RATIO, ultima());
        out.put(Pack.NEO_ARMS, neoarms());
        return Collections.unmodifiableMap(out);
    }

    private static final class Builder {
        private final Map<TankFaction, Map<Category, List<String>>> data = emptyMutable();

        private static Map<TankFaction, Map<Category, List<String>>> emptyMutable() {
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

        Builder put(TankFaction f, Category c, String ns, String... paths) {
            List<String> list = data.get(f).get(c);
            for (String path : paths) {
                String id = ns + ":" + path;
                if (!list.contains(id)) list.add(id);
            }
            return this;
        }

        Map<TankFaction, Map<Category, List<String>>> freeze() {
            Map<TankFaction, Map<Category, List<String>>> out = new EnumMap<>(TankFaction.class);
            for (TankFaction f : TankFaction.values()) {
                Map<Category, List<String>> byCat = new EnumMap<>(Category.class);
                for (Category c : Category.values()) {
                    byCat.put(c, List.copyOf(data.get(f).get(c)));
                }
                out.put(f, Collections.unmodifiableMap(byCat));
            }
            return Collections.unmodifiableMap(out);
        }
    }

    // ---------------------------------------------------------------- Superb Warfare

    private static Map<TankFaction, Map<Category, List<String>>> sbw() {
        String ns = "superbwarfare";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns, "t_90a", "bmp_2", "ztz_99a", "plz_05", "yx_100")
                .put(TankFaction.RU, Category.HELI, ns, "mi_28")
                .put(TankFaction.RU, Category.PLANE, ns, "j_16", "kv_16")
                .put(TankFaction.RU, Category.SHIP, ns, "speedboat")
                .put(TankFaction.RU, Category.TOW, ns, "tow", "hpj_11")
                .put(TankFaction.RU, Category.MORTAR, ns, "mortar", "type_63")
                .put(TankFaction.US, Category.GROUND, ns, "m_1a_2", "bradley", "lav_150", "lav_25", "lav_ad",
                        "fh_77bw", "truck")
                .put(TankFaction.US, Category.HELI, ns, "ah_6")
                .put(TankFaction.US, Category.PLANE, ns, "a_10a", "ac_130h", "ju_87")
                .put(TankFaction.US, Category.SHIP, ns, "speedboat")
                .put(TankFaction.US, Category.TOW, ns, "tow", "mk_42", "hpj_11")
                .put(TankFaction.US, Category.MORTAR, ns, "mortar")
                .put(TankFaction.PMC, Category.GROUND, ns, "bradley", "lav_150", "t_90a", "truck",
                        "sodayo_pick_up", "sodayo_pick_up_hmg", "sodayo_pick_up_rocket", "sodayo_pick_up_tow")
                .put(TankFaction.PMC, Category.HELI, ns, "ah_6")
                .put(TankFaction.PMC, Category.PLANE, ns, "a_10a")
                .put(TankFaction.PMC, Category.SHIP, ns, "speedboat")
                .put(TankFaction.PMC, Category.TOW, ns, "tow")
                .put(TankFaction.PMC, Category.MORTAR, ns, "mortar")
                .freeze();
    }

    // ---------------------------------------------------------------- FCP

    private static Map<TankFaction, Map<Category, List<String>>> fcp() {
        String ns = "fcp";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns,
                        "bmp1", "bmp1am", "bmp1p", "bmp1u", "bmp2", "bmp2_noatgm", "bmp2d", "bmp2m", "bmp2md",
                        "brdm2", "btr3e", "btr4mv1", "btr80", "btr80_cope", "btr82", "btr82_cope", "btr82at",
                        "t72av", "t14_armata", "gaz_tigr", "gaz_tigr_gl", "gaz_tigr_mg", "gaz_tigr_rws",
                        "kamaz", "kamaz_kung", "kamaz_long", "kozak2m1", "kozak5", "kozak_ambulance",
                        "msta", "novator", "novator_unarmed", "pantsir",
                        "uaz", "uaz_3303", "uaz_452", "uaz_dshka", "uaz_spg9",
                        "ural", "ural_fuel", "ural_grad", "ural_kung",
                        "toyota_hilux", "toyota_hilux_bmp", "toyota_hilux_mortar", "toyota_hilux_rocket_pod",
                        "toyota_hilux_spg9", "toyota_hilux_zu23")
                .put(TankFaction.RU, Category.HELI, ns,
                        "mi8", "mi8_amtsh", "mi8_door_guns", "mi8_mtv", "mi17", "mi17_door_guns")
                .put(TankFaction.RU, Category.TOW, ns, "empl_kornet", "empl_dshk", "empl_ags17", "empl_zis3")
                .put(TankFaction.US, Category.GROUND, ns,
                        "aavp", "lav25", "stryker_dragoon", "stryker_m2", "stryker_mgs", "stryker_mk19",
                        "stryker_tow", "stryker_mortar",
                        "hmmwv_ambulance", "hmmwv_armored_m2", "hmmwv_armored_mk19", "hmmwv_armored_unarmed",
                        "hmmwv_asrad", "hmmwv_avenger", "hmmwv_cargo", "hmmwv_shelter", "hmmwv_soft_top",
                        "hmmwv_soft_top_no_doors", "hmmwv_unarmored_m2", "hmmwv_unarmored_m2_shield",
                        "hmmwv_unarmored_m2_turret", "hmmwv_unarmored_tow", "hmmwv_unarmored_tow_turret",
                        "hmmwv_unarmored_unarmed",
                        "matv", "matv_9in1", "matv_crow", "matv_tow",
                        "m109", "m939", "man_hx58", "man_hx58_mg3", "fmtv", "rg_33", "dpv_m240", "dpv_minigun")
                .put(TankFaction.US, Category.HELI, ns,
                        "huey", "huey_m134_door_guns", "huey_m134_gunship", "huey_m60_door_guns",
                        "huey_m60_gunship", "huey_rockets", "littlebird", "littlebird_armed", "littlebird_heavy",
                        "uh60", "uh60_minigun", "venom", "venom_door_guns", "venom_gunship", "viper", "oh1",
                        "mh60l", "mh53", "ch53a", "ch53e", "bigbird")
                .put(TankFaction.US, Category.TOW, ns, "empl_tow", "empl_m2", "empl_mg3", "empl_mk19")
                .put(TankFaction.PMC, Category.GROUND, ns,
                        "t72av", "bmp2_noatgm", "brdm2", "btr80", "lav25", "stryker_m2", "matv", "matv_tow",
                        "hmmwv_armored_m2", "hmmwv_unarmored_tow", "gaz_tigr_mg", "toyota_hilux_spg9",
                        "toyota_hilux_zu23", "uaz_dshka", "rg_33", "dpv_minigun")
                .put(TankFaction.PMC, Category.HELI, ns,
                        "huey_m60_gunship", "littlebird_armed", "uh60", "mi17", "mi8_door_guns", "venom")
                .put(TankFaction.PMC, Category.TOW, ns, "empl_tow", "empl_kornet", "empl_dshk")
                .freeze();
    }

    // ---------------------------------------------------------------- DragonRise

    private static Map<TankFaction, Map<Category, List<String>>> dragonrise() {
        String ns = "dragonrise_reforge";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns,
                        "2s25m", "2s38", "625e", "bmd4m", "bmp3", "bmpt72", "csk181", "darkbear", "is2", "kv1",
                        "pzbjy", "sd905", "syy651", "t3476", "t3485", "t72b3", "t80", "t80b", "t90mh", "tjgc",
                        "tunguska", "ural4320", "ural4320_supply", "ural4320_zu23", "vt4a1", "vt4b", "wlsc",
                        "zbd04a", "zbd05", "zbl08", "zlt11", "zsl10", "zsu234", "ztd05", "ztq15", "ztz59a",
                        "ztz96a", "ztz99a", "ztz99ah")
                .put(TankFaction.RU, Category.HELI, ns, "ka50", "mi24v", "z10a", "z10me", "z20", "z9")
                .put(TankFaction.RU, Category.PLANE, ns,
                        "j8", "j10", "j10c", "j11", "j15t", "j16", "j20", "j20vtol", "j35", "jf17", "q5", "su24m")
                .put(TankFaction.RU, Category.TOW, ns, "9m133", "hj8", "dshk", "zu23", "wlhgzu23", "akm")
                .put(TankFaction.US, Category.GROUND, ns,
                        "aav7a1", "aavc7c1", "amx56", "challenger_ds", "churchill_vii", "cm34", "comet", "cv90",
                        "flarakpz1", "humvee", "humveetow", "l1a2", "leopard2a4", "lvt", "m10booker", "m113",
                        "m1a1hc", "m1a2sepv2", "m2", "m270", "m3a3", "m3stuart", "m4a2", "m4a2_105", "markv",
                        "mv3", "mv3_armed", "mv3_supply", "pershing", "strv103", "type100", "type3", "type97",
                        "type97q")
                .put(TankFaction.US, Category.HELI, ns, "ah1f", "ah64", "ec665", "nh90", "uh60")
                .put(TankFaction.US, Category.PLANE, ns,
                        "ac130", "av8b", "f14", "f15e", "f16c", "f4u", "fa18e", "jas39e", "refale", "refaleaa")
                .put(TankFaction.US, Category.TOW, ns, "mk19", "pak40", "npds114", "npds514", "npds810")
                .put(TankFaction.PMC, Category.GROUND, ns,
                        "t72b3", "bmp3", "bmd4m", "cv90", "m2", "m113", "humvee", "humveetow", "mv3_armed",
                        "ural4320_zu23", "zbl08", "amx56", "type97", "panzer4", "tiger")
                .put(TankFaction.PMC, Category.HELI, ns, "uh60", "mi24v", "ah1f", "z9")
                .put(TankFaction.PMC, Category.PLANE, ns, "f16c", "jas39e", "jf17", "su24m", "av8b")
                .put(TankFaction.PMC, Category.TOW, ns, "dshk", "mk19", "hj8", "9m133")
                .freeze();
    }

    // ---------------------------------------------------------------- Ultima Ratio

    private static Map<TankFaction, Map<Category, List<String>>> ultima() {
        String ns = "ultimaratio";
        return new Builder()
                .put(TankFaction.US, Category.GROUND, ns,
                        "caesar", "leclerc_azur", "leclerc_xlr", "mamba", "mamba_radar", "renault_kerax",
                        "samp_t_ng", "vbmr", "vbmr_mepac")
                .put(TankFaction.PMC, Category.GROUND, ns, "vbmr", "renault_kerax", "mamba", "caesar")
                .freeze();
    }

    // ---------------------------------------------------------------- Neo Arms

    private static Map<TankFaction, Map<Category, List<String>>> neoarms() {
        String ns = "neoarms";
        return new Builder()
                .put(TankFaction.RU, Category.SHIP, ns, "steregushchiy")
                .put(TankFaction.US, Category.GROUND, ns,
                        "c1_ariete", "leopard_1a4", "leopard_2a7v", "leopard_2a7v_bare")
                .put(TankFaction.US, Category.SHIP, ns, "aircraft_carrier", "k_130")
                .put(TankFaction.PMC, Category.GROUND, ns, "c1_ariete", "leopard_1a4", "leopard_2a7v_bare")
                .put(TankFaction.PMC, Category.SHIP, ns, "k_130")
                .freeze();
    }
}
