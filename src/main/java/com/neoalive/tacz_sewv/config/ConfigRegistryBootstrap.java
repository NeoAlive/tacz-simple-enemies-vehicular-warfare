package com.neoalive.tacz_sewv.config;

import java.util.Arrays;
import java.util.List;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import com.neoalive.tacz_sewv.init.ModGameRules;

final class ConfigRegistryBootstrap {

    private static final String[] DOCTRINE_FACTIONS = {"ru", "us", "pmc"};

    private ConfigRegistryBootstrap() {}

    static void registerAll(ConfigRegistry.Builder b) {
        registerClient(b);
        registerShortcuts(b);
        registerWorldRules(b);
        registerEvents(b);
        registerResources(b);
        registerSoldiers(b);
        registerStructures(b);
        registerCrewAi(b);
        registerCommand(b);
        registerPlatoon(b);
        registerFlight(b);
        registerIndirectFire(b);
        registerVoicelines(b);
        registerOrders(b);
        registerBoarding(b);
        registerMapIntel(b);
        registerSweep(b);
        registerInvasion(b);
        registerDoctrine(b);
        registerBallistics(b);
        if (isExterminationLoaded()) {
            registerCompatExtermination(b);
        }
        registerCompatTrees(b);
        registerFob(b);
    }

    private static void registerFob(ConfigRegistry.Builder b) {
        b.intRange(ConfigScope.SERVER, "fob", "fobMasterSize", 1, 256,
                SewvConfig.FOB_MASTER_SIZE, SewvConfig.FOB_MASTER_SIZE::set);
        b.intRange(ConfigScope.SERVER, "fob", "fobStockpileSize", 1, 64,
                SewvConfig.FOB_STOCKPILE_SIZE, SewvConfig.FOB_STOCKPILE_SIZE::set);
        b.intRange(ConfigScope.SERVER, "fob", "fobParkingSize", 1, 64,
                SewvConfig.FOB_PARKING_SIZE, SewvConfig.FOB_PARKING_SIZE::set);
        b.doubleRange(ConfigScope.SERVER, "fob", "fobBufferFactor", 1.0, 8.0,
                SewvConfig.FOB_BUFFER_FACTOR, SewvConfig.FOB_BUFFER_FACTOR::set);
        b.intRange(ConfigScope.SERVER, "fob", "fobThreatThreshold", 1, 10000,
                SewvConfig.FOB_THREAT_THRESHOLD, SewvConfig.FOB_THREAT_THRESHOLD::set);
        b.intRange(ConfigScope.SERVER, "fob", "fobAlarmCooldownTicks", 1, 72000,
                SewvConfig.FOB_ALARM_COOLDOWN_TICKS, SewvConfig.FOB_ALARM_COOLDOWN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "fob", "fobThreatEvalIntervalTicks", 1, 200,
                SewvConfig.FOB_THREAT_EVAL_INTERVAL_TICKS, SewvConfig.FOB_THREAT_EVAL_INTERVAL_TICKS::set);
    }

    private static boolean isExterminationLoaded() {
        var list = net.minecraftforge.fml.ModList.get();
        return list != null && list.isLoaded("extermination");
    }

    private static void registerClient(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.CLIENT, "interaction", "showOrderFeedback",
                ClientConfig.SHOW_ORDER_FEEDBACK, ClientConfig.SHOW_ORDER_FEEDBACK::set);

        b.bool(ConfigScope.CLIENT, "overlay", "factionColorsEnabled",
                ClientConfig.FACTION_COLORS_ENABLED, ClientConfig.FACTION_COLORS_ENABLED::set);
        b.hexColor(ConfigScope.CLIENT, "overlay", "colorRu",
                ClientConfig.COLOR_RU, ClientConfig.COLOR_RU::set);
        b.hexColor(ConfigScope.CLIENT, "overlay", "colorUs",
                ClientConfig.COLOR_US, ClientConfig.COLOR_US::set);
        b.hexColor(ConfigScope.CLIENT, "overlay", "colorPmc",
                ClientConfig.COLOR_PMC, ClientConfig.COLOR_PMC::set);
        b.bool(ConfigScope.CLIENT, "overlay", "heliShowRunPhase",
                ClientConfig.HELI_SHOW_RUN_PHASE, ClientConfig.HELI_SHOW_RUN_PHASE::set);

        b.bool(ConfigScope.CLIENT, "map", "mapMarkersEnabled",
                ClientConfig.MAP_MARKERS_ENABLED, ClientConfig.MAP_MARKERS_ENABLED::set);
        b.bool(ConfigScope.CLIENT, "map", "mapTrenchMarkersEnabled",
                ClientConfig.MAP_TRENCH_MARKERS_ENABLED, ClientConfig.MAP_TRENCH_MARKERS_ENABLED::set);
        b.bool(ConfigScope.CLIENT, "map", "mapLive",
                ClientConfig.MAP_LIVE, ClientConfig.MAP_LIVE::set);
        b.bool(ConfigScope.CLIENT, "map", "mapShowIcons",
                ClientConfig.MAP_SHOW_ICONS, ClientConfig.MAP_SHOW_ICONS::set);
        b.bool(ConfigScope.CLIENT, "map", "mapShowHealthBar",
                ClientConfig.MAP_SHOW_HEALTH_BAR, ClientConfig.MAP_SHOW_HEALTH_BAR::set);
        b.bool(ConfigScope.CLIENT, "map", "mapShowEnergyBar",
                ClientConfig.MAP_SHOW_ENERGY_BAR, ClientConfig.MAP_SHOW_ENERGY_BAR::set);
    }

    private static void registerShortcuts(ConfigRegistry.Builder b) {
        b.shortcut(ConfigScope.SERVER, "shortcuts", "pool", "pool");
        b.shortcut(ConfigScope.SERVER, "shortcuts", "misc", "misc");
        b.shortcut(ConfigScope.SERVER, "shortcuts", "target_priority", "target_priority");
        b.shortcut(ConfigScope.SERVER, "shortcuts", "player_doctrine_info", "doctrine_info");
    }

    private static void registerWorldRules(ConfigRegistry.Builder b) {
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.ambient_spawns", ModGameRules.AMBIENT_SPAWNS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.ru_spawns", ModGameRules.RU_SPAWNS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.us_spawns", ModGameRules.US_SPAWNS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.pmc_ambient_spawns",
                ModGameRules.PMC_AMBIENT_SPAWNS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.tanks_in_events", ModGameRules.TANKS_IN_EVENTS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.far_event_spawns", ModGameRules.FAR_EVENT_SPAWNS);
        b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.can_mobs_damage_vehicles",
                ModGameRules.CAN_MOBS_DAMAGE_VEHICLES);
        if (isExterminationLoaded() && ModGameRules.INVASION_OVERRIDES != null) {
            b.gamerule(ConfigScope.SERVER, "world_rules", "world_rules.invasion_overrides",
                    ModGameRules.INVASION_OVERRIDES);
        }
    }

    private static void registerEvents(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "events", "tankSpawnChanceRu", 0.0, 1.0,
                SewvConfig.TANK_SPAWN_CHANCE_RU, SewvConfig.TANK_SPAWN_CHANCE_RU::set);
        b.doubleRange(ConfigScope.SERVER, "events", "tankSpawnChanceUs", 0.0, 1.0,
                SewvConfig.TANK_SPAWN_CHANCE_US, SewvConfig.TANK_SPAWN_CHANCE_US::set);
        b.bool(ConfigScope.SERVER, "events", "planesInEvents",
                SewvConfig.PLANES_IN_EVENTS, SewvConfig.PLANES_IN_EVENTS::set);
        b.doubleRange(ConfigScope.SERVER, "events", "planeSpawnChanceRu", 0.0, 1.0,
                SewvConfig.PLANE_SPAWN_CHANCE_RU, SewvConfig.PLANE_SPAWN_CHANCE_RU::set);
        b.doubleRange(ConfigScope.SERVER, "events", "planeSpawnChanceUs", 0.0, 1.0,
                SewvConfig.PLANE_SPAWN_CHANCE_US, SewvConfig.PLANE_SPAWN_CHANCE_US::set);
        b.bool(ConfigScope.SERVER, "events", "convoyEventsEnabled",
                SewvConfig.CONVOY_EVENTS_ENABLED, SewvConfig.CONVOY_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "convoyBaseChance", 0.0, 1.0,
                SewvConfig.CONVOY_BASE_CHANCE, SewvConfig.CONVOY_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "convoyFailureMultiplier", 0.0, 1.0,
                SewvConfig.CONVOY_FAILURE_MULTIPLIER, SewvConfig.CONVOY_FAILURE_MULTIPLIER::set);
        b.bool(ConfigScope.SERVER, "events", "largeCombatEventsEnabled",
                SewvConfig.LARGE_COMBAT_EVENTS_ENABLED, SewvConfig.LARGE_COMBAT_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "largeCombatBaseChance", 0.0, 1.0,
                SewvConfig.LARGE_COMBAT_BASE_CHANCE, SewvConfig.LARGE_COMBAT_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "largeCombatFailureMultiplier", 0.0, 1.0,
                SewvConfig.LARGE_COMBAT_FAILURE_MULTIPLIER, SewvConfig.LARGE_COMBAT_FAILURE_MULTIPLIER::set);
        b.intRange(ConfigScope.SERVER, "events", "largeCombatVehicles", 0, 8,
                SewvConfig.LARGE_COMBAT_VEHICLES, SewvConfig.LARGE_COMBAT_VEHICLES::set);
        b.doubleRange(ConfigScope.SERVER, "events", "largeCombatEmplacementChance", 0.0, 1.0,
                SewvConfig.LARGE_COMBAT_EMPLACEMENT_CHANCE, SewvConfig.LARGE_COMBAT_EMPLACEMENT_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "largeCombatPlaneChance", 0.0, 1.0,
                SewvConfig.LARGE_COMBAT_PLANE_CHANCE, SewvConfig.LARGE_COMBAT_PLANE_CHANCE::set);
        b.bool(ConfigScope.SERVER, "events", "navalEventsEnabled",
                SewvConfig.NAVAL_EVENTS_ENABLED, SewvConfig.NAVAL_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "navalBaseChance", 0.0, 1.0,
                SewvConfig.NAVAL_BASE_CHANCE, SewvConfig.NAVAL_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "navalFailureMultiplier", 0.0, 1.0,
                SewvConfig.NAVAL_FAILURE_MULTIPLIER, SewvConfig.NAVAL_FAILURE_MULTIPLIER::set);
        b.intRange(ConfigScope.SERVER, "events", "navalShipsPerSide", 0, 12,
                SewvConfig.NAVAL_SHIPS_PER_SIDE, SewvConfig.NAVAL_SHIPS_PER_SIDE::set);
        b.bool(ConfigScope.SERVER, "events", "invasionEventsEnabled",
                SewvConfig.INVASION_EVENTS_ENABLED, SewvConfig.INVASION_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "invasionBaseChance", 0.0, 1.0,
                SewvConfig.INVASION_BASE_CHANCE, SewvConfig.INVASION_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "invasionFailureMultiplier", 0.0, 1.0,
                SewvConfig.INVASION_FAILURE_MULTIPLIER, SewvConfig.INVASION_FAILURE_MULTIPLIER::set);
        b.intRange(ConfigScope.SERVER, "events", "invasionDefenderInfantry", 0, 32,
                SewvConfig.INVASION_DEFENDER_INFANTRY, SewvConfig.INVASION_DEFENDER_INFANTRY::set);
        b.intRange(ConfigScope.SERVER, "events", "invasionDefenderTows", 0, 8,
                SewvConfig.INVASION_DEFENDER_TOWS, SewvConfig.INVASION_DEFENDER_TOWS::set);
        b.intRange(ConfigScope.SERVER, "events", "invasionDefenderMortars", 0, 8,
                SewvConfig.INVASION_DEFENDER_MORTARS, SewvConfig.INVASION_DEFENDER_MORTARS::set);
        b.bool(ConfigScope.SERVER, "events", "shellingEventsEnabled",
                SewvConfig.SHELLING_EVENTS_ENABLED, SewvConfig.SHELLING_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "shellingBaseChance", 0.0, 1.0,
                SewvConfig.SHELLING_BASE_CHANCE, SewvConfig.SHELLING_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "shellingFailureMultiplier", 0.0, 1.0,
                SewvConfig.SHELLING_FAILURE_MULTIPLIER, SewvConfig.SHELLING_FAILURE_MULTIPLIER::set);
        b.intRange(ConfigScope.SERVER, "events", "shellingBaseRadius", 8, 256,
                SewvConfig.SHELLING_BASE_RADIUS, SewvConfig.SHELLING_BASE_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "events", "shellingMortars", 0, 6,
                SewvConfig.SHELLING_MORTARS, SewvConfig.SHELLING_MORTARS::set);
        b.intRange(ConfigScope.SERVER, "events", "shellingGuards", 0, 12,
                SewvConfig.SHELLING_GUARDS, SewvConfig.SHELLING_GUARDS::set);
        b.intRange(ConfigScope.SERVER, "events", "shellingDurationMinTicks", 20, 24000,
                SewvConfig.SHELLING_DURATION_MIN_TICKS, SewvConfig.SHELLING_DURATION_MIN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "events", "shellingDurationMaxTicks", 20, 24000,
                SewvConfig.SHELLING_DURATION_MAX_TICKS, SewvConfig.SHELLING_DURATION_MAX_TICKS::set);
        b.resourceId(ConfigScope.SERVER, "events", "highChanceMortarShell",
                SewvConfig.HIGH_CHANCE_MORTAR_SHELL, SewvConfig.HIGH_CHANCE_MORTAR_SHELL::set);
        b.resourceId(ConfigScope.SERVER, "events", "lowChanceMortarShell",
                SewvConfig.LOW_CHANCE_MORTAR_SHELL, SewvConfig.LOW_CHANCE_MORTAR_SHELL::set);
        b.resourceId(ConfigScope.SERVER, "events", "highChanceType63Rocket",
                SewvConfig.HIGH_CHANCE_TYPE63_ROCKET, SewvConfig.HIGH_CHANCE_TYPE63_ROCKET::set);
        b.resourceId(ConfigScope.SERVER, "events", "lowChanceType63Rocket",
                SewvConfig.LOW_CHANCE_TYPE63_ROCKET, SewvConfig.LOW_CHANCE_TYPE63_ROCKET::set);
        b.bool(ConfigScope.SERVER, "events", "derelictEventsEnabled",
                SewvConfig.DERELICT_EVENTS_ENABLED, SewvConfig.DERELICT_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "derelictBaseChance", 0.0, 1.0,
                SewvConfig.DERELICT_BASE_CHANCE, SewvConfig.DERELICT_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "derelictFailureMultiplier", 0.0, 1.0,
                SewvConfig.DERELICT_FAILURE_MULTIPLIER, SewvConfig.DERELICT_FAILURE_MULTIPLIER::set);
        b.doubleRange(ConfigScope.SERVER, "events", "derelictHealthFraction", 0.01, 1.0,
                SewvConfig.DERELICT_HEALTH_FRACTION, SewvConfig.DERELICT_HEALTH_FRACTION::set);
        b.intRange(ConfigScope.SERVER, "events", "derelictGuards", 0, 12,
                SewvConfig.DERELICT_GUARDS, SewvConfig.DERELICT_GUARDS::set);
        b.intRange(ConfigScope.SERVER, "events", "derelictAmmoCount", 0, 64,
                SewvConfig.DERELICT_AMMO_COUNT, SewvConfig.DERELICT_AMMO_COUNT::set);
        b.bool(ConfigScope.SERVER, "events", "overflightEventsEnabled",
                SewvConfig.OVERFLIGHT_EVENTS_ENABLED, SewvConfig.OVERFLIGHT_EVENTS_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "overflightBaseChance", 0.0, 1.0,
                SewvConfig.OVERFLIGHT_BASE_CHANCE, SewvConfig.OVERFLIGHT_BASE_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "events", "overflightFailureMultiplier", 0.0, 1.0,
                SewvConfig.OVERFLIGHT_FAILURE_MULTIPLIER, SewvConfig.OVERFLIGHT_FAILURE_MULTIPLIER::set);
        b.intRange(ConfigScope.SERVER, "events", "overflightPlanes", 1, 3,
                SewvConfig.OVERFLIGHT_PLANES, SewvConfig.OVERFLIGHT_PLANES::set);
        b.bool(ConfigScope.SERVER, "events", "garrisonVehiclesEnabled",
                SewvConfig.GARRISON_VEHICLES_ENABLED, SewvConfig.GARRISON_VEHICLES_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "events", "garrisonVehicleChance", 0.0, 1.0,
                SewvConfig.GARRISON_VEHICLE_CHANCE, SewvConfig.GARRISON_VEHICLE_CHANCE::set);
    }

    private static void registerResources(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "resources", "creativeAmmoFallback",
                SewvConfig.CREATIVE_AMMO_FALLBACK, SewvConfig.CREATIVE_AMMO_FALLBACK::set);
        b.bool(ConfigScope.SERVER, "resources", "factionInfiniteEnergy",
                SewvConfig.FACTION_INFINITE_ENERGY, SewvConfig.FACTION_INFINITE_ENERGY::set);
        b.bool(ConfigScope.SERVER, "resources", "factionInfiniteAmmo",
                SewvConfig.FACTION_INFINITE_AMMO, SewvConfig.FACTION_INFINITE_AMMO::set);
        b.enumChoice(ConfigScope.SERVER, "resources", "vehicleDeathDrops",
                List.of("disable", "reduced", "everything"),
                SewvConfig.VEHICLE_DEATH_DROPS, SewvConfig.VEHICLE_DEATH_DROPS::set);
        b.bool(ConfigScope.SERVER, "resources", "vehicleAmmoLoot",
                SewvConfig.VEHICLE_AMMO_LOOT, SewvConfig.VEHICLE_AMMO_LOOT::set);
    }

    private static void registerSoldiers(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "soldiers", "npcArmorEnabled",
                SewvConfig.NPC_ARMOR_ENABLED, SewvConfig.NPC_ARMOR_ENABLED::set);
        b.bool(ConfigScope.SERVER, "soldiers", "nameAssignmentEnabled",
                SewvConfig.NAME_ASSIGNMENT_ENABLED, SewvConfig.NAME_ASSIGNMENT_ENABLED::set);
        b.string(ConfigScope.SERVER, "soldiers", "defaultNameCategory",
                SewvConfig.DEFAULT_NAME_CATEGORY, SewvConfig.DEFAULT_NAME_CATEGORY::set);
        b.multilineIds(ConfigScope.SERVER, "soldiers", "nvgEligibleItems",
                () -> List.copyOf(SewvConfig.NVG_ELIGIBLE_ITEMS.get()),
                v -> SewvConfig.NVG_ELIGIBLE_ITEMS.set(v));
        b.doubleRange(ConfigScope.SERVER, "soldiers", "nvgSpawnChance", 0.0, 1.0,
                SewvConfig.NVG_SPAWN_CHANCE, SewvConfig.NVG_SPAWN_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "soldiers", "darkAccuracyFraction", 0.05, 1.0,
                SewvConfig.DARK_ACCURACY_FRACTION, SewvConfig.DARK_ACCURACY_FRACTION::set);
        b.doubleRange(ConfigScope.SERVER, "soldiers", "nvgAccuracyFraction", 0.05, 1.0,
                SewvConfig.NVG_ACCURACY_FRACTION, SewvConfig.NVG_ACCURACY_FRACTION::set);
        b.doubleRange(ConfigScope.SERVER, "soldiers", "darkSpreadScaleMax", 1.0, 10.0,
                SewvConfig.DARK_SPREAD_SCALE_MAX, SewvConfig.DARK_SPREAD_SCALE_MAX::set);
        b.intRange(ConfigScope.SERVER, "soldiers", "darkBlockLightMax", 0, 15,
                SewvConfig.DARK_BLOCK_LIGHT_MAX, SewvConfig.DARK_BLOCK_LIGHT_MAX::set);
    }

    private static void registerStructures(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "structures", "structureVehiclesEnabled",
                SewvConfig.STRUCTURE_VEHICLES_ENABLED, SewvConfig.STRUCTURE_VEHICLES_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "structures", "structureVehicleMaxCount", 1, 16,
                SewvConfig.STRUCTURE_VEHICLE_MAX_COUNT, SewvConfig.STRUCTURE_VEHICLE_MAX_COUNT::set);
        b.intRange(ConfigScope.SERVER, "structures", "structureVehicleRampDays", 0, 1000,
                SewvConfig.STRUCTURE_VEHICLE_RAMP_DAYS, SewvConfig.STRUCTURE_VEHICLE_RAMP_DAYS::set);
        b.multilineIds(ConfigScope.SERVER, "structures", "ruVehicleStructures",
                () -> List.copyOf(SewvConfig.RU_VEHICLE_STRUCTURES.get()),
                v -> SewvConfig.RU_VEHICLE_STRUCTURES.set(v));
        b.multilineIds(ConfigScope.SERVER, "structures", "usVehicleStructures",
                () -> List.copyOf(SewvConfig.US_VEHICLE_STRUCTURES.get()),
                v -> SewvConfig.US_VEHICLE_STRUCTURES.set(v));
        b.multilineIds(ConfigScope.SERVER, "structures", "pmcVehicleStructures",
                () -> List.copyOf(SewvConfig.PMC_VEHICLE_STRUCTURES.get()),
                v -> SewvConfig.PMC_VEHICLE_STRUCTURES.set(v));
    }

    private static void registerCrewAi(ConfigRegistry.Builder b) {
        b.intRange(ConfigScope.SERVER, "crew_ai", "aiFireCooldownTicks", 1, 200,
                SewvConfig.AI_FIRE_COOLDOWN_TICKS, SewvConfig.AI_FIRE_COOLDOWN_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "aiFireAssistConeDeg", 4.0, 90.0,
                SewvConfig.AI_FIRE_ASSIST_CONE_DEG, SewvConfig.AI_FIRE_ASSIST_CONE_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "smokeBlockRadius", 1.0, 16.0,
                SewvConfig.SMOKE_BLOCK_RADIUS, SewvConfig.SMOKE_BLOCK_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "friendlyFireVehicleRadius", 0.0, 32.0,
                SewvConfig.FRIENDLY_FIRE_VEHICLE_RADIUS, SewvConfig.FRIENDLY_FIRE_VEHICLE_RADIUS::set);
        b.enumChoice(ConfigScope.SERVER, "crew_ai", "aiAimAccuracy",
                Arrays.asList("realistic", "scaled", "accurate"),
                SewvConfig.AI_AIM_ACCURACY, SewvConfig.AI_AIM_ACCURACY::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "aiAimSpreadDegrees", 0.0, 30.0,
                SewvConfig.AI_AIM_SPREAD_DEG, SewvConfig.AI_AIM_SPREAD_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "vehicleSkinMountChance", 0.0, 1.0,
                SewvConfig.VEHICLE_SKIN_MOUNT_CHANCE, SewvConfig.VEHICLE_SKIN_MOUNT_CHANCE::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "ifvDismountsEnabled",
                SewvConfig.IFV_DISMOUNTS_ENABLED, SewvConfig.IFV_DISMOUNTS_ENABLED::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "semCrewDisableInertiaRotate",
                SewvConfig.SEM_CREW_DISABLE_INERTIA_ROTATE, SewvConfig.SEM_CREW_DISABLE_INERTIA_ROTATE::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "tankRiderDismountEnabled",
                SewvConfig.TANK_RIDER_DISMOUNT_ENABLED, SewvConfig.TANK_RIDER_DISMOUNT_ENABLED::set);
        b.resourceId(ConfigScope.SERVER, "crew_ai", "atWeaponRu",
                SewvConfig.AT_WEAPON_RU, SewvConfig.AT_WEAPON_RU::set);
        b.resourceId(ConfigScope.SERVER, "crew_ai", "atWeaponUs",
                SewvConfig.AT_WEAPON_US, SewvConfig.AT_WEAPON_US::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "atSecondGunnerChance", 0.0, 1.0,
                SewvConfig.AT_SECOND_GUNNER_CHANCE, SewvConfig.AT_SECOND_GUNNER_CHANCE::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "atBackupAmmo", 1, 64,
                SewvConfig.AT_BACKUP_AMMO, SewvConfig.AT_BACKUP_AMMO::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "atEngageRange", 8.0, 200.0,
                SewvConfig.AT_ENGAGE_RANGE, SewvConfig.AT_ENGAGE_RANGE::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "medicEnabled",
                SewvConfig.MEDIC_ENABLED, SewvConfig.MEDIC_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicSearchRadius", 2.0, 48.0,
                SewvConfig.MEDIC_SEARCH_RADIUS, SewvConfig.MEDIC_SEARCH_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicHealPerTreat", 0.5, 20.0,
                SewvConfig.MEDIC_HEAL_PER_TREAT, SewvConfig.MEDIC_HEAL_PER_TREAT::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "pmcReviveEnabled",
                SewvConfig.PMC_REVIVE_ENABLED, SewvConfig.PMC_REVIVE_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "pmcReviveSearchRadius", 2.0, 64.0,
                SewvConfig.PMC_REVIVE_SEARCH_RADIUS, SewvConfig.PMC_REVIVE_SEARCH_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "pmcReviveChannelTicks", 20, 400,
                SewvConfig.PMC_REVIVE_CHANNEL_TICKS, SewvConfig.PMC_REVIVE_CHANNEL_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "pmcReviveForceSingleplayer",
                SewvConfig.PMC_REVIVE_FORCE_SINGLEPLAYER, SewvConfig.PMC_REVIVE_FORCE_SINGLEPLAYER::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "pmcDownedEnabled",
                SewvConfig.PMC_DOWNED_ENABLED, SewvConfig.PMC_DOWNED_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "pmcDownedHealth", 1.0, 20.0,
                SewvConfig.PMC_DOWNED_HEALTH, SewvConfig.PMC_DOWNED_HEALTH::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "pmcDownedBleedTicks", 100, 6000,
                SewvConfig.PMC_DOWNED_BLEED_TICKS, SewvConfig.PMC_DOWNED_BLEED_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "pmcDownedReviveHealth", 1.0, 40.0,
                SewvConfig.PMC_DOWNED_REVIVE_HEALTH, SewvConfig.PMC_DOWNED_REVIVE_HEALTH::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "medicCaptureEnabled",
                SewvConfig.MEDIC_CAPTURE_ENABLED, SewvConfig.MEDIC_CAPTURE_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicCapturedHealth", 1.0, 20.0,
                SewvConfig.MEDIC_CAPTURED_HEALTH, SewvConfig.MEDIC_CAPTURED_HEALTH::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "medicCaptureDurationTicks", 100, 12000,
                SewvConfig.MEDIC_CAPTURE_DURATION_TICKS, SewvConfig.MEDIC_CAPTURE_DURATION_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicFleeDetectionRadius", 4.0, 64.0,
                SewvConfig.MEDIC_FLEE_DETECTION_RADIUS, SewvConfig.MEDIC_FLEE_DETECTION_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicFleeMinDistance", 2.0, 32.0,
                SewvConfig.MEDIC_FLEE_MIN_DISTANCE, SewvConfig.MEDIC_FLEE_MIN_DISTANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicFleeMaxDistance", 4.0, 64.0,
                SewvConfig.MEDIC_FLEE_MAX_DISTANCE, SewvConfig.MEDIC_FLEE_MAX_DISTANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "pmcCaptureMedicRadius", 8.0, 128.0,
                SewvConfig.PMC_CAPTURE_MEDIC_RADIUS, SewvConfig.PMC_CAPTURE_MEDIC_RADIUS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "healthMobilityEnabled",
                SewvConfig.HEALTH_MOBILITY_ENABLED, SewvConfig.HEALTH_MOBILITY_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "healthMobilityFloor", 0.05, 1.0,
                SewvConfig.HEALTH_MOBILITY_FLOOR, SewvConfig.HEALTH_MOBILITY_FLOOR::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "medicSpawnChance", 0.0, 1.0,
                SewvConfig.MEDIC_SPAWN_CHANCE, SewvConfig.MEDIC_SPAWN_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "engineerSpawnChance", 0.0, 1.0,
                SewvConfig.ENGINEER_SPAWN_CHANCE, SewvConfig.ENGINEER_SPAWN_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "supportDedupeRadius", 4.0, 128.0,
                SewvConfig.SUPPORT_DEDUPE_RADIUS, SewvConfig.SUPPORT_DEDUPE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "engineerSearchRadius", 4.0, 96.0,
                SewvConfig.ENGINEER_SEARCH_RADIUS, SewvConfig.ENGINEER_SEARCH_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "engineerRepairPerTreat", 0.5, 100.0,
                SewvConfig.ENGINEER_REPAIR_PER_TREAT, SewvConfig.ENGINEER_REPAIR_PER_TREAT::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "engineerRepairCooldown", 1, 200,
                SewvConfig.ENGINEER_REPAIR_COOLDOWN, SewvConfig.ENGINEER_REPAIR_COOLDOWN::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "engineerRepairSpeedBoost", 1.0, 3.0,
                SewvConfig.ENGINEER_REPAIR_SPEED_BOOST, SewvConfig.ENGINEER_REPAIR_SPEED_BOOST::set);
        b.multilineIds(ConfigScope.SERVER, "crew_ai", "engineerSidearmPool",
                () -> List.copyOf(SewvConfig.ENGINEER_SIDEARM_POOL.get()),
                v -> SewvConfig.ENGINEER_SIDEARM_POOL.set(v));
        b.multilineIds(ConfigScope.SERVER, "crew_ai", "commanderSidearmPool",
                () -> List.copyOf(SewvConfig.COMMANDER_SIDEARM_POOL.get()),
                v -> SewvConfig.COMMANDER_SIDEARM_POOL.set(v));
        b.intRange(ConfigScope.SERVER, "crew_ai", "droneMaxPerEngineer", 0, 8,
                SewvConfig.DRONE_MAX_PER_ENGINEER, SewvConfig.DRONE_MAX_PER_ENGINEER::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "droneDeployCheckIntervalTicks", 20, 12000,
                SewvConfig.DRONE_DEPLOY_CHECK_INTERVAL_TICKS, SewvConfig.DRONE_DEPLOY_CHECK_INTERVAL_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "droneDeployChance", 0.0, 1.0,
                SewvConfig.DRONE_DEPLOY_CHANCE, SewvConfig.DRONE_DEPLOY_CHANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "droneScanAltitude", 5.0, 60.0,
                SewvConfig.DRONE_SCAN_ALTITUDE, SewvConfig.DRONE_SCAN_ALTITUDE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "droneBroadcastRadius", 16.0, 384.0,
                SewvConfig.DRONE_BROADCAST_RADIUS, SewvConfig.DRONE_BROADCAST_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "droneScanIntervalTicks", 5, 200,
                SewvConfig.DRONE_SCAN_INTERVAL_TICKS, SewvConfig.DRONE_SCAN_INTERVAL_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "droneLeashRadius", 16.0, 512.0,
                SewvConfig.DRONE_LEASH_RADIUS, SewvConfig.DRONE_LEASH_RADIUS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "autoBoardEnabled",
                SewvConfig.AUTO_BOARD_ENABLED, SewvConfig.AUTO_BOARD_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "autoBoardScanRadius", 4.0, 128.0,
                SewvConfig.AUTO_BOARD_SCAN_RADIUS, SewvConfig.AUTO_BOARD_SCAN_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "autoBoardMinHealthFraction", 0.0, 1.0,
                SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION, SewvConfig.AUTO_BOARD_MIN_HEALTH_FRACTION::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "autoBoardStealsPlayerVehicles",
                SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES, SewvConfig.AUTO_BOARD_STEALS_PLAYER_VEHICLES::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "autoManMortarEnabled",
                SewvConfig.AUTO_MAN_MORTAR_ENABLED, SewvConfig.AUTO_MAN_MORTAR_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "autoManMortarScanRadius", 4.0, 128.0,
                SewvConfig.AUTO_MAN_MORTAR_SCAN_RADIUS, SewvConfig.AUTO_MAN_MORTAR_SCAN_RADIUS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "autoEntrenchEnabled",
                SewvConfig.AUTO_ENTRENCH_ENABLED, SewvConfig.AUTO_ENTRENCH_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "autoEntrenchScanRadius", 8.0, 128.0,
                SewvConfig.AUTO_ENTRENCH_SCAN_RADIUS, SewvConfig.AUTO_ENTRENCH_SCAN_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "vehicleFormationSpacing", 5.0, 32.0,
                SewvConfig.VEHICLE_FORMATION_SPACING, SewvConfig.VEHICLE_FORMATION_SPACING::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "vehicleTargetScanRadius", 8.0, 128.0,
                SewvConfig.VEHICLE_TARGET_SCAN_RADIUS, SewvConfig.VEHICLE_TARGET_SCAN_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "vehicleTargetScanHeight", 4.0, 128.0,
                SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT, SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "vehicleTargetScanIntervalTicks", 1, 200,
                SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS, SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "vehicleTargetRequireLineOfSight",
                SewvConfig.VEHICLE_TARGET_REQUIRE_LOS, SewvConfig.VEHICLE_TARGET_REQUIRE_LOS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "vehicleAllyAssistRange", 0.0, 256.0,
                SewvConfig.VEHICLE_ALLY_ASSIST_RANGE, SewvConfig.VEHICLE_ALLY_ASSIST_RANGE::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "stalemateBreakerEnabled",
                SewvConfig.STALEMATE_BREAKER_ENABLED, SewvConfig.STALEMATE_BREAKER_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "stalemateSilenceTicks", 40, 2400,
                SewvConfig.STALEMATE_SILENCE_TICKS, SewvConfig.STALEMATE_SILENCE_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "vehicleTerrainAvoidance",
                SewvConfig.VEHICLE_TERRAIN_AVOIDANCE, SewvConfig.VEHICLE_TERRAIN_AVOIDANCE::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "patrolRotateIntervalTicks", 200, 24000,
                SewvConfig.PATROL_ROTATE_INTERVAL_TICKS, SewvConfig.PATROL_ROTATE_INTERVAL_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "idleWanderEnabled",
                SewvConfig.IDLE_WANDER_ENABLED, SewvConfig.IDLE_WANDER_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "idleWanderRadius", 4, 64,
                SewvConfig.IDLE_WANDER_RADIUS, SewvConfig.IDLE_WANDER_RADIUS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "idleHybridEnabled",
                SewvConfig.IDLE_HYBRID_ENABLED, SewvConfig.IDLE_HYBRID_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleGroupRadius", 8.0, 128.0,
                SewvConfig.IDLE_GROUP_RADIUS, SewvConfig.IDLE_GROUP_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleFormationBaseRadius", 5.0, 40.0,
                SewvConfig.IDLE_FORMATION_BASE_RADIUS, SewvConfig.IDLE_FORMATION_BASE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleFormationRadiusMin", 5.0, 40.0,
                SewvConfig.IDLE_FORMATION_RADIUS_MIN, SewvConfig.IDLE_FORMATION_RADIUS_MIN::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleFormationRadiusMax", 15.0, 80.0,
                SewvConfig.IDLE_FORMATION_RADIUS_MAX, SewvConfig.IDLE_FORMATION_RADIUS_MAX::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleScrambleRadius", 0.0, 16.0,
                SewvConfig.IDLE_SCRAMBLE_RADIUS, SewvConfig.IDLE_SCRAMBLE_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "idleHoldMinTicks", 20, 24000,
                SewvConfig.IDLE_HOLD_MIN_TICKS, SewvConfig.IDLE_HOLD_MIN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "idleHoldMaxTicks", 20, 48000,
                SewvConfig.IDLE_HOLD_MAX_TICKS, SewvConfig.IDLE_HOLD_MAX_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleTravelLeadDistance", 64.0, 2000.0,
                SewvConfig.IDLE_TRAVEL_LEAD_DISTANCE, SewvConfig.IDLE_TRAVEL_LEAD_DISTANCE::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleTravelSpacingMin", 2.0, 32.0,
                SewvConfig.IDLE_TRAVEL_SPACING_MIN, SewvConfig.IDLE_TRAVEL_SPACING_MIN::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleTravelSpacingMax", 2.0, 32.0,
                SewvConfig.IDLE_TRAVEL_SPACING_MAX, SewvConfig.IDLE_TRAVEL_SPACING_MAX::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "idleTravelDetectRadius", 4.0, 64.0,
                SewvConfig.IDLE_TRAVEL_DETECT_RADIUS, SewvConfig.IDLE_TRAVEL_DETECT_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "idleTravelStuckTicks", 100, 6000,
                SewvConfig.IDLE_TRAVEL_STUCK_TICKS, SewvConfig.IDLE_TRAVEL_STUCK_TICKS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "utilityRefreshIntervalTicks", 5, 200,
                SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS, SewvConfig.UTILITY_REFRESH_INTERVAL_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "factionOrganicComms",
                SewvConfig.FACTION_ORGANIC_COMMS, SewvConfig.FACTION_ORGANIC_COMMS::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "supportCallIntervalTicks", 20, 2400,
                SewvConfig.SUPPORT_CALL_INTERVAL_TICKS, SewvConfig.SUPPORT_CALL_INTERVAL_TICKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "outerRingEnabled",
                SewvConfig.OUTER_RING_ENABLED, SewvConfig.OUTER_RING_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "crew_ai", "outerRingMaxBlocks", 96.0, 512.0,
                SewvConfig.OUTER_RING_MAX_BLOCKS, SewvConfig.OUTER_RING_MAX_BLOCKS::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "awarenessCuesEnabled",
                SewvConfig.AWARENESS_CUES_ENABLED, SewvConfig.AWARENESS_CUES_ENABLED::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "coverCacheEnabled",
                SewvConfig.COVER_CACHE_ENABLED, SewvConfig.COVER_CACHE_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "crew_ai", "coverCacheBakeCellsPerTick", 8, 256,
                SewvConfig.COVER_CACHE_BAKE_CELLS_PER_TICK, SewvConfig.COVER_CACHE_BAKE_CELLS_PER_TICK::set);
        b.bool(ConfigScope.SERVER, "crew_ai", "individualTacticsEnabled",
                SewvConfig.INDIVIDUAL_TACTICS_ENABLED, SewvConfig.INDIVIDUAL_TACTICS_ENABLED::set);
    }

    private static void registerCommand(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "command", "commandGroupJoinRadius", 8.0, 256.0,
                SewvConfig.COMMAND_GROUP_JOIN_RADIUS, SewvConfig.COMMAND_GROUP_JOIN_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "command", "commandGroupLeaveRadius", 8.0, 256.0,
                SewvConfig.COMMAND_GROUP_LEAVE_RADIUS, SewvConfig.COMMAND_GROUP_LEAVE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "command", "commandGroupMaxDiameter", 16.0, 512.0,
                SewvConfig.COMMAND_GROUP_MAX_DIAMETER, SewvConfig.COMMAND_GROUP_MAX_DIAMETER::set);
        b.intRange(ConfigScope.SERVER, "command", "commandGroupMinSize", 2, 32,
                SewvConfig.COMMAND_GROUP_MIN_SIZE, SewvConfig.COMMAND_GROUP_MIN_SIZE::set);
        b.intRange(ConfigScope.SERVER, "command", "commandMaxUnits", 4, 256,
                SewvConfig.COMMAND_MAX_UNITS, SewvConfig.COMMAND_MAX_UNITS::set);
        b.doubleRange(ConfigScope.SERVER, "command", "commandEngagementRadius", 16.0, 256.0,
                SewvConfig.COMMAND_ENGAGEMENT_RADIUS, SewvConfig.COMMAND_ENGAGEMENT_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "command", "commandMargin", 0.0, 2.0,
                SewvConfig.COMMAND_MARGIN, SewvConfig.COMMAND_MARGIN::set);
        b.doubleRange(ConfigScope.SERVER, "command", "influenceCellSize", 8.0, 16.0,
                SewvConfig.INFLUENCE_CELL_SIZE, SewvConfig.INFLUENCE_CELL_SIZE::set);
        b.intRange(ConfigScope.SERVER, "command", "influenceMaxCells", 64, 1024,
                SewvConfig.INFLUENCE_MAX_CELLS, SewvConfig.INFLUENCE_MAX_CELLS::set);
        b.intRange(ConfigScope.SERVER, "command", "minPlayTicks", 20, 2400,
                SewvConfig.MIN_PLAY_TICKS, SewvConfig.MIN_PLAY_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "command", "playSwitchMargin", 0.0, 100.0,
                SewvConfig.PLAY_SWITCH_MARGIN, SewvConfig.PLAY_SWITCH_MARGIN::set);
    }

    private static void registerPlatoon(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "platoon", "platoonCohesionRadius", 8.0, 128.0,
                SewvConfig.PLATOON_COHESION_RADIUS, SewvConfig.PLATOON_COHESION_RADIUS::set);
        b.intRange(ConfigScope.SERVER, "platoon", "platoonMaxSize", 2, 12,
                SewvConfig.PLATOON_MAX_SIZE, SewvConfig.PLATOON_MAX_SIZE::set);
        b.intRange(ConfigScope.SERVER, "platoon", "platoonMinSize", 2, 8,
                SewvConfig.PLATOON_MIN_SIZE, SewvConfig.PLATOON_MIN_SIZE::set);
    }

    private static void registerFlight(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "flight", "heliEngageRadius", 12.0, 64.0,
                SewvConfig.HELI_ENGAGE_RADIUS, SewvConfig.HELI_ENGAGE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "heliMaxDepressionDeg", 20.0, 55.0,
                SewvConfig.HELI_MAX_DEPRESSION_DEG, SewvConfig.HELI_MAX_DEPRESSION_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "heliMinStandoff", 16.0, 96.0,
                SewvConfig.HELI_MIN_STANDOFF, SewvConfig.HELI_MIN_STANDOFF::set);
        b.bool(ConfigScope.SERVER, "flight", "heliChunkLoading",
                SewvConfig.HELI_CHUNK_LOADING, SewvConfig.HELI_CHUNK_LOADING::set);
        b.bool(ConfigScope.SERVER, "flight", "planeChunkLoading",
                SewvConfig.PLANE_CHUNK_LOADING, SewvConfig.PLANE_CHUNK_LOADING::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeCommandRadius", 32.0, 4096.0,
                SewvConfig.PLANE_COMMAND_RADIUS, SewvConfig.PLANE_COMMAND_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeGunConeDeg", 1.0, 45.0,
                SewvConfig.PLANE_GUN_CONE_DEG, SewvConfig.PLANE_GUN_CONE_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeMissileConeDeg", 1.0, 45.0,
                SewvConfig.PLANE_MISSILE_CONE_DEG, SewvConfig.PLANE_MISSILE_CONE_DEG::set);
        b.intRange(ConfigScope.SERVER, "flight", "planeMissileLockTicks", 0, 200,
                SewvConfig.PLANE_MISSILE_LOCK_TICKS, SewvConfig.PLANE_MISSILE_LOCK_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeMinConeDeg", 0.5, 20.0,
                SewvConfig.PLANE_MIN_CONE_DEG, SewvConfig.PLANE_MIN_CONE_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeAutoRocketRange", 0.0, 320.0,
                SewvConfig.PLANE_AUTO_ROCKET_RANGE, SewvConfig.PLANE_AUTO_ROCKET_RANGE::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeAutoHeavyRange", 0.0, 1024.0,
                SewvConfig.PLANE_AUTO_HEAVY_RANGE, SewvConfig.PLANE_AUTO_HEAVY_RANGE::set);
        b.intRange(ConfigScope.SERVER, "flight", "planeBombStick", 1, 12,
                SewvConfig.PLANE_BOMB_STICK, SewvConfig.PLANE_BOMB_STICK::set);
        b.intRange(ConfigScope.SERVER, "flight", "planeBombStickIntervalTicks", 1, 40,
                SewvConfig.PLANE_BOMB_STICK_INTERVAL, SewvConfig.PLANE_BOMB_STICK_INTERVAL::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeBombSightRadius", 1.0, 32.0,
                SewvConfig.PLANE_BOMB_SIGHT_RADIUS, SewvConfig.PLANE_BOMB_SIGHT_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeEngageRadius", 32.0, 1024.0,
                SewvConfig.PLANE_ENGAGE_RADIUS, SewvConfig.PLANE_ENGAGE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeAttackRunLength", 40.0, 1024.0,
                SewvConfig.PLANE_ATTACK_RUN_LENGTH, SewvConfig.PLANE_ATTACK_RUN_LENGTH::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeMaxAltitude", 64.0, 1024.0,
                SewvConfig.PLANE_MAX_ALTITUDE, SewvConfig.PLANE_MAX_ALTITUDE::set);
        b.bool(ConfigScope.SERVER, "flight", "planeDiveSnap",
                SewvConfig.PLANE_DIVE_SNAP, SewvConfig.PLANE_DIVE_SNAP::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeLandTransitAgl", 16.0, 160.0,
                SewvConfig.PLANE_LAND_TRANSIT_AGL, SewvConfig.PLANE_LAND_TRANSIT_AGL::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeLandFlareAgl", 2.0, 32.0,
                SewvConfig.PLANE_LAND_FLARE_AGL, SewvConfig.PLANE_LAND_FLARE_AGL::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeLandFlareRadius", 8.0, 96.0,
                SewvConfig.PLANE_LAND_FLARE_RADIUS, SewvConfig.PLANE_LAND_FLARE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "planeLandSettleRadius", 4.0, 64.0,
                SewvConfig.PLANE_LAND_SETTLE_RADIUS, SewvConfig.PLANE_LAND_SETTLE_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportMinAspectRatio", 1.5, 20.0,
                SewvConfig.AIRPORT_MIN_ASPECT_RATIO, SewvConfig.AIRPORT_MIN_ASPECT_RATIO::set);
        b.intRange(ConfigScope.SERVER, "flight", "airportMinLengthBlocks", 32, 512,
                SewvConfig.AIRPORT_MIN_LENGTH_BLOCKS, SewvConfig.AIRPORT_MIN_LENGTH_BLOCKS::set);
        b.intRange(ConfigScope.SERVER, "flight", "airportMaxAreaBlocks", 1024, 1048576,
                SewvConfig.AIRPORT_MAX_AREA_BLOCKS, SewvConfig.AIRPORT_MAX_AREA_BLOCKS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportLandingSearchRadius", 0.0, 16384.0,
                SewvConfig.AIRPORT_LANDING_SEARCH_RADIUS, SewvConfig.AIRPORT_LANDING_SEARCH_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportAlignmentDistance", 60.0, 512.0,
                SewvConfig.AIRPORT_ALIGNMENT_DISTANCE, SewvConfig.AIRPORT_ALIGNMENT_DISTANCE::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportAlignmentSnapRadius", 8.0, 256.0,
                SewvConfig.AIRPORT_ALIGNMENT_SNAP_RADIUS, SewvConfig.AIRPORT_ALIGNMENT_SNAP_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "dubinsAlignToleranceDeg", 5.0, 45.0,
                SewvConfig.DUBINS_ALIGN_TOLERANCE_DEG, SewvConfig.DUBINS_ALIGN_TOLERANCE_DEG::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "dubinsFallbackMultiplier", 1.5, 4.0,
                SewvConfig.DUBINS_FALLBACK_MULTIPLIER, SewvConfig.DUBINS_FALLBACK_MULTIPLIER::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "dubinsDeviationThreshold", 4.0, 64.0,
                SewvConfig.DUBINS_DEVIATION_THRESHOLD, SewvConfig.DUBINS_DEVIATION_THRESHOLD::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportSlotSizeFactor", 0.02, 0.5,
                SewvConfig.AIRPORT_SLOT_SIZE_FACTOR, SewvConfig.AIRPORT_SLOT_SIZE_FACTOR::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportSlotBufferFactor", 0.0, 0.2,
                SewvConfig.AIRPORT_SLOT_BUFFER_FACTOR, SewvConfig.AIRPORT_SLOT_BUFFER_FACTOR::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportExtraTakeoffFactor", 0.0, 0.5,
                SewvConfig.AIRPORT_EXTRA_TAKEOFF_FACTOR, SewvConfig.AIRPORT_EXTRA_TAKEOFF_FACTOR::set);
        b.doubleRange(ConfigScope.SERVER, "flight", "airportTaxiSpeed", 0.02, 1.0,
                SewvConfig.AIRPORT_TAXI_SPEED, SewvConfig.AIRPORT_TAXI_SPEED::set);
    }

    private static void registerIndirectFire(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "indirect_fire", "mortarUseDistance", 1.0, 6.0,
                SewvConfig.MORTAR_USE_DISTANCE, SewvConfig.MORTAR_USE_DISTANCE::set);
        b.intRange(ConfigScope.SERVER, "indirect_fire", "mortarFireCooldownTicks", 1, 1200,
                SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS, SewvConfig.MORTAR_FIRE_COOLDOWN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "indirect_fire", "type63FireCooldownTicks", 1, 1200,
                SewvConfig.TYPE63_FIRE_COOLDOWN_TICKS, SewvConfig.TYPE63_FIRE_COOLDOWN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "indirect_fire", "mortarDispersionRadius", 0, 16,
                SewvConfig.MORTAR_DISPERSION_RADIUS, SewvConfig.MORTAR_DISPERSION_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "indirect_fire", "friendlyFireMortarRadius", 0.0, 48.0,
                SewvConfig.FRIENDLY_FIRE_MORTAR_RADIUS, SewvConfig.FRIENDLY_FIRE_MORTAR_RADIUS::set);
        b.bool(ConfigScope.SERVER, "indirect_fire", "mortarRequiresAmmo",
                SewvConfig.MORTAR_REQUIRES_AMMO, SewvConfig.MORTAR_REQUIRES_AMMO::set);
        b.bool(ConfigScope.SERVER, "indirect_fire", "mortarChunkLoading",
                SewvConfig.MORTAR_CHUNK_LOADING, SewvConfig.MORTAR_CHUNK_LOADING::set);
        b.bool(ConfigScope.SERVER, "indirect_fire", "artilleryChunkLoading",
                SewvConfig.ARTILLERY_CHUNK_LOADING, SewvConfig.ARTILLERY_CHUNK_LOADING::set);
        b.doubleRange(ConfigScope.SERVER, "indirect_fire", "mortarRadioRange", 16.0, 2048.0,
                SewvConfig.MORTAR_RADIO_RANGE, SewvConfig.MORTAR_RADIO_RANGE::set);
    }

    private static void registerVoicelines(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "voicelines", "vehicleVoicelinesEnabled",
                SewvConfig.VEHICLE_VOICELINES_ENABLED, SewvConfig.VEHICLE_VOICELINES_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "voicelines", "idleVoicelineDelayTicks", 20, 12000,
                SewvConfig.IDLE_VOICELINE_DELAY_TICKS, SewvConfig.IDLE_VOICELINE_DELAY_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "voicelines", "idleVoicelineHealthFraction", 0.0, 1.0,
                SewvConfig.IDLE_VOICELINE_HEALTH_FRACTION, SewvConfig.IDLE_VOICELINE_HEALTH_FRACTION::set);
    }

    private static void registerOrders(ConfigRegistry.Builder b) {
        b.intRange(ConfigScope.SERVER, "orders", "targetVetoCooldownTicks", 20, 12000,
                SewvConfig.TARGET_VETO_COOLDOWN_TICKS, SewvConfig.TARGET_VETO_COOLDOWN_TICKS::set);
    }

    private static void registerBoarding(ConfigRegistry.Builder b) {
        b.doubleRange(ConfigScope.SERVER, "boarding", "boardScanRadius", 8.0, 128.0,
                SewvConfig.BOARD_SCAN_RADIUS, SewvConfig.BOARD_SCAN_RADIUS::set);
    }

    private static void registerMapIntel(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "map_intel", "mapInfantryEnabled",
                SewvConfig.MAP_INFANTRY_ENABLED, SewvConfig.MAP_INFANTRY_ENABLED::set);
        b.intRange(ConfigScope.SERVER, "map_intel", "mapSyncIntervalTicks", 5, 200,
                SewvConfig.MAP_SYNC_INTERVAL_TICKS, SewvConfig.MAP_SYNC_INTERVAL_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "map_intel", "mapSpotRadius", 0.0, 512.0,
                SewvConfig.MAP_SPOT_RADIUS, SewvConfig.MAP_SPOT_RADIUS::set);
    }

    private static void registerSweep(ConfigRegistry.Builder b) {
        b.intRange(ConfigScope.SERVER, "sweep", "quietSeconds", 1, 300,
                SewvConfig.SWEEP_QUIET_SECONDS, SewvConfig.SWEEP_QUIET_SECONDS::set);
        b.intRange(ConfigScope.SERVER, "sweep", "maxChunkArea", 1, 1024,
                SewvConfig.SWEEP_MAX_CHUNK_AREA, SewvConfig.SWEEP_MAX_CHUNK_AREA::set);
    }

    private static void registerInvasion(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "invasion", "unlimitedTeamBases",
                SewvConfig.UNLIMITED_TEAM_BASES, SewvConfig.UNLIMITED_TEAM_BASES::set);
        b.hexColor(ConfigScope.SERVER, "invasion", "hudTeamAColor",
                SewvConfig.INVASION_HUD_TEAM_A_COLOR, SewvConfig.INVASION_HUD_TEAM_A_COLOR::set);
        b.hexColor(ConfigScope.SERVER, "invasion", "hudTeamBColor",
                SewvConfig.INVASION_HUD_TEAM_B_COLOR, SewvConfig.INVASION_HUD_TEAM_B_COLOR::set);
        b.hexColor(ConfigScope.SERVER, "invasion", "hudNeutralColor",
                SewvConfig.INVASION_HUD_NEUTRAL_COLOR, SewvConfig.INVASION_HUD_NEUTRAL_COLOR::set);
    }

    private static void registerDoctrine(ConfigRegistry.Builder b) {
        for (int f = 0; f < DOCTRINE_FACTIONS.length; f++) {
            String faction = DOCTRINE_FACTIONS[f];
            for (Doctrine.Axis axis : Doctrine.Axis.VALUES) {
                int fi = f;
                int ai = axis.ordinal();
                String key = "doctrine." + faction + "." + axis.key;
                b.intRange(ConfigScope.SERVER, "doctrine", key, -Doctrine.AXIS_LIMIT, Doctrine.AXIS_LIMIT,
                        SewvConfig.DOCTRINE[fi][ai], SewvConfig.DOCTRINE[fi][ai]::set);
            }
        }
    }

    private static void registerBallistics(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "ballistics", "tacZBallisticTranslationEnabled",
                SewvConfig.TACZ_BALLISTIC_TRANSLATION_ENABLED, SewvConfig.TACZ_BALLISTIC_TRANSLATION_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "ballistics", "tacZBallisticGlobalScale", 0.0, 10.0,
                SewvConfig.TACZ_BALLISTIC_GLOBAL_SCALE, SewvConfig.TACZ_BALLISTIC_GLOBAL_SCALE::set);
    }

    private static void registerCompatExtermination(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "compat_extermination", "tripodShieldEnabled",
                SewvConfig.TRIPOD_SHIELD_ENABLED, SewvConfig.TRIPOD_SHIELD_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "compat_extermination", "tripodHpMultiplier", 1.0, 10.0,
                SewvConfig.TRIPOD_HP_MULTIPLIER, SewvConfig.TRIPOD_HP_MULTIPLIER::set);
        b.doubleRange(ConfigScope.SERVER, "compat_extermination", "tripodShieldBreakDamage", 1.0, 100000.0,
                SewvConfig.TRIPOD_SHIELD_BREAK_DAMAGE, SewvConfig.TRIPOD_SHIELD_BREAK_DAMAGE::set);
        b.intRange(ConfigScope.SERVER, "compat_extermination", "tripodShieldRegenTicks", 0, 72000,
                SewvConfig.TRIPOD_SHIELD_REGEN_TICKS, SewvConfig.TRIPOD_SHIELD_REGEN_TICKS::set);
        b.intRange(ConfigScope.SERVER, "compat_extermination", "tripodShieldFlareTicks", 1, 100,
                SewvConfig.TRIPOD_SHIELD_FLARE_TICKS, SewvConfig.TRIPOD_SHIELD_FLARE_TICKS::set);
        b.doubleRange(ConfigScope.SERVER, "compat_extermination", "tripodShieldAxisScale", 0.25, 4.0,
                SewvConfig.TRIPOD_SHIELD_AXIS_SCALE, SewvConfig.TRIPOD_SHIELD_AXIS_SCALE::set);
        b.doubleRange(ConfigScope.SERVER, "compat_extermination", "invasionPodAvoidRadius", 8.0, 128.0,
                SewvConfig.INVASION_POD_AVOID_RADIUS, SewvConfig.INVASION_POD_AVOID_RADIUS::set);
        b.doubleRange(ConfigScope.SERVER, "compat_extermination", "heatRaySpeed", 3.5, 40.0,
                SewvConfig.HEAT_RAY_SPEED, SewvConfig.HEAT_RAY_SPEED::set);
    }

    private static void registerCompatTrees(ConfigRegistry.Builder b) {
        b.bool(ConfigScope.SERVER, "compat_trees", "vehicleTreeFellingEnabled",
                SewvConfig.VEHICLE_TREE_FELLING_ENABLED, SewvConfig.VEHICLE_TREE_FELLING_ENABLED::set);
        b.doubleRange(ConfigScope.SERVER, "compat_trees", "vehicleTreeFellDamage", 0.0, 20.0,
                SewvConfig.VEHICLE_TREE_FELL_DAMAGE, SewvConfig.VEHICLE_TREE_FELL_DAMAGE::set);
        b.doubleRange(ConfigScope.SERVER, "compat_trees", "vehicleTreePathMalus", 0.0, 50.0,
                SewvConfig.VEHICLE_TREE_PATH_MALUS, SewvConfig.VEHICLE_TREE_PATH_MALUS::set);
        b.doubleRange(ConfigScope.SERVER, "compat_trees", "vehicleTreeSensorDanger", 0.0, 0.99,
                SewvConfig.VEHICLE_TREE_SENSOR_DANGER, SewvConfig.VEHICLE_TREE_SENSOR_DANGER::set);
        b.bool(ConfigScope.SERVER, "compat_trees", "vehicleTreeFellingExemptGiantTrunks",
                SewvConfig.VEHICLE_TREE_FELLING_EXEMPT_GIANT_TRUNKS, SewvConfig.VEHICLE_TREE_FELLING_EXEMPT_GIANT_TRUNKS::set);
        b.intRange(ConfigScope.SERVER, "compat_trees", "vehicleTreeContactTicks", 0, 200,
                SewvConfig.VEHICLE_TREE_CONTACT_TICKS, SewvConfig.VEHICLE_TREE_CONTACT_TICKS::set);
    }
}
