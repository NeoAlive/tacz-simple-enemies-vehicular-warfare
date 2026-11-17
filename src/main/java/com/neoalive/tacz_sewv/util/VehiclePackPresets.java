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
        DRAGONRISE_HISTORICAL("dragonrise_reforge", "gui.tacz_sewv.pool.preset.pack.dragonrise_historical"),
        ULTIMA_RATIO("ultimaratio", "gui.tacz_sewv.pool.preset.pack.ultimaratio"),
        NEO_ARMS("neoarms", "gui.tacz_sewv.pool.preset.pack.neoarms"),
        LIMITLESS_VEHICLES("ywzj_vehicle", "gui.tacz_sewv.pool.preset.pack.limitless");

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
        out.put(Pack.DRAGONRISE_HISTORICAL, dragonriseHistorical());
        out.put(Pack.ULTIMA_RATIO, ultima());
        out.put(Pack.NEO_ARMS, neoarms());
        out.put(Pack.LIMITLESS_VEHICLES, limitless());
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
                .put(TankFaction.RU, Category.ROTOR, ns, "mi_28")
                .put(TankFaction.RU, Category.PLANE, ns, "j_16", "kv_16")
                .put(TankFaction.RU, Category.SURFACE, ns, "speedboat")
                .put(TankFaction.RU, Category.TOW, ns, "tow", "hpj_11")
                .put(TankFaction.RU, Category.MORTAR, ns, "mortar", "type_63")
                .put(TankFaction.US, Category.GROUND, ns, "m_1a_2", "bradley", "lav_150", "lav_25", "lav_ad",
                        "fh_77bw", "truck")
                .put(TankFaction.US, Category.ROTOR, ns, "ah_6")
                .put(TankFaction.US, Category.PLANE, ns, "a_10a", "ac_130h", "ju_87")
                .put(TankFaction.US, Category.SURFACE, ns, "speedboat")
                .put(TankFaction.US, Category.TOW, ns, "tow", "mk_42", "hpj_11")
                .put(TankFaction.US, Category.MORTAR, ns, "mortar")
                .put(TankFaction.PMC, Category.GROUND, ns, "bradley", "lav_150", "t_90a", "truck",
                        "sodayo_pick_up", "sodayo_pick_up_hmg", "sodayo_pick_up_rocket", "sodayo_pick_up_tow")
                .put(TankFaction.PMC, Category.ROTOR, ns, "ah_6")
                .put(TankFaction.PMC, Category.PLANE, ns, "a_10a")
                .put(TankFaction.PMC, Category.SURFACE, ns, "speedboat")
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
                        "bmp3", "bmp3_cage", "bmp3m", "brm3k",
                        "brdm2", "brdm2_kornet", "btr3e", "btr4e", "btr4e1", "btr4mv1", "btr80", "btr80_cope",
                        "btr82", "btr82_cope", "btr82at",
                        "t72a", "t72av", "t72b", "t72b_1985", "t72b_1989", "t72b2", "t72b3", "t72b3_2016",
                        "t72b3m", "t72m2_moderna", "t90a", "t90m", "t90m_2022", "t90m_2024", "t90ms", "t14_armata",
                        "gaz_tigr", "gaz_tigr_dual", "gaz_tigr_gl", "gaz_tigr_mg", "gaz_tigr_rws", "gaz66", "zis5",
                        "kamaz", "kamaz_kung", "kamaz_long", "kozak2m1", "kozak5", "kozak_ambulance", "skif",
                        "msta", "2s31", "khrizantema", "novator", "novator_unarmed", "novator_stugna", "pantsir",
                        "typhoon_vdv", "typhoon_vdv_unarmed",
                        "uaz", "uaz_3303", "uaz_452", "uaz_dshka", "uaz_spg9",
                        "ural", "ural_fuel", "ural_grad", "ural_kung",
                        "toyota_hilux", "toyota_hilux_bmp", "toyota_hilux_kornet", "toyota_hilux_mortar",
                        "toyota_hilux_rocket_pod", "toyota_hilux_spg9", "toyota_hilux_zu23")
                .put(TankFaction.RU, Category.ROTOR, ns,
                        "mi8", "mi8_amtsh", "mi8_door_guns", "mi8_mtv", "mi17", "mi17_door_guns", "ka52")
                .put(TankFaction.RU, Category.TOW, ns, "empl_kornet", "empl_dshk", "empl_ags17", "empl_zis3")
                .put(TankFaction.US, Category.GROUND, ns,
                        "m1a1", "m1a1_aim", "m1a1_ukr", "m1a2", "m1a2_tusk1", "m1a2_tusk2",
                        "leopard2a4", "leopard2a4_ukr", "leopard2a6", "leopard2a7v", "type10",
                        "m2a2", "m2a2_busk", "m2a3", "m2a3_busk", "m6a2", "puma", "puma_atgm", "marder1a3",
                        "aavp", "lav25", "lav25_mg", "m113", "m113a2",
                        "stryker_dragoon", "stryker_m2", "stryker_mgs", "stryker_mk19", "stryker_tow", "stryker_mortar",
                        "hmmwv_ambulance", "hmmwv_armored_m2", "hmmwv_armored_mk19", "hmmwv_armored_unarmed",
                        "hmmwv_asrad", "hmmwv_avenger", "hmmwv_cargo", "hmmwv_shelter", "hmmwv_soft_top",
                        "hmmwv_soft_top_no_doors", "hmmwv_unarmored_m2", "hmmwv_unarmored_m2_shield",
                        "hmmwv_unarmored_m2_turret", "hmmwv_unarmored_tow", "hmmwv_unarmored_tow_turret",
                        "hmmwv_unarmored_unarmed",
                        "matv", "matv_9in1", "matv_crow", "matv_mk19", "matv_tow",
                        "dingo", "dingo_gmg", "dingo_m2", "dingo_mg3",
                        "caiman", "caiman_unarmed", "caiman_uparmored", "caiman_uparmored_unarmed",
                        "komatsu_lav", "komatsu_lav_shield", "komatsu_lav_unarmed", "type73", "type73_m2",
                        "toyota_hmv", "m109", "m939", "man_hx58", "man_hx58_mg3", "man_hx60", "fmtv", "fmtv_4x4",
                        "rg_33", "dpv_m240", "dpv_minigun")
                .put(TankFaction.US, Category.ROTOR, ns,
                        "huey", "huey_m134_door_guns", "huey_m134_gunship", "huey_m60_door_guns",
                        "huey_m60_gunship", "littlebird", "littlebird_armed", "littlebird_heavy",
                        "uh60", "uh60_minigun", "venom", "venom_door_guns", "venom_gunship", "viper", "oh1",
                        "mh60l", "mh53", "ch47", "ch53a", "ch53e", "bigbird")
                .put(TankFaction.US, Category.TOW, ns, "empl_tow", "empl_m2", "empl_mg3", "empl_mk19")
                .put(TankFaction.PMC, Category.GROUND, ns,
                        "t72av", "t72b", "m1a1", "leopard2a4", "m2a2", "bmp2_noatgm", "bmp3", "brdm2", "btr80",
                        "lav25", "m113", "stryker_m2", "matv", "matv_tow", "dingo_m2", "caiman",
                        "hmmwv_armored_m2", "hmmwv_unarmored_tow", "gaz_tigr_mg", "toyota_hilux_spg9",
                        "toyota_hilux_zu23", "toyota_hilux_kornet", "type73_m2", "uaz_dshka", "rg_33", "dpv_minigun")
                .put(TankFaction.PMC, Category.ROTOR, ns,
                        "huey_m60_gunship", "littlebird_armed", "littlebird_heavy", "uh60", "uh60_minigun",
                        "mh60l", "mi17", "mi8_door_guns", "venom")
                .put(TankFaction.PMC, Category.TOW, ns, "empl_tow", "empl_kornet", "empl_dshk")
                .freeze();
    }

    // ---------------------------------------------------------------- DragonRise

    /** Modern (post-war) DragonRise hulls; WWI/WWII ones are {@link #dragonriseHistorical}. */
    private static Map<TankFaction, Map<Category, List<String>>> dragonrise() {
        String ns = "dragonrise_reforge";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns,
                        "2s25m", "2s38", "625e", "bmd4m", "bmp3", "bmpt72", "csk181", "darkbear",
                        "pzbjy", "sd905", "syy651", "t72b3", "t80", "t80b", "t90mh", "tjgc",
                        "tunguska", "ural4320", "ural4320_supply", "ural4320_zu23", "vt4a1", "vt4b", "wlsc",
                        "zbd04a", "zbd05", "zbl08", "zlt11", "zsl10", "zsu234", "ztd05", "ztq15", "ztz59a",
                        "ztz96a", "ztz99a", "ztz99ah")
                .put(TankFaction.RU, Category.ROTOR, ns, "ka50", "mi24v", "z10a", "z10me", "z20", "z9")
                .put(TankFaction.RU, Category.PLANE, ns,
                        "j8", "j10", "j10c", "j11", "j15t", "j16", "j20", "j20vtol", "j35", "jf17", "q5", "su24m")
                .put(TankFaction.RU, Category.TOW, ns, "9m133", "hj8", "dshk", "zu23", "wlhgzu23", "akm")
                .put(TankFaction.US, Category.GROUND, ns,
                        "aav7a1", "aavc7c1", "amx56", "challenger_ds", "cm34", "cv90",
                        "flarakpz1", "humvee", "humveetow", "l1a2", "leopard2a4", "m10booker", "m113",
                        "m1a1hc", "m1a2sepv2", "m2", "m270", "m3a3",
                        "mv3", "mv3_armed", "mv3_supply", "strv103", "type100")
                .put(TankFaction.US, Category.ROTOR, ns, "ah1f", "ah64", "ec665", "nh90", "uh60")
                .put(TankFaction.US, Category.PLANE, ns,
                        "ac130", "av8b", "f14", "f15e", "f16c", "fa18e", "jas39e", "refale", "refaleaa")
                .put(TankFaction.US, Category.TOW, ns, "mk19", "npds114", "npds514", "npds810")
                .put(TankFaction.PMC, Category.GROUND, ns,
                        "t72b3", "bmp3", "bmd4m", "cv90", "m2", "m113", "humvee", "humveetow", "mv3_armed",
                        "ural4320_zu23", "zbl08", "amx56")
                .put(TankFaction.PMC, Category.ROTOR, ns, "uh60", "mi24v", "ah1f", "z9")
                .put(TankFaction.PMC, Category.PLANE, ns, "f16c", "jas39e", "jf17", "su24m", "av8b")
                .put(TankFaction.PMC, Category.TOW, ns, "dshk", "mk19", "hj8", "9m133")
                .freeze();
    }

    /** WWI/WWII DragonRise hulls. DShK / M2HB are period guns still in service, so they sit in both. */
    private static Map<TankFaction, Map<Category, List<String>>> dragonriseHistorical() {
        String ns = "dragonrise_reforge";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns, "is2", "kv1", "t3476", "t3485")
                .put(TankFaction.RU, Category.TOW, ns, "dshk")
                .put(TankFaction.US, Category.GROUND, ns,
                        "churchill_vii", "comet", "lvt", "m2", "m3stuart", "m4a2", "m4a2_105", "markv",
                        "pershing", "type3", "type97", "type97q")
                .put(TankFaction.US, Category.PLANE, ns, "f4u")
                .put(TankFaction.US, Category.TOW, ns, "pak40")
                .put(TankFaction.PMC, Category.GROUND, ns, "m2", "type97", "panzer4", "tiger")
                .put(TankFaction.PMC, Category.TOW, ns, "dshk")
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
                .put(TankFaction.RU, Category.SURFACE, ns, "steregushchiy")
                .put(TankFaction.US, Category.GROUND, ns,
                        "c1_ariete", "leopard_1a4", "leopard_2a7v", "leopard_2a7v_bare")
                .put(TankFaction.US, Category.SURFACE, ns, "aircraft_carrier", "k_130")
                .put(TankFaction.PMC, Category.GROUND, ns, "c1_ariete", "leopard_1a4", "leopard_2a7v_bare")
                .put(TankFaction.PMC, Category.SURFACE, ns, "k_130")
                .freeze();
    }

    // ---------------------------------------------------------------- Limitless Vehicles

    /**
     * LV's bundled 0.6.0 vehicles (data ids, not entity types). Fixed wing ({@code j10c}, {@code j36},
     * {@code rafale}), the {@code bgm_71_tow} mount and the remote-flown {@code quadcopter} are left
     * out: crews do not fly LV planes or drones. The PLA hulls stand in for RU, as elsewhere.
     */
    private static Map<TankFaction, Map<Category, List<String>>> limitless() {
        String ns = "ywzj_vehicle";
        return new Builder()
                .put(TankFaction.RU, Category.GROUND, ns, "ztz99a", "ztl11", "cssa5", "htf5980")
                .put(TankFaction.RU, Category.ROTOR, ns, "z10")
                .put(TankFaction.RU, Category.SURFACE, ns, "assault_boat")
                .put(TankFaction.US, Category.GROUND, ns, "m1a2", "lav_ad", "atv_mrzr4")
                .put(TankFaction.US, Category.ROTOR, ns, "ah64d", "ah_6")
                .put(TankFaction.US, Category.SURFACE, ns, "assault_boat")
                .put(TankFaction.PMC, Category.GROUND, ns, "atv_mrzr4", "atv_coyoto", "cssa5", "hiace", "motorcycle")
                .put(TankFaction.PMC, Category.ROTOR, ns, "ah_6")
                .put(TankFaction.PMC, Category.SURFACE, ns, "assault_boat")
                .freeze();
    }
}
