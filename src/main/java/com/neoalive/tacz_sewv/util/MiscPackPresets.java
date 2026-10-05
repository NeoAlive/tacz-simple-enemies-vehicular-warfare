package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Hardcoded pack → class-cue + faction-armor tables for {@code /sewv pool misc} presets.
 * Empty curated lists mean “pack does not define this kind/faction” (Replace leaves them alone).
 */
public final class MiscPackPresets {

    public record Tables(Map<CueKind, List<String>> cues, Map<TankFaction, List<String>> armor) {}

    public record Assessment(int cues, int armor, int live) {}

    private static final Map<Pack, Tables> TABLES = buildAll();

    private MiscPackPresets() {}

    public static Tables tables(Pack pack) {
        return TABLES.get(pack);
    }

    public static Assessment assess(Pack pack) {
        Tables t = TABLES.get(pack);
        int cueN = 0;
        for (CueKind k : CueKind.values()) cueN += t.cues().get(k).size();
        Set<String> armorIds = allArmorIds(pack);
        int live = 0;
        for (String id : armorIds) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && ForgeRegistries.ITEMS.containsKey(rl)) live++;
        }
        return new Assessment(cueN, armorIds.size(), live);
    }

    public static int cueCount(Pack pack) {
        return assess(pack).cues();
    }

    public static int armorCount(Pack pack) {
        return allArmorIds(pack).size();
    }

    public static Set<String> allArmorIds(Pack pack) {
        Set<String> out = new LinkedHashSet<>();
        for (TankFaction f : TankFaction.values()) {
            out.addAll(TABLES.get(pack).armor().get(f));
        }
        return out;
    }

    public static Set<String> allCueStrings(Pack pack) {
        Set<String> out = new LinkedHashSet<>();
        for (CueKind k : CueKind.values()) {
            out.addAll(TABLES.get(pack).cues().get(k));
        }
        return out;
    }

    private static Map<Pack, Tables> buildAll() {
        Map<Pack, Tables> out = new EnumMap<>(Pack.class);
        out.put(Pack.SUPERB_WARFARE, sbw());
        out.put(Pack.FCP, fcp());
        out.put(Pack.DRAGONRISE, dragonrise());
        out.put(Pack.ULTIMA_RATIO, ultima());
        out.put(Pack.NEO_ARMS, empty());
        return Collections.unmodifiableMap(out);
    }

    private static final class Builder {
        private final Map<CueKind, List<String>> cues = emptyCues();
        private final Map<TankFaction, List<String>> armor = emptyArmor();

        private static Map<CueKind, List<String>> emptyCues() {
            Map<CueKind, List<String>> out = new EnumMap<>(CueKind.class);
            for (CueKind k : CueKind.values()) out.put(k, new ArrayList<>());
            return out;
        }

        private static Map<TankFaction, List<String>> emptyArmor() {
            Map<TankFaction, List<String>> out = new EnumMap<>(TankFaction.class);
            for (TankFaction f : TankFaction.values()) out.put(f, new ArrayList<>());
            return out;
        }

        Builder cue(CueKind kind, String... values) {
            List<String> list = cues.get(kind);
            for (String v : values) {
                if (!list.contains(v)) list.add(v);
            }
            return this;
        }

        Builder armor(TankFaction faction, String ns, String... paths) {
            List<String> list = armor.get(faction);
            for (String path : paths) {
                String id = path.contains(":") ? path : ns + ":" + path;
                if (!list.contains(id)) list.add(id);
            }
            return this;
        }

        Tables freeze() {
            Map<CueKind, List<String>> c = new EnumMap<>(CueKind.class);
            for (CueKind k : CueKind.values()) c.put(k, List.copyOf(cues.get(k)));
            Map<TankFaction, List<String>> a = new EnumMap<>(TankFaction.class);
            for (TankFaction f : TankFaction.values()) a.put(f, List.copyOf(armor.get(f)));
            return new Tables(Collections.unmodifiableMap(c), Collections.unmodifiableMap(a));
        }
    }

    private static Tables empty() {
        return new Builder().freeze();
    }

    private static Tables sbw() {
        return new Builder()
                .cue(CueKind.IFV, "bradley", "bmp", "lav")
                .cue(CueKind.ANTI_AIR, "lav_ad", "hpj_11")
                .cue(CueKind.ARTILLERY, "plz_05", "mk_42", "mle_1934", "bl_132", "type_63", "fh_77bw")
                .cue(CueKind.PLANE_MISSILE,
                        "missile", "anti_ground_missile", "anti_air_missile", "agm", "kh_", "atgm", "maverick")
                .cue(CueKind.PLANE_BOMB, "bomb", "aerial_bomb", "mortar_shell")
                .cue(CueKind.PLANE_ROCKET, "rocket", "small_rocket", "hydra")
                .cue(CueKind.PLANE_CANNON,
                        "cannon", "machinegun", "gau", "shell_ap", "shell_he", "shell_aa",
                        "rifleammo", "heavyammo")
                .armor(TankFaction.RU, "superbwarfare", "ru_helmet_6b47", "ru_chest_6b43")
                .armor(TankFaction.US, "superbwarfare", "us_helmet_pasgt", "us_chest_iotv")
                .armor(TankFaction.PMC, null,
                        "tacz_sewv:pmc_helmet_mich", "superbwarfare:us_chest_iotv")
                .freeze();
    }

    private static Tables fcp() {
        return new Builder()
                .cue(CueKind.IFV, "bmp", "btr", "brdm", "lav25", "stryker", "aavp")
                .cue(CueKind.ANTI_AIR, "pantsir", "avenger", "asrad", "zu23")
                .cue(CueKind.ARTILLERY, "msta", "m109", "ural_grad", "stryker_mortar", "toyota_hilux_mortar")
                .freeze();
    }

    private static Tables dragonrise() {
        String ns = "dragonrise_reforge";
        return new Builder()
                .cue(CueKind.IFV,
                        "bmp3", "bmd4m", "cv90", "zbd", "zbl", "zsl", "m113", "m2", "m3a3", "aav7", "aavc7")
                .cue(CueKind.ANTI_AIR, "tunguska", "zsu234", "flarakpz1", "wlhgzu23")
                .cue(CueKind.MISSILE_SYSTEM, "9m133", "hj8")
                .cue(CueKind.ARTILLERY, "2s25m", "2s38", "m270")
                // Sets (ArmorSets): msv / gorka3 / type21 lock together when ≥2 pieces are in the
                // list; loose helmets fill HEAD when a set has none. KR06 is fictional — omitted.
                .armor(TankFaction.RU, ns,
                        "msv_chest", "msv_pants", "gorka3", "gorka3_leggings",
                        "cn21", "cnchest", "pants21", "aljin_helmet")
                .armor(TankFaction.US, ns,
                        "fast_helmet", "desert07_helmet", "desert07_chest", "desert07_pants",
                        "ocean07_helmet", "ocean07_chest", "ocean07_pants", "un_helmet")
                .armor(TankFaction.PMC, ns,
                        "fast_helmet", "t21_helmet", "sniper21_helmet", "med21_chest", "pants21", "gorka3")
                .freeze();
    }

    private static Tables ultima() {
        return new Builder()
                .cue(CueKind.IFV, "vbmr")
                .cue(CueKind.ANTI_AIR, "samp_t", "mamba")
                .cue(CueKind.ARTILLERY, "caesar", "vbmr_mepac")
                .freeze();
    }
}
