package com.neoalive.tacz_sewv.loadout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Hardcoded TACZ gun pack → faction gun-id tables for the loadout editor presets, the weapons
 * counterpart of {@code VehiclePackPresets}. Same conventions: RU = Russian/Soviet + PLA, US = US +
 * NATO, PMC = commercial/boutique; WWII Axis sits in PMC. Launchers, miniguns and fictional guns are
 * left out on purpose (exclusion is "not listed"). Ids carry their namespace, so one pack may span
 * two (Classic RCCRP ships {@code ccrp} and {@code classicr}). Pure data: the self-check runs headless.
 */
public final class WeaponPackPresets {

    public enum Pack {
        TACZ_DEFAULT("gui.tacz_sewv.loadout.preset.pack.tacz"),
        CLASSIC_RCCRP("gui.tacz_sewv.loadout.preset.pack.ccrp"),
        ENLISTED("gui.tacz_sewv.loadout.preset.pack.enlisted"),
        DAFFAS_ARSENAL("gui.tacz_sewv.loadout.preset.pack.daffas"),
        CONTINENTAL("gui.tacz_sewv.loadout.preset.pack.continental"),
        ARIPS("gui.tacz_sewv.loadout.preset.pack.arips");

        public final String langKey;

        Pack(String langKey) {
            this.langKey = langKey;
        }
    }

    private static final Map<Pack, Map<TankFaction, List<String>>> TABLES = buildAll();

    private WeaponPackPresets() {}

    public static Map<TankFaction, List<String>> tables(Pack pack) {
        return TABLES.get(pack);
    }

    /** Unique curated ids across all factions for one pack. */
    public static Set<String> allIds(Pack pack) {
        Set<String> out = new LinkedHashSet<>();
        TABLES.get(pack).values().forEach(out::addAll);
        return out;
    }

    private static Map<Pack, Map<TankFaction, List<String>>> buildAll() {
        Map<Pack, Map<TankFaction, List<String>>> out = new EnumMap<>(Pack.class);
        out.put(Pack.TACZ_DEFAULT, taczDefault());
        out.put(Pack.CLASSIC_RCCRP, classicRccrp());
        out.put(Pack.ENLISTED, enlisted());
        out.put(Pack.DAFFAS_ARSENAL, daffas());
        out.put(Pack.CONTINENTAL, continental());
        out.put(Pack.ARIPS, arips());
        return Collections.unmodifiableMap(out);
    }

    private static final class Builder {
        private final Map<TankFaction, List<String>> data = new EnumMap<>(TankFaction.class);

        Builder() {
            for (TankFaction f : TankFaction.values()) data.put(f, new ArrayList<>());
        }

        Builder put(TankFaction f, String ns, String... paths) {
            List<String> list = data.get(f);
            for (String path : paths) {
                String id = ns + ":" + path;
                if (!list.contains(id)) list.add(id);
            }
            return this;
        }

        Map<TankFaction, List<String>> freeze() {
            Map<TankFaction, List<String>> out = new EnumMap<>(TankFaction.class);
            data.forEach((f, list) -> out.put(f, List.copyOf(list)));
            return Collections.unmodifiableMap(out);
        }
    }

    private static Map<TankFaction, List<String>> taczDefault() {
        String ns = "tacz";
        return new Builder()
                .put(TankFaction.RU, ns, "ak47", "rpk", "sks_tactical", "type_81", "qbz_95", "qbz_191", "cz75")
                .put(TankFaction.US, ns, "m4a1", "m16a4", "hk416d", "scar_l", "scar_h", "mk14", "spr15hb", "m249",
                        "fn_evolys", "m700", "m107", "m870", "m1014", "hk_mp5a5", "aug", "g36k", "glock_17", "m1911",
                        "m9a4")
                .put(TankFaction.PMC, ns, "ak47", "m4a1", "m16a1", "fn_fal", "hk_g3", "aug", "g36k", "sks_tactical",
                        "ump45", "vector45", "p90", "uzi", "aa12", "spas_12", "m870", "ai_awp", "deagle", "p320", "b93r")
                .freeze();
    }

    private static Map<TankFaction, List<String>> classicRccrp() {
        String cc = "ccrp";
        String cr = "classicr";
        return new Builder()
                .put(TankFaction.RU, cc, "ak74", "ak74m", "km_ak74m", "akmn", "ak103", "zenit_nomad_ak103",
                        "zenit_ak104", "zenit_ak105", "ak12_2012", "ak12_2014", "ak12m", "ak15m", "aks74u",
                        "type56b_akmsu", "as_val", "as_val_mod4", "vss", "sr_3m", "ots_14_2a", "rpk16", "rpk74m",
                        "qbz_191", "qbu_191", "type_192", "type_95_longbow", "shield_ots33")
                .put(TankFaction.RU, cr, "ak12", "dp28", "qbz191_renewed")
                .put(TankFaction.US, cc, "m4_cqbr", "m4_sopmod2_fsp", "camg_m4_sopmod2", "camg_mk18", "car_15",
                        "m16a3", "hk416", "hk416c", "hk416a8", "hk417", "mk17", "scar_16s", "scar_17s", "scar_sc",
                        "mcx_spear_tombstone", "sig277", "m249_saw", "m27_iar", "mg36", "hk21", "aug_a3", "aug_hbar",
                        "m110", "m110a3", "m39_emr", "mk13_mod5", "sr25", "troy_m14_sass", "m14_hbar", "hk_g28",
                        "aics_m700", "m38_spr", "mp7a3", "mp5_sd", "m231", "ro635", "camg_m1014", "glock20gen5mos",
                        "glock40gen5mos")
                .put(TankFaction.US, cr, "hk416_a5", "mk18_mod1", "ngsw_r_renewed", "spr_15", "scar_mk20", "m60",
                        "m24_renewed", "msr", "mrad", "m82a2", "mp7", "m92fs")
                .put(TankFaction.PMC, cc, "ddm4", "ddm4a1", "ddm4_pdw", "ddm4_v7_pro", "geissele_superduty",
                        "geissele_hk416a5_smr_fde", "honeybadger", "aac_honeybadger", "mcx_virtus_patrol", "kac_ks_1",
                        "sai_gry", "solgw_mk1", "psa_ak556", "dsa_sa58", "scar_47", "hk433", "m4_urgi", "mk47_dissent",
                        "v308", "x95r", "x95_smg", "f90", "brn_180_bullpup", "aug_para", "mpx", "tti_mpx", "tti_tr1",
                        "vector10", "p90_effen_90", "apc_9k_pro_g", "mp9_thunder", "mp5k", "mp5k_pdw", "uzi45", "ar57",
                        "m1887_long", "marlin_1895", "keltec_p50", "block_17")
                .put(TankFaction.PMC, cr, "fn_fal_tac", "mk47", "mp9", "udp_9", "mac10", "tec_9", "glock_18",
                        "sti2011", "tti_g34", "b93r", "aa410", "colt_python", "m1a1_smg")
                .freeze();
    }

    /** Enlisted WWII: Soviet → RU, Allied → US, Axis → PMC (as DragonRise historical does with hulls). */
    private static Map<TankFaction, List<String>> enlisted() {
        String ns = "ww";
        return new Builder()
                .put(TankFaction.RU, ns, "m91", "svt_40", "avt_40", "as44", "dp28", "pps")
                .put(TankFaction.US, ns, "m1", "m1g", "m1t", "m1903", "lee", "m1918", "m1918a1", "m1918a2", "m1919",
                        "m1921", "m1928a1", "m1a1", "m50", "m2", "m2s", "sten", "m1897", "m1912", "i37", "m1911a1")
                .put(TankFaction.PMC, ns, "kar98k", "g43", "stg44", "mg34", "mg42", "mp28", "mp34", "mp38", "mp40",
                        "mp41", "s1100", "p08", "p38", "c96", "m712", "type38", "type99", "t96", "t99", "t100", "t100l",
                        "t14")
                .freeze();
    }

    private static Map<TankFaction, List<String>> daffas() {
        String ns = "daffas_arsenal";
        return new Builder()
                .put(TankFaction.RU, ns, "ak47", "ak472nd", "aksopmod", "rpd")
                .put(TankFaction.US, ns, "hk416", "hk416_sup", "hk417", "g36", "g36c", "sg550", "sl8", "mg338",
                        "scarhahaha", "scarlalala", "hk45")
                .put(TankFaction.PMC, ns, "sg552", "g36c_commando", "mp5k", "ss1_v2", "ss1_v5", "ss1_v5g", "spasi15",
                        "ssg69", "apacoba9", "harpa9", "uji", "makten_single", "nova", "nova9", "pego", "pegomc",
                        "p99_hak", "taurus")
                .freeze();
    }

    /** Almost all pistols: RU/US get only a few, a pistol-heavy pool makes weak units. */
    private static Map<TankFaction, List<String>> continental() {
        String ns = "the_continental";
        return new Builder()
                .put(TankFaction.RU, ns, "pm", "apb", "qcw05")
                .put(TankFaction.US, ns, "m45a1", "mk23", "sl8")
                .put(TankFaction.PMC, ns, "ump40", "mp510", "mk57", "sl8", "p226", "g18d", "g34_copperhead", "fn57",
                        "fnx45", "hipower", "p30l", "vp40", "92auto")
                .freeze();
    }

    private static Map<TankFaction, List<String>> arips() {
        return new Builder()
                .put(TankFaction.PMC, "apdf", "mdx2", "mdx4", "mdx5", "skw8")
                .freeze();
    }
}
