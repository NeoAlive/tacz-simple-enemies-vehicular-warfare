package com.neoalive.tacz_sewv.config;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.List;

public final class SewvConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue TANKS_IN_EVENTS;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_RU;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_US;
    public static final ForgeConfigSpec.BooleanValue PLANES_IN_EVENTS;
    public static final ForgeConfigSpec.DoubleValue PLANE_SPAWN_CHANCE_RU;
    public static final ForgeConfigSpec.DoubleValue PLANE_SPAWN_CHANCE_US;
    public static final ForgeConfigSpec.BooleanValue CONVOY_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue CONVOY_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue CONVOY_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue LARGE_COMBAT_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue LARGE_COMBAT_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue LARGE_COMBAT_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue LARGE_COMBAT_VEHICLES;
    public static final ForgeConfigSpec.DoubleValue LARGE_COMBAT_EMPLACEMENT_CHANCE;
    public static final ForgeConfigSpec.DoubleValue LARGE_COMBAT_PLANE_CHANCE;
    public static final ForgeConfigSpec.BooleanValue NAVAL_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue NAVAL_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue NAVAL_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue NAVAL_SHIPS_PER_SIDE;
    public static final ForgeConfigSpec.BooleanValue INVASION_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue INVASION_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue INVASION_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue INVASION_DEFENDER_INFANTRY;
    public static final ForgeConfigSpec.IntValue INVASION_DEFENDER_TOWS;
    public static final ForgeConfigSpec.IntValue INVASION_DEFENDER_MORTARS;
    public static final ForgeConfigSpec.BooleanValue SHELLING_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SHELLING_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SHELLING_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue SHELLING_BASE_RADIUS;
    public static final ForgeConfigSpec.IntValue SHELLING_MORTARS;
    public static final ForgeConfigSpec.IntValue SHELLING_GUARDS;
    public static final ForgeConfigSpec.IntValue SHELLING_DURATION_MIN_TICKS;
    public static final ForgeConfigSpec.IntValue SHELLING_DURATION_MAX_TICKS;
    public static final ForgeConfigSpec.ConfigValue<String> HIGH_CHANCE_MORTAR_SHELL;
    public static final ForgeConfigSpec.ConfigValue<String> LOW_CHANCE_MORTAR_SHELL;
    public static final ForgeConfigSpec.BooleanValue DERELICT_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue DERELICT_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DERELICT_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue DERELICT_HEALTH_FRACTION;
    public static final ForgeConfigSpec.IntValue DERELICT_GUARDS;
    public static final ForgeConfigSpec.IntValue DERELICT_AMMO_COUNT;
    public static final ForgeConfigSpec.BooleanValue GARRISON_VEHICLES_ENABLED;
    public static final ForgeConfigSpec.DoubleValue GARRISON_VEHICLE_CHANCE;

    public static final ForgeConfigSpec.BooleanValue CREATIVE_AMMO_FALLBACK;
    public static final ForgeConfigSpec.BooleanValue FACTION_INFINITE_ENERGY;
    public static final ForgeConfigSpec.BooleanValue FACTION_INFINITE_AMMO;

    public static final ForgeConfigSpec.BooleanValue NPC_ARMOR_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_ARMOR;

    public static final ForgeConfigSpec.BooleanValue STRUCTURE_VEHICLES_ENABLED;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_MAX_COUNT;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_RAMP_DAYS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_VEHICLE_STRUCTURES;

    public static final ForgeConfigSpec.IntValue AI_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue AI_FIRE_ASSIST_CONE_DEG;
    public static final ForgeConfigSpec.DoubleValue SMOKE_BLOCK_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<String> AI_AIM_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue AI_AIM_SPREAD_DEG;
    public static final ForgeConfigSpec.BooleanValue IFV_DISMOUNTS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue SEM_CREW_DISABLE_INERTIA_ROTATE;
    public static final ForgeConfigSpec.BooleanValue TANK_RIDER_DISMOUNT_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> AT_WEAPON_RU;
    public static final ForgeConfigSpec.ConfigValue<String> AT_WEAPON_US;
    public static final ForgeConfigSpec.DoubleValue AT_SECOND_GUNNER_CHANCE;
    public static final ForgeConfigSpec.IntValue AT_BACKUP_AMMO;
    public static final ForgeConfigSpec.DoubleValue AT_ENGAGE_RANGE;
    public static final ForgeConfigSpec.BooleanValue MEDIC_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MEDIC_SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue MEDIC_HEAL_PER_TREAT;
    public static final ForgeConfigSpec.BooleanValue HEALTH_MOBILITY_ENABLED;
    public static final ForgeConfigSpec.DoubleValue HEALTH_MOBILITY_FLOOR;
    public static final ForgeConfigSpec.DoubleValue MEDIC_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SUPPORT_DEDUPE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_REPAIR_PER_TREAT;
    public static final ForgeConfigSpec.IntValue ENGINEER_REPAIR_COOLDOWN;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENGINEER_SIDEARM_POOL;
    public static final ForgeConfigSpec.IntValue DRONE_MAX_PER_ENGINEER;
    public static final ForgeConfigSpec.IntValue DRONE_DEPLOY_CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue DRONE_DEPLOY_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DRONE_SCAN_ALTITUDE;
    public static final ForgeConfigSpec.DoubleValue DRONE_DETECTION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue DRONE_BROADCAST_RADIUS;
    public static final ForgeConfigSpec.IntValue DRONE_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_MIN_HEALTH_FRACTION;
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_STEALS_PLAYER_VEHICLES;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_FORMATION_SPACING;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_HEIGHT;
    public static final ForgeConfigSpec.IntValue VEHICLE_TARGET_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TARGET_REQUIRE_LOS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_ALLY_ASSIST_RANGE;
    public static final ForgeConfigSpec.BooleanValue STALEMATE_BREAKER_ENABLED;
    public static final ForgeConfigSpec.IntValue STALEMATE_SILENCE_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TERRAIN_AVOIDANCE;
    public static final ForgeConfigSpec.IntValue PATROL_ROTATE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue IDLE_WANDER_ENABLED;
    public static final ForgeConfigSpec.IntValue IDLE_WANDER_RADIUS;
    public static final ForgeConfigSpec.IntValue UTILITY_REFRESH_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue FACTION_ORGANIC_COMMS;
    public static final ForgeConfigSpec.IntValue SUPPORT_CALL_INTERVAL_TICKS;

    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_JOIN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_LEAVE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_MAX_DIAMETER;
    public static final ForgeConfigSpec.IntValue COMMAND_GROUP_MIN_SIZE;
    public static final ForgeConfigSpec.IntValue COMMAND_MAX_UNITS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_ENGAGEMENT_RADIUS;

    public static final ForgeConfigSpec.DoubleValue HELI_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.BooleanValue HELI_CHUNK_LOADING;
    public static final ForgeConfigSpec.BooleanValue PLANE_CHUNK_LOADING;
    public static final ForgeConfigSpec.DoubleValue PLANE_COMMAND_RADIUS;

    public static final ForgeConfigSpec.DoubleValue MORTAR_USE_DISTANCE;
    public static final ForgeConfigSpec.IntValue MORTAR_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue MORTAR_DISPERSION_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MORTAR_REQUIRES_AMMO;
    public static final ForgeConfigSpec.BooleanValue MORTAR_CHUNK_LOADING;
    public static final ForgeConfigSpec.DoubleValue MORTAR_RADIO_RANGE;

    public static final ForgeConfigSpec.BooleanValue VEHICLE_VOICELINES_ENABLED;
    public static final ForgeConfigSpec.IntValue IDLE_VOICELINE_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue IDLE_VOICELINE_HEALTH_FRACTION;

    public static final ForgeConfigSpec.DoubleValue BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MAP_INFANTRY_ENABLED;
    public static final ForgeConfigSpec.IntValue MAP_SYNC_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue MAP_SPOT_RADIUS;

    public static final ForgeConfigSpec.IntValue[][] DOCTRINE;

    private static final String[] FACTION_KEYS = {"ru", "us", "pmc"};
    private static final int[][] DOCTRINE_DEFAULTS = {
            {2, -1, 2, -1, 1, 0, -2, 2},
            {0, 2, 1, 2, 3, 1, 2, -1},
            {0, 0, 0, 0, 0, 0, 0, 0},
    };

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("events");
        TANKS_IN_EVENTS = builder.comment("Allow rare RU/US tanks in combat events.").define("tanksInEvents", true);
        TANK_SPAWN_CHANCE_RU = builder.comment("RU tank chance when SEM's far_combat event fires.")
                .defineInRange("tankSpawnChanceRu", 0.12, 0.0, 1.0);
        TANK_SPAWN_CHANCE_US = builder.comment("US tank chance when SEM's far_combat event fires.")
                .defineInRange("tankSpawnChanceUs", 0.12, 0.0, 1.0);
        PLANES_IN_EVENTS = builder.comment("Allow rare RU/US planes in combat events.").define("planesInEvents", true);
        PLANE_SPAWN_CHANCE_RU = builder.comment("RU plane chance when SEM's far_combat event fires.")
                .defineInRange("planeSpawnChanceRu", 0.02, 0.0, 1.0);
        PLANE_SPAWN_CHANCE_US = builder.comment("US plane chance when SEM's far_combat event fires.")
                .defineInRange("planeSpawnChanceUs", 0.02, 0.0, 1.0);
        CONVOY_EVENTS_ENABLED = builder.comment("Enable convoy events.").define("convoyEventsEnabled", true);
        CONVOY_BASE_CHANCE = builder.comment("Base convoy event chance per SEM event roll.")
                .defineInRange("convoyBaseChance", 0.06, 0.0, 1.0);
        CONVOY_FAILURE_MULTIPLIER = builder.comment("Convoy chance added after a missed roll.")
                .defineInRange("convoyFailureMultiplier", 0.06, 0.0, 1.0);
        LARGE_COMBAT_EVENTS_ENABLED = builder.comment("Enable large combat events.").define("largeCombatEventsEnabled", true);
        LARGE_COMBAT_BASE_CHANCE = builder.comment("Base large-combat event chance per SEM event roll.")
                .defineInRange("largeCombatBaseChance", 0.02, 0.0, 1.0);
        LARGE_COMBAT_FAILURE_MULTIPLIER = builder.comment("Large-combat chance added after a missed roll.")
                .defineInRange("largeCombatFailureMultiplier", 0.02, 0.0, 1.0);
        LARGE_COMBAT_VEHICLES = builder.comment("Vehicles each side gets in large combat.")
                .defineInRange("largeCombatVehicles", 2, 0, 8);
        LARGE_COMBAT_EMPLACEMENT_CHANCE = builder.comment("Per-side chance to add a mortar or TOW to large combat.")
                .defineInRange("largeCombatEmplacementChance", 0.04, 0.0, 1.0);
        LARGE_COMBAT_PLANE_CHANCE = builder.comment("Per-side chance to add a plane to large combat.")
                .defineInRange("largeCombatPlaneChance", 0.03, 0.0, 1.0);
        NAVAL_EVENTS_ENABLED = builder.comment("Enable naval battle events.").define("navalEventsEnabled", true);
        NAVAL_BASE_CHANCE = builder.comment("Base naval event chance per SEM event roll.")
                .defineInRange("navalBaseChance", 0.05, 0.0, 1.0);
        NAVAL_FAILURE_MULTIPLIER = builder.comment("Naval chance added after a missed roll.")
                .defineInRange("navalFailureMultiplier", 0.05, 0.0, 1.0);
        NAVAL_SHIPS_PER_SIDE = builder.comment("Ships each side gets in a naval battle.")
                .defineInRange("navalShipsPerSide", 4, 1, 12);
        INVASION_EVENTS_ENABLED = builder.comment("Enable asymmetric invasion events.").define("invasionEventsEnabled", true);
        INVASION_BASE_CHANCE = builder.comment("Base invasion event chance per SEM event roll.")
                .defineInRange("invasionBaseChance", 0.03, 0.0, 1.0);
        INVASION_FAILURE_MULTIPLIER = builder.comment("Invasion chance added after a missed roll.")
                .defineInRange("invasionFailureMultiplier", 0.03, 0.0, 1.0);
        INVASION_DEFENDER_INFANTRY = builder.comment("Defending infantry in an invasion.")
                .defineInRange("invasionDefenderInfantry", 6, 0, 32);
        INVASION_DEFENDER_TOWS = builder.comment("Defending TOW emplacements in an invasion.")
                .defineInRange("invasionDefenderTows", 2, 0, 8);
        INVASION_DEFENDER_MORTARS = builder.comment("Defending mortars in an invasion.")
                .defineInRange("invasionDefenderMortars", 2, 0, 8);
        SHELLING_EVENTS_ENABLED = builder.comment("Enable mortar shelling events.").define("shellingEventsEnabled", true);
        SHELLING_BASE_CHANCE = builder.comment("Base shelling event chance per SEM event roll.")
                .defineInRange("shellingBaseChance", 0.05, 0.0, 1.0);
        SHELLING_FAILURE_MULTIPLIER = builder.comment("Shelling chance added after a missed roll.")
                .defineInRange("shellingFailureMultiplier", 0.04, 0.0, 1.0);
        SHELLING_BASE_RADIUS = builder.comment("How close a player must be to their respawn point for shelling.")
                .defineInRange("shellingBaseRadius", 48, 8, 256);
        SHELLING_MORTARS = builder.comment("Mortars in a shelling battery.")
                .defineInRange("shellingMortars", 2, 1, 6);
        SHELLING_GUARDS = builder.comment("Infantry guarding a shelling battery.")
                .defineInRange("shellingGuards", 4, 0, 12);
        SHELLING_DURATION_MIN_TICKS = builder.comment("Minimum shelling duration.")
                .defineInRange("shellingDurationMinTicks", 600, 20, 24000);
        SHELLING_DURATION_MAX_TICKS = builder.comment("Maximum shelling duration.")
                .defineInRange("shellingDurationMaxTicks", 1200, 20, 24000);
        HIGH_CHANCE_MORTAR_SHELL = builder.comment("Common shell used by spawned mortar crews.")
                .define("highChanceMortarShell", "superbwarfare:mortar_shell", SewvConfig::isValidResourceId);
        LOW_CHANCE_MORTAR_SHELL = builder.comment("Rare shell used by spawned mortar crews.")
                .define("lowChanceMortarShell", "superbwarfare:mortar_shell_wp", SewvConfig::isValidResourceId);
        DERELICT_EVENTS_ENABLED = builder.comment("Enable derelict vehicle events.").define("derelictEventsEnabled", true);
        DERELICT_BASE_CHANCE = builder.comment("Base derelict event chance per SEM event roll.")
                .defineInRange("derelictBaseChance", 0.05, 0.0, 1.0);
        DERELICT_FAILURE_MULTIPLIER = builder.comment("Derelict chance added after a missed roll.")
                .defineInRange("derelictFailureMultiplier", 0.05, 0.0, 1.0);
        DERELICT_HEALTH_FRACTION = builder.comment("Health fraction for spawned derelict hulls. Keep below autoBoardMinHealthFraction.")
                .defineInRange("derelictHealthFraction", 0.15, 0.01, 1.0);
        DERELICT_GUARDS = builder.comment("Maximum survivors around a derelict hull.")
                .defineInRange("derelictGuards", 4, 1, 12);
        DERELICT_AMMO_COUNT = builder.comment("Ammo left in a derelict hull.")
                .defineInRange("derelictAmmoCount", 2, 0, 64);
        GARRISON_VEHICLES_ENABLED = builder.comment("Let village garrisons field one crewed tank.")
                .define("garrisonVehiclesEnabled", true);
        GARRISON_VEHICLE_CHANCE = builder.comment("Chance that a village garrison gets its tank.")
                .defineInRange("garrisonVehicleChance", 0.5, 0.0, 1.0);
        builder.pop();

        builder.push("resources");
        CREATIVE_AMMO_FALLBACK = builder.comment("Use a creative ammo box when finite vehicle ammo cannot be determined.")
                .define("creativeAmmoFallback", true);
        FACTION_INFINITE_ENERGY = builder.comment("RU/US-crewed vehicles never run out of energy.")
                .define("factionInfiniteEnergy", true);
        FACTION_INFINITE_AMMO = builder.comment("RU/US-crewed vehicles use unlimited ammo.")
                .define("factionInfiniteAmmo", true);
        builder.pop();

        builder.push("npc_armor");
        NPC_ARMOR_ENABLED = builder.comment("Issue armor to spawned SEM units.")
                .define("npcArmorEnabled", true);
        RU_ARMOR = builder.comment("Armor item ids for RU units.")
                .defineList("ruArmor", List.of("superbwarfare:ru_helmet_6b47", "superbwarfare:ru_chest_6b43"), SewvConfig::isValidResourceId);
        US_ARMOR = builder.comment("Armor item ids for US units.")
                .defineList("usArmor", List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"), SewvConfig::isValidResourceId);
        PMC_ARMOR = builder.comment("Armor item ids for PMC units.")
                .defineList("pmcArmor", List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"), SewvConfig::isValidResourceId);
        builder.pop();

        builder.push("structure_vehicles");
        STRUCTURE_VEHICLES_ENABLED = builder.comment("Spawn vehicles at compatible Berezka structures.")
                .define("structureVehiclesEnabled", true);
        STRUCTURE_VEHICLE_MAX_COUNT = builder.comment("Maximum vehicles a structure can field.")
                .defineInRange("structureVehicleMaxCount", 5, 1, 16);
        STRUCTURE_VEHICLE_RAMP_DAYS = builder.comment("World days to ramp structure vehicle counts from 1 to the max.")
                .defineInRange("structureVehicleRampDays", 24, 0, 1000);
        RU_VEHICLE_STRUCTURES = builder.comment("Structure ids that field RU vehicles.")
                .defineList("ruVehicleStructures", List.of("russian_army_structures:tank"), SewvConfig::isValidResourceId);
        US_VEHICLE_STRUCTURES = builder.comment("Structure ids that field US vehicles.")
                .defineList("usVehicleStructures", List.of("us_army_structures:convoy"), SewvConfig::isValidResourceId);
        PMC_VEHICLE_STRUCTURES = builder.comment("Structure ids that field PMC vehicles.")
                .defineList("pmcVehicleStructures", List.of("pmc_structures:buggy"), SewvConfig::isValidResourceId);
        builder.pop();

        builder.push("crew_ai");
        AI_FIRE_COOLDOWN_TICKS = builder.comment("Minimum delay between AI vehicle shots.")
                .defineInRange("aiFireCooldownTicks", 5, 1, 200);
        AI_FIRE_ASSIST_CONE_DEG = builder.comment("How far off target an AI crew may still fire.")
                .defineInRange("aiFireAssistConeDeg", 12.0, 4.0, 30.0);
        SMOKE_BLOCK_RADIUS = builder.comment("How close smoke must be to block AI fire.")
                .defineInRange("smokeBlockRadius", 6.0, 1.0, 16.0);
        AI_AIM_ACCURACY = builder.comment("AI vehicle accuracy mode: realistic, scaled, or accurate.")
                .defineInList("aiAimAccuracy", "realistic", Arrays.asList("realistic", "scaled", "accurate"));
        AI_AIM_SPREAD_DEG = builder.comment("Extra dispersion added in realistic/scaled aim modes.")
                .defineInRange("aiAimSpreadDegrees", 1.0, 0.0, 30.0);
        IFV_DISMOUNTS_ENABLED = builder.comment("Let IFVs dismount squads against armor.")
                .define("ifvDismountsEnabled", true);
        SEM_CREW_DISABLE_INERTIA_ROTATE = builder.comment("Disable chassis bank while SEM units drive.")
                .define("semCrewDisableInertiaRotate", true);
        TANK_RIDER_DISMOUNT_ENABLED = builder.comment("Let exposed climb-seat riders dismount in combat.")
                .define("tankRiderDismountEnabled", true);
        AT_WEAPON_RU = builder.comment("Launcher given to RU anti-tank dismounts.")
                .define("atWeaponRu", "superbwarfare:rpg");
        AT_WEAPON_US = builder.comment("Launcher given to US anti-tank dismounts.")
                .define("atWeaponUs", "superbwarfare:javelin");
        AT_SECOND_GUNNER_CHANCE = builder.comment("Chance to give a squad a second anti-tank gunner.")
                .defineInRange("atSecondGunnerChance", 0.5, 0.0, 1.0);
        AT_BACKUP_AMMO = builder.comment("Issued rockets or missiles per anti-tank gunner.")
                .defineInRange("atBackupAmmo", 8, 1, 64);
        AT_ENGAGE_RANGE = builder.comment("Maximum anti-tank firing range.")
                .defineInRange("atEngageRange", 48.0, 8.0, 200.0);
        MEDIC_ENABLED = builder.comment("Allow PMC medics to use medical kits out of combat.")
                .define("medicEnabled", true);
        MEDIC_SEARCH_RADIUS = builder.comment("How far medics search for wounded allies.")
                .defineInRange("medicSearchRadius", 24.0, 2.0, 48.0);
        MEDIC_HEAL_PER_TREAT = builder.comment("Health restored per dedicated medic treatment pulse.")
                .defineInRange("medicHealPerTreat", 2.0, 0.5, 20.0);
        HEALTH_MOBILITY_ENABLED = builder.comment("Scale AI vehicle mobility down as health drops.")
                .define("healthMobilityEnabled", true);
        HEALTH_MOBILITY_FLOOR = builder.comment("Minimum mobility fraction at zero health.")
                .defineInRange("healthMobilityFloor", 0.4, 0.05, 1.0);
        MEDIC_SPAWN_CHANCE = builder.comment("Chance an RU/US unit brings a medic companion.")
                .defineInRange("medicSpawnChance", 0.06, 0.0, 1.0);
        ENGINEER_SPAWN_CHANCE = builder.comment("Chance an RU/US unit brings an engineer companion.")
                .defineInRange("engineerSpawnChance", 0.05, 0.0, 1.0);
        SUPPORT_DEDUPE_RADIUS = builder.comment("Radius used to avoid stacking support companions.")
                .defineInRange("supportDedupeRadius", 32.0, 4.0, 128.0);
        ENGINEER_SEARCH_RADIUS = builder.comment("How far engineers search for vehicles to repair.")
                .defineInRange("engineerSearchRadius", 24.0, 4.0, 96.0);
        ENGINEER_REPAIR_PER_TREAT = builder.comment("Vehicle health restored per engineer repair pulse.")
                .defineInRange("engineerRepairPerTreat", 4.0, 0.5, 100.0);
        ENGINEER_REPAIR_COOLDOWN = builder.comment("Goal ticks between engineer repair pulses.")
                .defineInRange("engineerRepairCooldown", 10, 1, 200);
        ENGINEER_SIDEARM_POOL = builder.comment("Possible TACZ sidearm ids for engineers.")
                .defineList("engineerSidearmPool", List.of("tacz:m9a1", "tacz:m1911", "tacz:glock_17"), SewvConfig::isValidResourceId);
        DRONE_MAX_PER_ENGINEER = builder.comment("Maximum recon drones per engineer.")
                .defineInRange("droneMaxPerEngineer", 2, 0, 8);
        DRONE_DEPLOY_CHECK_INTERVAL_TICKS = builder.comment("How often engineers roll to deploy drones.")
                .defineInRange("droneDeployCheckIntervalTicks", 200, 20, 12000);
        DRONE_DEPLOY_CHANCE = builder.comment("Chance that a deploy check launches a drone.")
                .defineInRange("droneDeployChance", 0.2, 0.0, 1.0);
        DRONE_SCAN_ALTITUDE = builder.comment("Drone station altitude above its engineer.")
                .defineInRange("droneScanAltitude", 20.0, 5.0, 60.0);
        DRONE_DETECTION_RADIUS = builder.comment("Drone enemy detection radius.")
                .defineInRange("droneDetectionRadius", 48.0, 8.0, 128.0);
        DRONE_BROADCAST_RADIUS = builder.comment("Radius for relaying drone sightings to allies.")
                .defineInRange("droneBroadcastRadius", 160.0, 16.0, 384.0);
        DRONE_SCAN_INTERVAL_TICKS = builder.comment("How often drones run their expensive enemy scan.")
                .defineInRange("droneScanIntervalTicks", 20, 5, 200);
        AUTO_BOARD_ENABLED = builder.comment("Let idle RU/US infantry claim abandoned vehicles.")
                .define("autoBoardEnabled", true);
        AUTO_BOARD_SCAN_RADIUS = builder.comment("Radius used to scan for abandoned vehicles.")
                .defineInRange("autoBoardScanRadius", 32.0, 4.0, 128.0);
        AUTO_BOARD_MIN_HEALTH_FRACTION = builder.comment("Minimum vehicle health fraction required for auto-boarding.")
                .defineInRange("autoBoardMinHealthFraction", 0.25, 0.0, 1.0);
        AUTO_BOARD_STEALS_PLAYER_VEHICLES = builder.comment("Allow RU/US units to take vehicles last driven by a player.")
                .define("autoBoardStealsPlayerVehicles", false);
        VEHICLE_FORMATION_SPACING = builder.comment("Distance between vehicles in formations.")
                .defineInRange("vehicleFormationSpacing", 12.0, 5.0, 32.0);
        VEHICLE_TARGET_SCAN_RADIUS = builder.comment("Horizontal radius of mounted target scans.")
                .defineInRange("vehicleTargetScanRadius", 96.0, 8.0, 128.0);
        VEHICLE_TARGET_SCAN_HEIGHT = builder.comment("Vertical span of mounted target scans.")
                .defineInRange("vehicleTargetScanHeight", 128.0, 4.0, 128.0);
        VEHICLE_TARGET_SCAN_INTERVAL_TICKS = builder.comment("How often mounted crews rescan for targets.")
                .defineInRange("vehicleTargetScanIntervalTicks", 20, 1, 200);
        VEHICLE_TARGET_REQUIRE_LOS = builder.comment("Require line of sight for mounted target scans.")
                .define("vehicleTargetRequireLineOfSight", true);
        VEHICLE_ALLY_ASSIST_RANGE = builder.comment("Range for counting allied support in combat.")
                .defineInRange("vehicleAllyAssistRange", 128.0, 0.0, 256.0);
        STALEMATE_BREAKER_ENABLED = builder.comment("Let mounted crews reposition when they cannot land shots.")
                .define("stalemateBreakerEnabled", true);
        STALEMATE_SILENCE_TICKS = builder.comment("How long a crew may fail to land a shot before repositioning.")
                .defineInRange("stalemateSilenceTicks", 300, 40, 2400);
        VEHICLE_TERRAIN_AVOIDANCE = builder.comment("Use terrain avoidance while driving.")
                .define("vehicleTerrainAvoidance", true);
        PATROL_ROTATE_INTERVAL_TICKS = builder.comment("How long patrol crews hold a point before rotating.")
                .defineInRange("patrolRotateIntervalTicks", 3600, 200, 24000);
        IDLE_WANDER_ENABLED = builder.comment("Let idle hulls wander locally.")
                .define("idleWanderEnabled", true);
        IDLE_WANDER_RADIUS = builder.comment("How far idle hulls may drift from their anchor.")
                .defineInRange("idleWanderRadius", 16, 4, 64);
        UTILITY_REFRESH_INTERVAL_TICKS = builder.comment("How often vehicle crews reconsider their plan.")
                .defineInRange("utilityRefreshIntervalTicks", 20, 5, 200);
        FACTION_ORGANIC_COMMS = builder.comment("Let RU/US crews call support without carrying radios.")
                .define("factionOrganicComms", true);
        SUPPORT_CALL_INTERVAL_TICKS = builder.comment("Minimum delay between support searches and support requests.")
                .defineInRange("supportCallIntervalTicks", 200, 20, 2400);
        builder.pop();

        builder.push("command");
        COMMAND_GROUP_JOIN_RADIUS = builder.comment("Distance to a group's centroid at which a crew may join.")
                .defineInRange("commandGroupJoinRadius", 48.0, 8.0, 256.0);
        COMMAND_GROUP_LEAVE_RADIUS = builder.comment("Distance at which a member leaves (must exceed join radius).")
                .defineInRange("commandGroupLeaveRadius", 64.0, 8.0, 256.0);
        COMMAND_GROUP_MAX_DIAMETER = builder.comment("Hard maximum group diameter (must be >= 2× leave radius so the hysteresis band fits inside the ball).")
                .defineInRange("commandGroupMaxDiameter", 128.0, 16.0, 512.0);
        COMMAND_GROUP_MIN_SIZE = builder.comment("Minimum drivers for a battle group (lone hulls stay on local AI).")
                .defineInRange("commandGroupMinSize", 2, 2, 32);
        COMMAND_MAX_UNITS = builder.comment("Hard cap on drivers considered per command scan.")
                .defineInRange("commandMaxUnits", 64, 4, 256);
        COMMAND_ENGAGEMENT_RADIUS = builder.comment("Opposing forces within this range gate battle-group formation.")
                .defineInRange("commandEngagementRadius", 96.0, 16.0, 256.0);
        builder.pop();

        builder.push("flight_ai");
        HELI_ENGAGE_RADIUS = builder.comment("Horizontal standoff distance for AI helicopters.")
                .defineInRange("heliEngageRadius", 32.0, 12.0, 64.0);
        HELI_CHUNK_LOADING = builder.comment("Keep AI helicopters ticking when no player is nearby.")
                .define("heliChunkLoading", false);
        PLANE_CHUNK_LOADING = builder.comment("Keep AI planes ticking when no player is nearby.")
                .define("planeChunkLoading", false);
        PLANE_COMMAND_RADIUS = builder.comment("How far the server accepts player aircraft orders.")
                .defineInRange("planeCommandRadius", 256.0, 32.0, 1024.0);
        builder.pop();

        builder.push("mortar_ai");
        MORTAR_USE_DISTANCE = builder.comment("How close a crew must stand to work a mortar.")
                .defineInRange("mortarUseDistance", 2.0, 1.0, 6.0);
        MORTAR_FIRE_COOLDOWN_TICKS = builder.comment("Minimum delay between mortar shots.")
                .defineInRange("mortarFireCooldownTicks", 60, 1, 1200);
        MORTAR_DISPERSION_RADIUS = builder.comment("Scatter radius around a mortar target point.")
                .defineInRange("mortarDispersionRadius", 3, 0, 16);
        MORTAR_REQUIRES_AMMO = builder.comment("Require mortar shells in inventory and consume one per shot.")
                .define("mortarRequiresAmmo", true);
        MORTAR_CHUNK_LOADING = builder.comment("Keep crewed mortars loaded during remote fire missions.")
                .define("mortarChunkLoading", true);
        MORTAR_RADIO_RANGE = builder.comment("Radio range for designating targets and support crews.")
                .defineInRange("mortarRadioRange", 400.0, 16.0, 1024.0);
        builder.pop();

        builder.push("voicelines");
        VEHICLE_VOICELINES_ENABLED = builder.comment("Enable mounted crew radio voicelines.")
                .define("vehicleVoicelinesEnabled", true);
        IDLE_VOICELINE_DELAY_TICKS = builder.comment("How long crews stay quiet before idle chatter starts.")
                .defineInRange("idleVoicelineDelayTicks", 200, 20, 12000);
        IDLE_VOICELINE_HEALTH_FRACTION = builder.comment("Below this health fraction, idle chatter stays off.")
                .defineInRange("idleVoicelineHealthFraction", 0.3, 0.0, 1.0);
        builder.pop();

        builder.push("interaction");
        BOARD_SCAN_RADIUS = builder.comment("How far the server accepts nearby ground-order unit selection.")
                .defineInRange("boardScanRadius", 64.0, 8.0, 128.0);
        builder.pop();

        builder.push("map");
        MAP_INFANTRY_ENABLED = builder.comment("Include on-foot units in map syncs.")
                .define("mapInfantryEnabled", true);
        MAP_SYNC_INTERVAL_TICKS = builder.comment("How often the server sends map marker updates.")
                .defineInRange("mapSyncIntervalTicks", 20, 5, 200);
        MAP_SPOT_RADIUS = builder.comment("How far your side can spot enemy markers on the map.")
                .defineInRange("mapSpotRadius", 128.0, 0.0, 512.0);
        builder.pop();

        builder.push("doctrine");
        builder.comment("Fallback doctrine presets for RU, US, and PMC commanders.");
        DOCTRINE = new ForgeConfigSpec.IntValue[FACTION_KEYS.length][Doctrine.Axis.VALUES.length];
        for (int f = 0; f < FACTION_KEYS.length; f++) {
            builder.push(FACTION_KEYS[f]);
            for (Doctrine.Axis axis : Doctrine.Axis.VALUES) {
                DOCTRINE[f][axis.ordinal()] = builder
                        .comment(axis.description)
                        .defineInRange(axis.key, DOCTRINE_DEFAULTS[f][axis.ordinal()], -Doctrine.AXIS_LIMIT, Doctrine.AXIS_LIMIT);
            }
            builder.pop();
        }
        builder.pop();

        SPEC = builder.build();
    }

    private SewvConfig() {}

    private static boolean isValidResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
