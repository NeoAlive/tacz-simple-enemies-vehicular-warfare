package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neoalive.tacz_sewv.client.editor.MiscPresetApply;
import com.neoalive.tacz_sewv.client.editor.PoolPresetApply.Mode;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.MiscPackPresets.Tables;
import com.neoalive.tacz_sewv.util.VehiclePackPresets.Pack;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/**
 * Headless check of misc pack preset tables and apply semantics
 * ({@code ./gradlew selfCheckMiscPacks}, needs {@code -ea}).
 */
public final class MiscPackPresetsSelfCheck {

    public static void main(String[] args) {
        everyPackHasTables();
        cuesAndArmorWellFormed();
        noDuplicatesWithinLists();
        packModIdsAlign();
        noneIsNoOp();
        addUnionsWithoutRemoving();
        replaceOverwritesDefinedOnly();
        sbwArmorMatchesDefaultsShape();
        neoArmsEmptyIsSafe();
        System.out.println("MiscPackPresetsSelfCheck OK");
    }

    private static void everyPackHasTables() {
        for (Pack pack : VehiclePackPresets.packs()) {
            Tables t = MiscPackPresets.tables(pack);
            check(t != null, pack + " tables");
            check(t.cues().size() == CueKind.values().length, pack + " cue kinds");
            check(t.armor().size() == TankFaction.values().length, pack + " armor factions");
        }
    }

    private static void cuesAndArmorWellFormed() {
        for (Pack pack : VehiclePackPresets.packs()) {
            for (String clue : MiscPackPresets.allCueStrings(pack)) {
                check(!clue.isBlank(), pack + " blank clue");
                check(!clue.contains(" "), pack + " clue has space: " + clue);
            }
            for (String id : MiscPackPresets.allArmorIds(pack)) {
                int colon = id.indexOf(':');
                check(colon > 0, pack + " armor id " + id);
                String ns = id.substring(0, colon);
                String path = id.substring(colon + 1);
                check(ns.matches("[a-z0-9_.-]+"), pack + " armor ns " + id);
                check(path.matches("[a-z0-9/._-]+"), pack + " armor path " + id);
            }
        }
    }

    private static void noDuplicatesWithinLists() {
        for (Pack pack : VehiclePackPresets.packs()) {
            Tables t = MiscPackPresets.tables(pack);
            for (CueKind k : CueKind.values()) {
                Set<String> seen = new HashSet<>();
                for (String c : t.cues().get(k)) {
                    check(seen.add(c), pack + " dup cue " + k + " " + c);
                }
            }
            for (TankFaction f : TankFaction.values()) {
                Set<String> seen = new HashSet<>();
                for (String id : t.armor().get(f)) {
                    check(seen.add(id), pack + " dup armor " + f + " " + id);
                }
            }
        }
    }

    private static void packModIdsAlign() {
        check(Pack.SUPERB_WARFARE.modId.equals("superbwarfare"), "sbw");
        check(Pack.FCP.modId.equals("fcp"), "fcp");
        check(Pack.DRAGONRISE.modId.equals("dragonrise_reforge"), "dr");
        check(Pack.ULTIMA_RATIO.modId.equals("ultimaratio"), "ur");
        check(Pack.NEO_ARMS.modId.equals("neoarms"), "neo");
        check(MiscPackPresets.cueCount(Pack.FCP) > 0, "fcp has cues");
        check(MiscPackPresets.armorCount(Pack.FCP) == 0, "fcp no armor");
        check(MiscPackPresets.armorCount(Pack.SUPERB_WARFARE) == 5, "sbw unique armor (us chest shared)");
        int sbwArmorRows = 0;
        for (TankFaction f : TankFaction.values()) {
            sbwArmorRows += MiscPackPresets.tables(Pack.SUPERB_WARFARE).armor().get(f).size();
        }
        check(sbwArmorRows == 6, "sbw armor rows across factions");
        check(MiscPackPresets.armorCount(Pack.DRAGONRISE) > 0, "dr armor");
        for (String id : MiscPackPresets.allArmorIds(Pack.DRAGONRISE)) {
            check(!id.contains("kr06"), "dr omits fictional kr06: " + id);
        }
        // Type-21 RU list includes pants so ArmorSets can lock cn21+cnchest+pants21.
        check(MiscPackPresets.tables(Pack.DRAGONRISE).armor().get(TankFaction.RU)
                .contains("dragonrise_reforge:pants21"), "dr ru pants21");
    }

    private static void noneIsNoOp() {
        Map<CueKind, List<String>> cues = blankCues();
        Map<TankFaction, List<String>> armor = blankArmor();
        cues.get(CueKind.IFV).add("keep");
        MiscPresetApply.apply(Mode.NONE, Pack.SUPERB_WARFARE, cues, armor);
        check(cues.get(CueKind.IFV).equals(List.of("keep")), "NONE no-op");
    }

    private static void addUnionsWithoutRemoving() {
        Map<CueKind, List<String>> cues = blankCues();
        Map<TankFaction, List<String>> armor = blankArmor();
        cues.get(CueKind.IFV).add("keep");
        cues.get(CueKind.IFV).add("bradley");
        MiscPresetApply.apply(Mode.ADD, Pack.SUPERB_WARFARE, cues, armor);
        check(cues.get(CueKind.IFV).contains("keep"), "ADD keeps");
        check(cues.get(CueKind.IFV).contains("bradley"), "ADD has bradley");
        check(cues.get(CueKind.IFV).contains("bmp"), "ADD merges bmp");
        check(cues.get(CueKind.IFV).stream().filter("bradley"::equals).count() == 1, "ADD dedupe");
        check(armor.get(TankFaction.RU).contains("superbwarfare:ru_helmet_6b47"), "ADD armor");
    }

    private static void replaceOverwritesDefinedOnly() {
        Map<CueKind, List<String>> cues = blankCues();
        Map<TankFaction, List<String>> armor = blankArmor();
        cues.get(CueKind.IFV).add("keep_ifv");
        cues.get(CueKind.MISSILE_SYSTEM).add("keep_missile");
        armor.get(TankFaction.RU).add("keep:armor");
        MiscPresetApply.apply(Mode.REPLACE, Pack.ULTIMA_RATIO, cues, armor);
        check(!cues.get(CueKind.IFV).contains("keep_ifv"), "REPLACE IFV");
        check(cues.get(CueKind.IFV).contains("vbmr"), "REPLACE writes vbmr");
        check(cues.get(CueKind.MISSILE_SYSTEM).equals(List.of("keep_missile")),
                "REPLACE skips empty missile");
        check(armor.get(TankFaction.RU).equals(List.of("keep:armor")),
                "REPLACE skips empty armor");
    }

    private static void sbwArmorMatchesDefaultsShape() {
        Tables t = MiscPackPresets.tables(Pack.SUPERB_WARFARE);
        check(t.armor().get(TankFaction.RU).contains("superbwarfare:ru_helmet_6b47"), "ru helm");
        check(t.armor().get(TankFaction.RU).contains("superbwarfare:ru_chest_6b43"), "ru chest");
        check(t.armor().get(TankFaction.US).contains("superbwarfare:us_helmet_pasgt"), "us helm");
        check(t.armor().get(TankFaction.US).contains("superbwarfare:us_chest_iotv"), "us chest");
        check(t.armor().get(TankFaction.PMC).contains("tacz_sewv:pmc_helmet_mich"), "pmc helm");
        check(t.armor().get(TankFaction.PMC).contains("superbwarfare:us_chest_iotv"), "pmc chest");
    }

    private static void neoArmsEmptyIsSafe() {
        check(MiscPackPresets.cueCount(Pack.NEO_ARMS) == 0, "neo cues empty");
        check(MiscPackPresets.armorCount(Pack.NEO_ARMS) == 0, "neo armor empty");
        Map<CueKind, List<String>> cues = blankCues();
        Map<TankFaction, List<String>> armor = blankArmor();
        cues.get(CueKind.IFV).add("keep");
        MiscPresetApply.apply(Mode.REPLACE, Pack.NEO_ARMS, cues, armor);
        check(cues.get(CueKind.IFV).equals(List.of("keep")), "empty REPLACE no-op");
    }

    private static Map<CueKind, List<String>> blankCues() {
        Map<CueKind, List<String>> out = new EnumMap<>(CueKind.class);
        for (CueKind k : CueKind.values()) out.put(k, new ArrayList<>());
        return out;
    }

    private static Map<TankFaction, List<String>> blankArmor() {
        Map<TankFaction, List<String>> out = new EnumMap<>(TankFaction.class);
        for (TankFaction f : TankFaction.values()) out.put(f, new ArrayList<>());
        return out;
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }
}
