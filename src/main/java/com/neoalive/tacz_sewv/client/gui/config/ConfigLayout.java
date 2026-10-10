package com.neoalive.tacz_sewv.client.gui.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;

/**
 * How the config screens present the registry: the Essentials page, which settings count as
 * advanced, and the sections that break up the big categories. Presentation only — registry order
 * and indices (the wire ids) never move, so this is the one file to edit to re-file a setting.
 * Section ids resolve to {@code gui.tacz_sewv.config.section.<id>}.
 */
public final class ConfigLayout {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Essentials page: section id → keys, in display order. */
    static final Map<String, List<String>> ESSENTIALS = new LinkedHashMap<>();

    static {
        ESSENTIALS.put("essentials.difficulty", List.of(
                "easyMode", "aiAimAccuracy", "aiFireHesitationTicks", "aiFireCooldownTicks"));
        ESSENTIALS.put("essentials.units", List.of(
                "grenadeThrowCadence", "ifvDismountsEnabled", "autoBoardEnabled", "medicEnabled",
                "pmcDownedEnabled", "npcArmorEnabled"));
        ESSENTIALS.put("essentials.world", List.of(
                "gracePeriodDays", "tankSpawnChanceRu", "tankSpawnChanceUs", "convoyEventsEnabled", "largeCombatEventsEnabled",
                "shellingEventsEnabled", "factionInfiniteAmmo", "vehicleDeathDrops"));
        ESSENTIALS.put("essentials.display", List.of(
                "mapMarkersEnabled", "notificationsEnabled", "factionColorsEnabled"));
    }

    /** Performance knobs and internals: hidden until "Show advanced" (search always shows them). */
    private static final Set<String> ADVANCED_CATEGORIES = Set.of("debug");
    private static final String[] ADVANCED_PREFIXES = {
            "path", "sensorColumn", "treeScan", "cover", "groundFarLod", "planeFarLod", "heliFarLod",
            "combatantIndex", "aiLosCache", "utilityRefresh", "influence", "dubins", "airportSlot",
            "airportExtra", "wideScanCadence", "mapSyncInterval", "contactBoardMoveThreshold",
            "contactBoardCloseBand", "minPlayTicks", "playSwitchMargin", "commandMargin",
            "vehicleTargetScanIntervalTicks", "droneScanIntervalTicks", "droneDeployCheckIntervalTicks",
            "fobThreatEvalIntervalTicks", "semCrewDisableInertiaRotate", "komodoRenderFix"};

    /** Per category: ordered (section id, key prefixes); first match wins. */
    private static final Map<String, List<Rule>> SECTIONS = new LinkedHashMap<>();

    private record Rule(String section, String[] prefixes) {}

    private static void rule(String category, String section, String... prefixes) {
        SECTIONS.computeIfAbsent(category, c -> new ArrayList<>()).add(new Rule(category + "." + section, prefixes));
    }

    static {
        rule("crew_ai", "fire", "aiFire", "aiLos", "aiAim", "smokeBlock", "friendlyFire", "stalemate",
                "vehicleAllyAssist");
        rule("crew_ai", "grenades", "grenade");
        rule("crew_ai", "infantry_weapons", "atWeapon", "atAir", "atSecond", "atBackup", "atEngage", "sbw");
        rule("crew_ai", "medics", "medic", "pmcRevive", "pmcDowned", "pmcCapture", "healthMobility");
        rule("crew_ai", "support", "engineer", "commanderSidearm", "drone", "combatEngineer", "supportDedupe",
                "supportCall", "factionOrganicComms");
        rule("crew_ai", "boarding", "autoBoard", "tow", "autoManMortar", "autoEntrench");
        rule("crew_ai", "awareness", "vehicleTarget", "contactBoard", "wideScan", "combatantIndex", "outerRing",
                "awarenessCues", "individualTactics", "utilityRefresh");
        rule("crew_ai", "movement", "path", "sensor", "tree", "cover", "groundFarLod", "vehicleTerrain",
                "vehicleFormation", "patrol");
        rule("crew_ai", "idle", "idle");
        rule("crew_ai", "crew", "vehicleSkin", "camo", "ifv", "semCrew", "tankRider");

        rule("events", "spawns", "tankSpawn", "planeSpawn", "farEvent", "planesIn", "garrison");
        rule("events", "structures", "structures", "nativeStructures");
        rule("events", "convoy", "convoy");
        rule("events", "large_combat", "largeCombat");
        rule("events", "naval", "naval");
        rule("events", "invasion", "invasion");
        rule("events", "shelling", "shelling", "highChanceMortar", "lowChanceMortar");
        rule("events", "derelict", "derelict");
        rule("events", "overflight", "overflight");

        rule("flight", "helicopters", "heli", "playerCrewRappel", "playerSelfRappel");
        rule("flight", "planes", "plane");
        rule("flight", "airports", "airport", "dubins");

        rule("compat", "ballistics", "tacZBallistic");
        rule("compat", "trees", "vehicleTree");
        rule("compat", "minecolonies", "minecolonies");
        rule("compat", "extermination", "tripod", "invasionPod", "heatRay");
        rule("compat", "rendering", "komodo");
    }

    private static boolean checked;

    private ConfigLayout() {}

    public static boolean isAdvanced(ConfigEntry e) {
        if (ADVANCED_CATEGORIES.contains(e.category)) return true;
        for (String p : ADVANCED_PREFIXES) {
            if (e.key.startsWith(p)) return true;
        }
        return false;
    }

    /** Section id for an entry on its category page, or null when the category is not sectioned. */
    @Nullable
    static String sectionOf(ConfigEntry e) {
        if (e.sectionKey != null) return e.sectionKey;
        List<Rule> rules = SECTIONS.get(e.category);
        if (rules == null) return null;
        for (Rule r : rules) {
            for (String p : r.prefixes()) {
                if (e.key.startsWith(p)) return r.section();
            }
        }
        return "other";
    }

    private static int rank(ConfigEntry e) {
        List<Rule> rules = SECTIONS.get(e.category);
        if (rules == null || e.sectionKey != null) return 0;
        String s = sectionOf(e);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).section().equals(s)) return i;
        }
        return rules.size();
    }

    /** A category's entries grouped by section (stable: registry order inside each section). */
    static List<ConfigEntry> sorted(List<ConfigEntry> entries) {
        List<ConfigEntry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingInt(ConfigLayout::rank));
        return out;
    }

    /** Warns once about any key named here that the registry does not know — a typo would hide it. */
    static void checkKeys() {
        if (checked) return;
        checked = true;
        for (List<String> keys : ESSENTIALS.values()) {
            for (String k : keys) {
                if (ConfigRegistry.byKey(k) == null) {
                    LOGGER.warn("[sewv] ConfigLayout essential '{}' is not a registered config key.", k);
                }
            }
        }
    }
}
