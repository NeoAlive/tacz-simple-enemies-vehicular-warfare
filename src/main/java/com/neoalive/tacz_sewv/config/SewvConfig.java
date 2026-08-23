package com.neoalive.tacz_sewv.config;

import java.util.Arrays;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine;

public final class SewvConfig {

    // Minecraft keeps your existing config file. Delete it (or individual keys) to pick up new defaults.
    public static final ForgeConfigSpec SPEC;

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
    public static final ForgeConfigSpec.ConfigValue<String> HIGH_CHANCE_TYPE63_ROCKET;
    public static final ForgeConfigSpec.ConfigValue<String> LOW_CHANCE_TYPE63_ROCKET;
    public static final ForgeConfigSpec.BooleanValue DERELICT_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue DERELICT_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DERELICT_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue DERELICT_HEALTH_FRACTION;
    public static final ForgeConfigSpec.IntValue DERELICT_GUARDS;
    public static final ForgeConfigSpec.IntValue DERELICT_AMMO_COUNT;
    public static final ForgeConfigSpec.BooleanValue OVERFLIGHT_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue OVERFLIGHT_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue OVERFLIGHT_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue OVERFLIGHT_PLANES;
    public static final ForgeConfigSpec.BooleanValue GARRISON_VEHICLES_ENABLED;
    public static final ForgeConfigSpec.DoubleValue GARRISON_VEHICLE_CHANCE;

    public static final ForgeConfigSpec.BooleanValue CREATIVE_AMMO_FALLBACK;
    public static final ForgeConfigSpec.BooleanValue FACTION_INFINITE_ENERGY;
    public static final ForgeConfigSpec.BooleanValue FACTION_INFINITE_AMMO;
    public static final ForgeConfigSpec.ConfigValue<String> VEHICLE_DEATH_DROPS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_AMMO_LOOT;

    public static final ForgeConfigSpec.BooleanValue NPC_ARMOR_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_ARMOR;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NVG_ELIGIBLE_ITEMS;
    public static final ForgeConfigSpec.DoubleValue NVG_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DARK_ACCURACY_FRACTION;
    public static final ForgeConfigSpec.DoubleValue NVG_ACCURACY_FRACTION;
    public static final ForgeConfigSpec.DoubleValue DARK_SPREAD_SCALE_MAX;
    public static final ForgeConfigSpec.IntValue DARK_BLOCK_LIGHT_MAX;

    public static final ForgeConfigSpec.BooleanValue STRUCTURE_VEHICLES_ENABLED;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_MAX_COUNT;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_RAMP_DAYS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_VEHICLE_STRUCTURES;

    public static final ForgeConfigSpec.IntValue AI_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue AI_FIRE_ASSIST_CONE_DEG;
    public static final ForgeConfigSpec.DoubleValue SMOKE_BLOCK_RADIUS;
    public static final ForgeConfigSpec.DoubleValue FRIENDLY_FIRE_VEHICLE_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<String> AI_AIM_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue AI_AIM_SPREAD_DEG;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_SKIN_MOUNT_CHANCE;
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
    public static final ForgeConfigSpec.BooleanValue PMC_REVIVE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue PMC_REVIVE_SEARCH_RADIUS;
    public static final ForgeConfigSpec.IntValue PMC_REVIVE_CHANNEL_TICKS;
    public static final ForgeConfigSpec.BooleanValue PMC_REVIVE_FORCE_SINGLEPLAYER;
    public static final ForgeConfigSpec.BooleanValue PMC_DOWNED_ENABLED;
    public static final ForgeConfigSpec.DoubleValue PMC_DOWNED_HEALTH;
    public static final ForgeConfigSpec.IntValue PMC_DOWNED_BLEED_TICKS;
    public static final ForgeConfigSpec.DoubleValue PMC_DOWNED_REVIVE_HEALTH;
    public static final ForgeConfigSpec.BooleanValue MEDIC_CAPTURE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MEDIC_CAPTURED_HEALTH;
    public static final ForgeConfigSpec.IntValue MEDIC_CAPTURE_DURATION_TICKS;
    public static final ForgeConfigSpec.DoubleValue MEDIC_FLEE_DETECTION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue MEDIC_FLEE_MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue MEDIC_FLEE_MAX_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue MEDIC_CAPTURE_DEBUG_LOGGING;
    public static final ForgeConfigSpec.DoubleValue PMC_CAPTURE_MEDIC_RADIUS;
    public static final ForgeConfigSpec.BooleanValue HEALTH_MOBILITY_ENABLED;
    public static final ForgeConfigSpec.DoubleValue HEALTH_MOBILITY_FLOOR;
    public static final ForgeConfigSpec.DoubleValue MEDIC_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SUPPORT_DEDUPE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_REPAIR_PER_TREAT;
    public static final ForgeConfigSpec.IntValue ENGINEER_REPAIR_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue ENGINEER_REPAIR_SPEED_BOOST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENGINEER_SIDEARM_POOL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> COMMANDER_SIDEARM_POOL;
    public static final ForgeConfigSpec.IntValue DRONE_MAX_PER_ENGINEER;
    public static final ForgeConfigSpec.IntValue DRONE_DEPLOY_CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue DRONE_DEPLOY_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DRONE_SCAN_ALTITUDE;
    public static final ForgeConfigSpec.DoubleValue DRONE_BROADCAST_RADIUS;
    public static final ForgeConfigSpec.IntValue DRONE_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue DRONE_LEASH_RADIUS;
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_MIN_HEALTH_FRACTION;
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_STEALS_PLAYER_VEHICLES;
    public static final ForgeConfigSpec.BooleanValue AUTO_MAN_MORTAR_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTO_MAN_MORTAR_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue AUTO_ENTRENCH_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTO_ENTRENCH_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_FORMATION_SPACING;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_HEIGHT;
    public static final ForgeConfigSpec.IntValue VEHICLE_TARGET_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TARGET_REQUIRE_LOS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_ALLY_ASSIST_RANGE;
    public static final ForgeConfigSpec.BooleanValue STALEMATE_BREAKER_ENABLED;
    public static final ForgeConfigSpec.IntValue STALEMATE_SILENCE_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TERRAIN_AVOIDANCE;
    // groundPathingDebug/shipPathingDebug/sewvDiagDebug moved to gamerules
    // (sewvGroundPathingDebug/sewvShipPathingDebug/sewvDiagDebug) — see ModGameRules.
    public static final ForgeConfigSpec.BooleanValue KOMODO_RENDER_FIX_ENABLED;
    public static final ForgeConfigSpec.IntValue PATROL_ROTATE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue IDLE_WANDER_ENABLED;
    public static final ForgeConfigSpec.IntValue IDLE_WANDER_RADIUS;
    public static final ForgeConfigSpec.IntValue UTILITY_REFRESH_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue FACTION_ORGANIC_COMMS;
    public static final ForgeConfigSpec.IntValue SUPPORT_CALL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue OUTER_RING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue OUTER_RING_MAX_BLOCKS;
    // outerRingDebugLogging moved to gamerule sewvOuterRingDebugLogging — see ModGameRules.

    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_JOIN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_LEAVE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_GROUP_MAX_DIAMETER;
    public static final ForgeConfigSpec.IntValue COMMAND_GROUP_MIN_SIZE;
    public static final ForgeConfigSpec.IntValue COMMAND_MAX_UNITS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_ENGAGEMENT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COMMAND_MARGIN;
    public static final ForgeConfigSpec.DoubleValue PLATOON_COHESION_RADIUS;
    public static final ForgeConfigSpec.IntValue PLATOON_MAX_SIZE;
    public static final ForgeConfigSpec.IntValue PLATOON_MIN_SIZE;
    public static final ForgeConfigSpec.DoubleValue INFLUENCE_CELL_SIZE;
    public static final ForgeConfigSpec.IntValue INFLUENCE_MAX_CELLS;
    public static final ForgeConfigSpec.IntValue MIN_PLAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue PLAY_SWITCH_MARGIN;

    public static final ForgeConfigSpec.DoubleValue HELI_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue HELI_MAX_DEPRESSION_DEG;
    public static final ForgeConfigSpec.DoubleValue HELI_MIN_STANDOFF;
    // heliCombatDebug/heliFlightDebug moved to gamerules sewvHeliCombatDebug/sewvHeliFlightDebug.
    public static final ForgeConfigSpec.BooleanValue HELI_CHUNK_LOADING;
    public static final ForgeConfigSpec.BooleanValue PLANE_CHUNK_LOADING;
    public static final ForgeConfigSpec.DoubleValue PLANE_COMMAND_RADIUS;
    public static final ForgeConfigSpec.DoubleValue PLANE_GUN_CONE_DEG;
    public static final ForgeConfigSpec.DoubleValue PLANE_MISSILE_CONE_DEG;
    public static final ForgeConfigSpec.IntValue PLANE_MISSILE_LOCK_TICKS;
    public static final ForgeConfigSpec.DoubleValue PLANE_MIN_CONE_DEG;
    public static final ForgeConfigSpec.DoubleValue PLANE_AUTO_ROCKET_RANGE;
    public static final ForgeConfigSpec.DoubleValue PLANE_AUTO_HEAVY_RANGE;
    public static final ForgeConfigSpec.IntValue PLANE_BOMB_STICK;
    public static final ForgeConfigSpec.IntValue PLANE_BOMB_STICK_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue PLANE_BOMB_SIGHT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue PLANE_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue PLANE_ATTACK_RUN_LENGTH;
    public static final ForgeConfigSpec.DoubleValue PLANE_MAX_ALTITUDE;
    public static final ForgeConfigSpec.BooleanValue PLANE_DIVE_SNAP;
    public static final ForgeConfigSpec.DoubleValue PLANE_LAND_TRANSIT_AGL;
    public static final ForgeConfigSpec.DoubleValue PLANE_LAND_FLARE_AGL;
    public static final ForgeConfigSpec.DoubleValue PLANE_LAND_FLARE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue PLANE_LAND_SETTLE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_MIN_ASPECT_RATIO;
    public static final ForgeConfigSpec.IntValue AIRPORT_MIN_LENGTH_BLOCKS;
    public static final ForgeConfigSpec.IntValue AIRPORT_MAX_AREA_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_LANDING_SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_ALIGNMENT_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_ALIGNMENT_SNAP_RADIUS;
    public static final ForgeConfigSpec.DoubleValue DUBINS_ALIGN_TOLERANCE_DEG;
    public static final ForgeConfigSpec.DoubleValue DUBINS_FALLBACK_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue DUBINS_DEVIATION_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_SLOT_SIZE_FACTOR;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_SLOT_BUFFER_FACTOR;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_EXTRA_TAKEOFF_FACTOR;
    public static final ForgeConfigSpec.DoubleValue AIRPORT_TAXI_SPEED;
    public static final ForgeConfigSpec.BooleanValue DEBUG_AUTO_PLANE_DEPLOY;
    // planeCombatDebug moved to gamerule sewvPlaneCombatDebug — see ModGameRules.

    public static final ForgeConfigSpec.DoubleValue MORTAR_USE_DISTANCE;
    public static final ForgeConfigSpec.IntValue MORTAR_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue TYPE63_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue MORTAR_DISPERSION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue FRIENDLY_FIRE_MORTAR_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MORTAR_REQUIRES_AMMO;
    public static final ForgeConfigSpec.BooleanValue MORTAR_CHUNK_LOADING;
    public static final ForgeConfigSpec.BooleanValue ARTILLERY_CHUNK_LOADING;
    public static final ForgeConfigSpec.DoubleValue MORTAR_RADIO_RANGE;

    public static final ForgeConfigSpec.BooleanValue VEHICLE_VOICELINES_ENABLED;
    public static final ForgeConfigSpec.IntValue IDLE_VOICELINE_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue IDLE_VOICELINE_HEALTH_FRACTION;

    // orderFailureDebug/targetVetoDebug moved to gamerules sewvOrderFailureDebug/sewvTargetVetoDebug.
    public static final ForgeConfigSpec.IntValue TARGET_VETO_COOLDOWN_TICKS;

    public static final ForgeConfigSpec.DoubleValue BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MAP_INFANTRY_ENABLED;
    public static final ForgeConfigSpec.IntValue MAP_SYNC_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue MAP_SPOT_RADIUS;
    public static final ForgeConfigSpec.IntValue SWEEP_QUIET_SECONDS;
    public static final ForgeConfigSpec.IntValue SWEEP_MAX_CHUNK_AREA;

    /** Invasion HUD colours (hex RGB). Team A/B match the left/right bases on the layout. */
    public static final ForgeConfigSpec.BooleanValue UNLIMITED_TEAM_BASES;
    public static final ForgeConfigSpec.ConfigValue<String> INVASION_HUD_TEAM_A_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> INVASION_HUD_TEAM_B_COLOR;
    public static final ForgeConfigSpec.ConfigValue<String> INVASION_HUD_NEUTRAL_COLOR;

    public static final ForgeConfigSpec.IntValue[][] DOCTRINE;

    // Optional Extermination support: Tripod shield, HP, and hit flare.
    public static final ForgeConfigSpec.BooleanValue TRIPOD_SHIELD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue TRIPOD_HP_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue TRIPOD_SHIELD_BREAK_DAMAGE;
    public static final ForgeConfigSpec.IntValue TRIPOD_SHIELD_REGEN_TICKS;
    public static final ForgeConfigSpec.IntValue TRIPOD_SHIELD_FLARE_TICKS;
    // tripodShieldFlareDebugAlwaysOn/tripodShieldDebugWireframe moved to gamerules
    // sewvTripodShieldFlareAlwaysOn/sewvTripodShieldWireframe.
    public static final ForgeConfigSpec.DoubleValue TRIPOD_SHIELD_AXIS_SCALE;
    public static final ForgeConfigSpec.DoubleValue INVASION_POD_AVOID_RADIUS;
    public static final ForgeConfigSpec.DoubleValue HEAT_RAY_SPEED;

    public static final ForgeConfigSpec.BooleanValue TACZ_BALLISTIC_TRANSLATION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue TACZ_BALLISTIC_GLOBAL_SCALE;

    // Optional Enhanced Falling Trees support: ground vehicles fell trees on contact.
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TREE_FELLING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TREE_FELL_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TREE_PATH_MALUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TREE_SENSOR_DANGER;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TREE_FELLING_EXEMPT_GIANT_TRUNKS;
    public static final ForgeConfigSpec.IntValue VEHICLE_TREE_CONTACT_TICKS;

    private static final String[] FACTION_KEYS = {"ru", "us", "pmc"};
    private static final int[][] DOCTRINE_DEFAULTS = {
            {2, -1, 2, -1, 1, 0, -2, 2},
            {0, 2, 1, 2, 3, 1, 2, -1},
            {0, 0, 0, 0, 0, 0, 0, 0},
    };

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("events");
        TANK_SPAWN_CHANCE_RU = builder.comment("Chance an RU tank appears when Simple Enemy Mod rolls a distant fight.",
                        "Turn tanks in events on/off with the sewvTanksInEvents gamerule.")
                .defineInRange("tankSpawnChanceRu", 0.12, 0.0, 1.0);
        TANK_SPAWN_CHANCE_US = builder.comment("Chance a US tank appears when Simple Enemy Mod rolls a distant fight.",
                        "Turn tanks in events on/off with the sewvTanksInEvents gamerule.")
                .defineInRange("tankSpawnChanceUs", 0.12, 0.0, 1.0);
        PLANES_IN_EVENTS = builder.comment("Allow rare RU/US planes to appear in combat events.").define("planesInEvents", true);
        PLANE_SPAWN_CHANCE_RU = builder.comment("Chance an RU plane appears when Simple Enemy Mod rolls a distant fight.")
                .defineInRange("planeSpawnChanceRu", 0.02, 0.0, 1.0);
        PLANE_SPAWN_CHANCE_US = builder.comment("Chance a US plane appears when Simple Enemy Mod rolls a distant fight.")
                .defineInRange("planeSpawnChanceUs", 0.02, 0.0, 1.0);
        CONVOY_EVENTS_ENABLED = builder.comment("Allow convoy events (vehicles on the move).").define("convoyEventsEnabled", true);
        CONVOY_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for a convoy.")
                .defineInRange("convoyBaseChance", 0.06, 0.0, 1.0);
        CONVOY_FAILURE_MULTIPLIER = builder.comment("How much the convoy chance grows after each miss (until it fires).")
                .defineInRange("convoyFailureMultiplier", 0.06, 0.0, 1.0);
        LARGE_COMBAT_EVENTS_ENABLED = builder.comment("Allow large multi-vehicle battles.").define("largeCombatEventsEnabled", true);
        LARGE_COMBAT_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for a large battle.")
                .defineInRange("largeCombatBaseChance", 0.02, 0.0, 1.0);
        LARGE_COMBAT_FAILURE_MULTIPLIER = builder.comment("How much the large-battle chance grows after each miss.")
                .defineInRange("largeCombatFailureMultiplier", 0.02, 0.0, 1.0);
        LARGE_COMBAT_VEHICLES = builder.comment("How many vehicles each side gets in a large battle.")
                .defineInRange("largeCombatVehicles", 2, 0, 8);
        LARGE_COMBAT_EMPLACEMENT_CHANCE = builder.comment("Per side: chance to also spawn a mortar or TOW launcher.")
                .defineInRange("largeCombatEmplacementChance", 0.04, 0.0, 1.0);
        LARGE_COMBAT_PLANE_CHANCE = builder.comment("Per side: chance to also spawn a plane.")
                .defineInRange("largeCombatPlaneChance", 0.03, 0.0, 1.0);
        NAVAL_EVENTS_ENABLED = builder.comment("Allow ship battles on open water.").define("navalEventsEnabled", true);
        NAVAL_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for a naval battle.")
                .defineInRange("navalBaseChance", 0.05, 0.0, 1.0);
        NAVAL_FAILURE_MULTIPLIER = builder.comment("How much the naval-battle chance grows after each miss.")
                .defineInRange("navalFailureMultiplier", 0.05, 0.0, 1.0);
        NAVAL_SHIPS_PER_SIDE = builder.comment("How many ships each side gets in a naval battle.")
                .defineInRange("navalShipsPerSide", 4, 0, 12);
        INVASION_EVENTS_ENABLED = builder.comment("Allow invasion events (attackers vs a defended position).").define("invasionEventsEnabled", true);
        INVASION_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for an invasion.")
                .defineInRange("invasionBaseChance", 0.03, 0.0, 1.0);
        INVASION_FAILURE_MULTIPLIER = builder.comment("How much the invasion chance grows after each miss.")
                .defineInRange("invasionFailureMultiplier", 0.03, 0.0, 1.0);
        INVASION_DEFENDER_INFANTRY = builder.comment("How many defending foot soldiers spawn in an invasion.")
                .defineInRange("invasionDefenderInfantry", 6, 0, 32);
        INVASION_DEFENDER_TOWS = builder.comment("How many defending anti-tank launchers (TOWs) spawn.")
                .defineInRange("invasionDefenderTows", 2, 0, 8);
        INVASION_DEFENDER_MORTARS = builder.comment("How many defending mortars spawn.")
                .defineInRange("invasionDefenderMortars", 2, 0, 8);
        SHELLING_EVENTS_ENABLED = builder.comment("Allow mortar batteries to shell a player's base.").define("shellingEventsEnabled", true);
        SHELLING_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for shelling.")
                .defineInRange("shellingBaseChance", 0.05, 0.0, 1.0);
        SHELLING_FAILURE_MULTIPLIER = builder.comment("How much the shelling chance grows after each miss.")
                .defineInRange("shellingFailureMultiplier", 0.04, 0.0, 1.0);
        SHELLING_BASE_RADIUS = builder.comment("Player must be this close to their bed/respawn point for shelling to target it.")
                .defineInRange("shellingBaseRadius", 48, 8, 256);
        SHELLING_MORTARS = builder.comment("How many mortars in a shelling battery.")
                .defineInRange("shellingMortars", 2, 0, 6);
        SHELLING_GUARDS = builder.comment("How many soldiers guard the shelling battery.")
                .defineInRange("shellingGuards", 4, 0, 12);
        SHELLING_DURATION_MIN_TICKS = builder.comment("Shortest shelling lasts, in game ticks (20 ticks = 1 second).")
                .defineInRange("shellingDurationMinTicks", 600, 20, 24000);
        SHELLING_DURATION_MAX_TICKS = builder.comment("Longest shelling lasts, in game ticks (20 ticks = 1 second).")
                .defineInRange("shellingDurationMaxTicks", 1200, 20, 24000);
        HIGH_CHANCE_MORTAR_SHELL = builder.comment("Usual mortar shell type for spawned crews (item id).")
                .define("highChanceMortarShell", "superbwarfare:mortar_shell", SewvConfig::isValidResourceId);
        LOW_CHANCE_MORTAR_SHELL = builder.comment("Less common mortar shell type for spawned crews (item id).")
                .define("lowChanceMortarShell", "superbwarfare:mortar_shell_wp", SewvConfig::isValidResourceId);
        HIGH_CHANCE_TYPE63_ROCKET = builder.comment("Usual Type-63 rocket for spawned crews (item id).")
                .define("highChanceType63Rocket", "superbwarfare:medium_rocket_he", SewvConfig::isValidResourceId);
        LOW_CHANCE_TYPE63_ROCKET = builder.comment("Less common Type-63 rocket for spawned crews (item id).")
                .define("lowChanceType63Rocket", "superbwarfare:medium_rocket_ap", SewvConfig::isValidResourceId);
        DERELICT_EVENTS_ENABLED = builder.comment("Allow wrecked vehicles with a few survivors nearby.").define("derelictEventsEnabled", true);
        DERELICT_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for a derelict.")
                .defineInRange("derelictBaseChance", 0.05, 0.0, 1.0);
        DERELICT_FAILURE_MULTIPLIER = builder.comment("How much the derelict chance grows after each miss.")
                .defineInRange("derelictFailureMultiplier", 0.05, 0.0, 1.0);
        DERELICT_HEALTH_FRACTION = builder.comment("How healthy a derelict hull is (0.15 = 15%). Keep this below autoBoardMinHealthFraction",
                        "so nearby enemy infantry do not climb in and drive it away.")
                .defineInRange("derelictHealthFraction", 0.15, 0.01, 1.0);
        DERELICT_GUARDS = builder.comment("Max survivors standing near a derelict hull.")
                .defineInRange("derelictGuards", 4, 0, 12);
        DERELICT_AMMO_COUNT = builder.comment("How much ammo is left inside a derelict hull.")
                .defineInRange("derelictAmmoCount", 2, 0, 64);
        OVERFLIGHT_EVENTS_ENABLED = builder.comment("Allow flyovers by RU/US planes from the plane spawn lists.")
                .define("overflightEventsEnabled", true);
        OVERFLIGHT_BASE_CHANCE = builder.comment("Starting chance each time Simple Enemy Mod checks for an overflight.")
                .defineInRange("overflightBaseChance", 0.04, 0.0, 1.0);
        OVERFLIGHT_FAILURE_MULTIPLIER = builder.comment("How much the overflight chance grows after each miss.")
                .defineInRange("overflightFailureMultiplier", 0.04, 0.0, 1.0);
        OVERFLIGHT_PLANES = builder.comment("Max planes in one overflight.")
                .defineInRange("overflightPlanes", 1, 1, 3);
        GARRISON_VEHICLES_ENABLED = builder.comment("Let village garrisons sometimes get one crewed tank.")
                .define("garrisonVehiclesEnabled", true);
        GARRISON_VEHICLE_CHANCE = builder.comment("Chance a village garrison gets that tank.")
                .defineInRange("garrisonVehicleChance", 0.5, 0.0, 1.0);
        builder.pop();

        builder.push("resources");
        CREATIVE_AMMO_FALLBACK = builder.comment("If a vehicle's normal ammo type cannot be figured out, fall back to a creative ammo box.")
                .define("creativeAmmoFallback", true);
        FACTION_INFINITE_ENERGY = builder.comment("Vehicles crewed by RU or US never run out of energy/fuel.")
                .define("factionInfiniteEnergy", true);
        FACTION_INFINITE_AMMO = builder.comment("Vehicles crewed by RU or US never run out of ammo.")
                .define("factionInfiniteAmmo", true);
        // Existing worlds keep the old value until you change or delete this key.
        VEHICLE_DEATH_DROPS = builder.comment(
                        "What drops when a vehicle is destroyed (and for special spawned crews).",
                        "disable = nothing; reduced = about 1/4 of each stack (default); everything = full stacks.",
                        "Creative ammo boxes never drop.")
                .defineInList("vehicleDeathDrops", "reduced",
                        Arrays.asList("disable", "reduced", "everything"));
        VEHICLE_AMMO_LOOT = builder.comment(
                        "When you clear the last enemy crew from an RU/US vehicle, add a fair ammo package",
                        "into its inventory (on top of normal vehicle loot). If it only had a creative ammo",
                        "box and creativeAmmoFallback is on, that box is replaced with real ammo stacks.",
                        "Ammo already being used in a fight is left alone.")
                .define("vehicleAmmoLoot", true);
        builder.pop();

        builder.push("npc_armor");
        NPC_ARMOR_ENABLED = builder.comment("Give armor to spawned Simple Enemy Mod soldiers.")
                .define("npcArmorEnabled", true);
        RU_ARMOR = builder.comment("Armor pieces for RU units (item ids).")
                .defineList("ruArmor", List.of("superbwarfare:ru_helmet_6b47", "superbwarfare:ru_chest_6b43"), SewvConfig::isValidResourceId);
        US_ARMOR = builder.comment("Armor pieces for US units (item ids).")
                .defineList("usArmor", List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"), SewvConfig::isValidResourceId);
        PMC_ARMOR = builder.comment("Armor pieces for PMC units (item ids).")
                .defineList("pmcArmor", List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"), SewvConfig::isValidResourceId);
        builder.pop();

        builder.push("nvg");
        NVG_ELIGIBLE_ITEMS = builder.comment(
                        "Items that count as night-vision gear (item ids).",
                        "Used for night accuracy and for who spawns wearing NVG. Can be armor or Curios slots.")
                .defineList("nvgEligibleItems",
                        List.of("superbwarfare:thermal_imaging_goggles"), SewvConfig::isValidResourceId);
        NVG_SPAWN_CHANCE = builder.comment("Chance a soldier spawning at night wears night-vision gear.")
                .defineInRange("nvgSpawnChance", 0.20, 0.0, 1.0);
        DARK_ACCURACY_FRACTION = builder.comment(
                        "How accurate AI vehicle guns are in the dark with no night vision on board (0.55 ≈ 45% worse).",
                        "Also limited by darkSpreadScaleMax so spread cannot get worse than that cap.")
                .defineInRange("darkAccuracyFraction", 0.55, 0.05, 1.0);
        NVG_ACCURACY_FRACTION = builder.comment(
                        "How accurate AI vehicle guns are in the dark when someone visible in a seat has night vision",
                        "(0.85 ≈ 15% worse than daylight).")
                .defineInRange("nvgAccuracyFraction", 0.85, 0.05, 1.0);
        DARK_SPREAD_SCALE_MAX = builder.comment(
                        "Worst allowed shot spread in darkness vs daytime. 2.0 means at most twice as wide.")
                .defineInRange("darkSpreadScaleMax", 2.0, 1.0, 10.0);
        DARK_BLOCK_LIGHT_MAX = builder.comment(
                        "During the day, places this dark or darker count as dark for accuracy (0-15 light scale).",
                        "Night always counts as dark. Open daylight is bright from the sky, so outdoors stays accurate.")
                .defineInRange("darkBlockLightMax", 4, 0, 15);
        builder.pop();

        builder.push("structure_vehicles");
        STRUCTURE_VEHICLES_ENABLED = builder.comment("Spawn vehicles at matching Berezka army/PMC bases.")
                .define("structureVehiclesEnabled", true);
        STRUCTURE_VEHICLE_MAX_COUNT = builder.comment("Max vehicles one of those bases can have.")
                .defineInRange("structureVehicleMaxCount", 5, 1, 16);
        STRUCTURE_VEHICLE_RAMP_DAYS = builder.comment("In-game days until bases go from 1 vehicle up to the max.")
                .defineInRange("structureVehicleRampDays", 24, 0, 1000);
        RU_VEHICLE_STRUCTURES = builder.comment("Which structures get RU vehicles (structure ids).")
                .defineList("ruVehicleStructures", List.of("russian_army_structures:tank"), SewvConfig::isValidResourceId);
        US_VEHICLE_STRUCTURES = builder.comment("Which structures get US vehicles (structure ids).")
                .defineList("usVehicleStructures", List.of("us_army_structures:convoy"), SewvConfig::isValidResourceId);
        PMC_VEHICLE_STRUCTURES = builder.comment("Which structures get PMC vehicles (structure ids).")
                .defineList("pmcVehicleStructures", List.of("pmc_structures:buggy"), SewvConfig::isValidResourceId);
        builder.pop();

        builder.push("crew_ai");
        AI_FIRE_COOLDOWN_TICKS = builder.comment("Minimum wait between AI vehicle shots, in game ticks (20 = 1 second).")
                .defineInRange("aiFireCooldownTicks", 5, 1, 200);
        AI_FIRE_ASSIST_CONE_DEG = builder.comment(
                        "How many degrees off-target an AI crew may still pull the trigger.",
                        "Wider = more missed shots but less sitting silent. Existing configs keep their old value until you edit them.")
                .defineInRange("aiFireAssistConeDeg", 35.0, 4.0, 90.0);
        SMOKE_BLOCK_RADIUS = builder.comment("AI will not shoot if smoke is this close (blocks).")
                .defineInRange("smokeBlockRadius", 6.0, 1.0, 16.0);
        FRIENDLY_FIRE_VEHICLE_RADIUS = builder.comment(
                        "AI vehicle weapons (including TOWs) hold fire when the target is this close (blocks)",
                        "to the owning player or a friendly PMC. 0 disables.")
                .defineInRange("friendlyFireVehicleRadius", 6.0, 0.0, 32.0);
        AI_AIM_ACCURACY = builder.comment("AI aim style: realistic (misses), scaled, or accurate (rare misses).")
                .defineInList("aiAimAccuracy", "realistic", Arrays.asList("realistic", "scaled", "accurate"));
        // Existing configs keep the old value until edited or deleted.
        AI_AIM_SPREAD_DEG = builder.comment("Extra aim wobble in degrees for realistic/scaled modes.")
                .defineInRange("aiAimSpreadDegrees", 4.0, 0.0, 30.0);
        VEHICLE_SKIN_MOUNT_CHANCE = builder.comment(
                        "Chance a soldier climbing into an empty captured vehicle paints it in their faction colours.",
                        "Vehicles spawned already crewed always get the faction look; this is only for field captures.")
                .defineInRange("vehicleSkinMountChance", 0.60, 0.0, 1.0);
        IFV_DISMOUNTS_ENABLED = builder.comment("IFVs (troop carriers) drop their squad when fighting enemy armor.")
                .define("ifvDismountsEnabled", true);
        SEM_CREW_DISABLE_INERTIA_ROTATE = builder.comment("Stop the vehicle body from tilting/banking while AI drives.")
                .define("semCrewDisableInertiaRotate", true);
        TANK_RIDER_DISMOUNT_ENABLED = builder.comment("Soldiers hanging on outside seats jump off when combat starts.")
                .define("tankRiderDismountEnabled", true);
        AT_WEAPON_RU = builder.comment("Rocket/missile launcher given to RU soldiers who leave the vehicle to fight tanks.")
                .define("atWeaponRu", "superbwarfare:rpg");
        AT_WEAPON_US = builder.comment("Rocket/missile launcher given to US soldiers who leave the vehicle to fight tanks.")
                .define("atWeaponUs", "superbwarfare:javelin");
        AT_SECOND_GUNNER_CHANCE = builder.comment("Chance a dismounted squad gets a second anti-tank gunner.")
                .defineInRange("atSecondGunnerChance", 0.5, 0.0, 1.0);
        AT_BACKUP_AMMO = builder.comment("Extra rockets or missiles each anti-tank gunner is given.")
                .defineInRange("atBackupAmmo", 8, 1, 64);
        AT_ENGAGE_RANGE = builder.comment("Max distance (blocks) at which anti-tank gunners will fire.")
                .defineInRange("atEngageRange", 48.0, 8.0, 200.0);
        MEDIC_ENABLED = builder.comment("PMC soldiers carrying a medical kit can heal allies when not in a fight.")
                .define("medicEnabled", true);
        MEDIC_SEARCH_RADIUS = builder.comment("How far (blocks) medics look for wounded allies.")
                .defineInRange("medicSearchRadius", 24.0, 2.0, 48.0);
        MEDIC_HEAL_PER_TREAT = builder.comment("Health restored each time a medic treats someone.")
                .defineInRange("medicHealPerTreat", 2.0, 0.5, 20.0);
        PMC_REVIVE_ENABLED = builder.comment("PMC soldiers automatically revive a downed player (requires PlayerReviveMod).")
                .define("pmcReviveEnabled", true);
        PMC_REVIVE_SEARCH_RADIUS = builder.comment("How far (blocks) a PMC looks for a downed player to revive.")
                .defineInRange("pmcReviveSearchRadius", 32.0, 2.0, 64.0);
        PMC_REVIVE_CHANNEL_TICKS = builder.comment("Ticks a PMC must stand next to a downed player before reviving them.")
                .defineInRange("pmcReviveChannelTicks", 100, 20, 400);
        PMC_REVIVE_FORCE_SINGLEPLAYER = builder.comment(
                "Forces PlayerReviveMod's bleed-out state to work in singleplayer, not just LAN/dedicated "
                        + "servers (equivalent to setting PlayerReviveMod's own bleedInSingleplayer option). "
                        + "Without this, a singleplayer death never bleeds out and PMC auto-revive has nothing to do.")
                .define("pmcReviveForceSingleplayer", true);
        PMC_DOWNED_ENABLED = builder.comment(
                "PMC units go down instead of dying and can be revived (by a player interacting with "
                        + "them, or by a medic PMC) instead of dying outright. RU/US always just die. "
                        + "Requires PlayerReviveMod (bundled with the downed-player feature, even though "
                        + "it never uses that mod's own API).")
                .define("pmcDownedEnabled", true);
        PMC_DOWNED_HEALTH = builder.comment("Health a PMC is left with the moment it goes down.")
                .defineInRange("pmcDownedHealth", 4.0, 1.0, 20.0);
        PMC_DOWNED_BLEED_TICKS = builder.comment("Ticks a downed PMC has before it dies for real if not revived.")
                .defineInRange("pmcDownedBleedTicks", 1200, 100, 6000);
        PMC_DOWNED_REVIVE_HEALTH = builder.comment("Health a PMC is restored to when successfully revived.")
                .defineInRange("pmcDownedReviveHealth", 6.0, 1.0, 40.0);
        MEDIC_CAPTURE_ENABLED = builder.comment("RU/US medics may be captured (defeated) and converted to PMC units for a cost.")
                .define("medicCaptureEnabled", true);
        MEDIC_CAPTURED_HEALTH = builder.comment("Health an RU/US medic is left with the moment it is captured.")
                .defineInRange("medicCapturedHealth", 4.0, 1.0, 20.0);
        MEDIC_CAPTURE_DURATION_TICKS = builder.comment("Ticks a captured medic has before escaping. 2400 = 2 minutes.")
                .defineInRange("medicCaptureDurationTicks", 2400, 100, 12000);
        MEDIC_FLEE_DETECTION_RADIUS = builder.comment("How far (blocks) medics look for a nearby hostile before fleeing.")
                .defineInRange("medicFleeDetectionRadius", 16.0, 4.0, 64.0);
        MEDIC_FLEE_MIN_DISTANCE = builder.comment("Minimum distance (blocks) a fleeing medic tries to reach from the threat.")
                .defineInRange("medicFleeMinDistance", 8.0, 2.0, 32.0);
        MEDIC_FLEE_MAX_DISTANCE = builder.comment("Maximum distance (blocks) sampled for a fleeing medic's escape point.")
                .defineInRange("medicFleeMaxDistance", 16.0, 4.0, 64.0);
        MEDIC_CAPTURE_DEBUG_LOGGING = builder.comment(
                        "Log medic capture trigger/expiry and PMC chase-medic dispatch to console. Off by default.")
                .define("medicCaptureDebugLogging", false);
        PMC_CAPTURE_MEDIC_RADIUS = builder.comment(
                        "How far (blocks) a PMC ordered to Capture Medic looks for a medic — captured",
                        "or still running — to subdue and convert.")
                .defineInRange("pmcCaptureMedicRadius", 48.0, 8.0, 128.0);
        HEALTH_MOBILITY_ENABLED = builder.comment("Damaged AI vehicles move more slowly.")
                .define("healthMobilityEnabled", true);
        HEALTH_MOBILITY_FLOOR = builder.comment("Slowest a wrecked hull still moves (0.4 = 40% of normal speed).")
                .defineInRange("healthMobilityFloor", 0.4, 0.05, 1.0);
        MEDIC_SPAWN_CHANCE = builder.comment("Chance an RU/US group includes a medic.")
                .defineInRange("medicSpawnChance", 0.06, 0.0, 1.0);
        ENGINEER_SPAWN_CHANCE = builder.comment("Chance an RU/US group includes an engineer.")
                .defineInRange("engineerSpawnChance", 0.05, 0.0, 1.0);
        SUPPORT_DEDUPE_RADIUS = builder.comment("Do not spawn another medic/engineer if one is already this close (blocks).")
                .defineInRange("supportDedupeRadius", 32.0, 4.0, 128.0);
        ENGINEER_SEARCH_RADIUS = builder.comment("How far (blocks) engineers look for damaged vehicles to repair.")
                .defineInRange("engineerSearchRadius", 24.0, 4.0, 96.0);
        ENGINEER_REPAIR_PER_TREAT = builder.comment("Vehicle health restored each time an engineer repairs.")
                .defineInRange("engineerRepairPerTreat", 4.0, 0.5, 100.0);
        ENGINEER_REPAIR_COOLDOWN = builder.comment("Wait between repair actions, in AI ticks (about every other game tick).")
                .defineInRange("engineerRepairCooldown", 10, 1, 200);
        ENGINEER_REPAIR_SPEED_BOOST = builder.comment(
                        "Movement speed multiplier for an engineer while tasked with reaching a repair",
                        "target (1.0 = no boost). Without it a foot unit can be permanently outrun by the",
                        "tank it was sent to fix.")
                .defineInRange("engineerRepairSpeedBoost", 1.6, 1.0, 3.0);
        ENGINEER_SIDEARM_POOL = builder.comment("Pistols engineers may carry (TACZ gun ids).")
                .defineList("engineerSidearmPool", List.of("tacz:m9a1", "tacz:m1911", "tacz:glock_17"), SewvConfig::isValidResourceId);
        COMMANDER_SIDEARM_POOL = builder.comment("Pistols PMC Commanders may carry (TACZ gun ids).")
                .defineList("commanderSidearmPool", List.of("tacz:m9a1", "tacz:m1911", "tacz:glock_17"), SewvConfig::isValidResourceId);
        DRONE_MAX_PER_ENGINEER = builder.comment("Max attack drones each RU/US engineer may have out at once.")
                .defineInRange("droneMaxPerEngineer", 1, 0, 8);
        DRONE_DEPLOY_CHECK_INTERVAL_TICKS = builder.comment("How often (game ticks) an engineer considers launching another drone.")
                .defineInRange("droneDeployCheckIntervalTicks", 200, 20, 12000);
        DRONE_DEPLOY_CHANCE = builder.comment("Chance each check actually launches a drone.")
                .defineInRange("droneDeployChance", 0.2, 0.0, 1.0);
        DRONE_SCAN_ALTITUDE = builder.comment("How high (blocks) drones cruise above the ground while searching.")
                .defineInRange("droneScanAltitude", 20.0, 5.0, 60.0);
        DRONE_BROADCAST_RADIUS = builder.comment("How far (blocks) drone-related map/crew lookups reach.")
                .defineInRange("droneBroadcastRadius", 160.0, 16.0, 384.0);
        DRONE_SCAN_INTERVAL_TICKS = builder.comment("How often (game ticks) a diving drone looks for a new target.")
                .defineInRange("droneScanIntervalTicks", 20, 5, 200);
        DRONE_LEASH_RADIUS = builder.comment("Max distance (blocks) a wandering drone may stray from its engineer.")
                .defineInRange("droneLeashRadius", 200.0, 16.0, 512.0);
        AUTO_BOARD_ENABLED = builder.comment("Idle RU/US soldiers may climb into empty abandoned vehicles.")
                .define("autoBoardEnabled", true);
        AUTO_BOARD_SCAN_RADIUS = builder.comment("How far (blocks) they look for empty vehicles.")
                .defineInRange("autoBoardScanRadius", 32.0, 4.0, 128.0);
        AUTO_BOARD_MIN_HEALTH_FRACTION = builder.comment("Ignore wrecks below this health (0.25 = 25%). Derelicts should stay below this.")
                .defineInRange("autoBoardMinHealthFraction", 0.25, 0.0, 1.0);
        AUTO_BOARD_STEALS_PLAYER_VEHICLES = builder.comment("If on, RU/US may take vehicles you have driven before. Off by default.")
                .define("autoBoardStealsPlayerVehicles", false);
        AUTO_MAN_MORTAR_ENABLED = builder.comment("Idle RU/US soldiers may crew an empty, unclaimed mortar (same feature as autoBoardEnabled, for mortars).")
                .define("autoManMortarEnabled", true);
        AUTO_MAN_MORTAR_SCAN_RADIUS = builder.comment("How far (blocks) they look for empty mortars.")
                .defineInRange("autoManMortarScanRadius", 32.0, 4.0, 128.0);
        AUTO_ENTRENCH_ENABLED = builder.comment(
                        "Idle RU/US soldiers may claim nearby trench networks, emplacements, or free sandbags.")
                .define("autoEntrenchEnabled", true);
        AUTO_ENTRENCH_SCAN_RADIUS = builder.comment(
                        "How far (blocks) they look for trench cells, emplacements, or sandbags.")
                .defineInRange("autoEntrenchScanRadius", 48.0, 8.0, 128.0);
        VEHICLE_FORMATION_SPACING = builder.comment("Spacing (blocks) between vehicles in a formation.")
                .defineInRange("vehicleFormationSpacing", 12.0, 5.0, 32.0);
        VEHICLE_TARGET_SCAN_RADIUS = builder.comment("How far sideways (blocks) crewed vehicles look for enemies.")
                .defineInRange("vehicleTargetScanRadius", 96.0, 8.0, 128.0);
        VEHICLE_TARGET_SCAN_HEIGHT = builder.comment("How far up/down (blocks) crewed vehicles look for enemies.")
                .defineInRange("vehicleTargetScanHeight", 128.0, 4.0, 128.0);
        VEHICLE_TARGET_SCAN_INTERVAL_TICKS = builder.comment("How often (game ticks) crewed vehicles refresh their target list.")
                .defineInRange("vehicleTargetScanIntervalTicks", 30, 1, 200);
        VEHICLE_TARGET_REQUIRE_LOS = builder.comment("Crewed vehicles only lock enemies they can see (no wall hacks).")
                .define("vehicleTargetRequireLineOfSight", true);
        VEHICLE_ALLY_ASSIST_RANGE = builder.comment("How far (blocks) to count nearby allies when deciding to hold or fall back.")
                .defineInRange("vehicleAllyAssistRange", 128.0, 0.0, 256.0);
        STALEMATE_BREAKER_ENABLED = builder.comment("If a crew cannot hit its target for a while, move to a better angle.")
                .define("stalemateBreakerEnabled", true);
        STALEMATE_SILENCE_TICKS = builder.comment("How long (game ticks) without a hit before they reposition.")
                .defineInRange("stalemateSilenceTicks", 300, 40, 2400);
        VEHICLE_TERRAIN_AVOIDANCE = builder.comment("AI drivers try to avoid water, walls, and other hazards.")
                .define("vehicleTerrainAvoidance", true);
        KOMODO_RENDER_FIX_ENABLED = builder.comment(
                        "Client-only. Komodo's retained-rendering fallback (used for the first frame or two of a",
                        "newly seen vehicle model+texture, before its GPU-instanced path is baked) can call into",
                        "vanilla's DynamicTexture off the render thread, which crashes the client with an NPE on",
                        "replay. This makes that fallback no-op for that one frame instead of crashing when it is",
                        "not safely on the render thread. No known downside; turn off only to isolate a Komodo bug report.")
                .define("komodoRenderFixEnabled", true);
        PATROL_ROTATE_INTERVAL_TICKS = builder.comment("How long (game ticks) a patrol holds one spot before moving on.")
                .defineInRange("patrolRotateIntervalTicks", 3600, 200, 24000);
        IDLE_WANDER_ENABLED = builder.comment("Crewed vehicles with nothing to do may wander nearby.")
                .define("idleWanderEnabled", true);
        IDLE_WANDER_RADIUS = builder.comment("Max wander distance (blocks) from where they started waiting.")
                .defineInRange("idleWanderRadius", 16, 4, 64);
        UTILITY_REFRESH_INTERVAL_TICKS = builder.comment("How often (game ticks) a ground crew rethinks attack / hold / fall back.")
                .defineInRange("utilityRefreshIntervalTicks", 30, 5, 200);
        FACTION_ORGANIC_COMMS = builder.comment("RU/US crews can call mortars/TOWs/air support without holding a radio item.")
                .define("factionOrganicComms", true);
        SUPPORT_CALL_INTERVAL_TICKS = builder.comment("Minimum wait (game ticks) between support call attempts.")
                .defineInRange("supportCallIntervalTicks", 200, 20, 2400);
        OUTER_RING_ENABLED = builder.comment(
                        "Let crews notice enemies farther out than their normal scan range.",
                        "Awareness only — they will not open fire at that distance by themselves.")
                .define("outerRingEnabled", true);
        OUTER_RING_MAX_BLOCKS = builder.comment(
                        "Max distance (blocks) for that long-range awareness. Also limited by the server's view/simulation distance.")
                .defineInRange("outerRingMaxBlocks", 192.0, 96.0, 512.0);
        builder.pop();

        builder.push("command");
        COMMAND_GROUP_JOIN_RADIUS = builder.comment("How close (blocks) a vehicle must be to a battle group's centre to join it.")
                .defineInRange("commandGroupJoinRadius", 48.0, 8.0, 256.0);
        COMMAND_GROUP_LEAVE_RADIUS = builder.comment("How far (blocks) before a member leaves the group. Must be larger than join radius.")
                .defineInRange("commandGroupLeaveRadius", 64.0, 8.0, 256.0);
        COMMAND_GROUP_MAX_DIAMETER = builder.comment("Largest allowed size of a battle group (blocks across). Should be at least twice the leave radius.")
                .defineInRange("commandGroupMaxDiameter", 128.0, 16.0, 512.0);
        COMMAND_GROUP_MIN_SIZE = builder.comment("Minimum crewed vehicles to form a battle group. Loners keep fighting on their own.")
                .defineInRange("commandGroupMinSize", 2, 2, 32);
        COMMAND_MAX_UNITS = builder.comment("Max drivers the commander AI looks at in one pass (performance limit).")
                .defineInRange("commandMaxUnits", 64, 4, 256);
        COMMAND_ENGAGEMENT_RADIUS = builder.comment("Enemy forces must be this close (blocks) before battle groups form.")
                .defineInRange("commandEngagementRadius", 96.0, 16.0, 256.0);
        COMMAND_MARGIN = builder.comment("How much better a new commander must score to replace the current one.")
                .defineInRange("commandMargin", 0.15, 0.0, 2.0);
        INFLUENCE_CELL_SIZE = builder.comment("Grid cell size (blocks) for the rough who-controls-this-area map. Bigger cells = cheaper, coarser.")
                .defineInRange("influenceCellSize", 12.0, 8.0, 16.0);
        INFLUENCE_MAX_CELLS = builder.comment("Max cells that map may use. Huge battles automatically use bigger cells instead of more of them.")
                .defineInRange("influenceMaxCells", 256, 64, 1024);
        MIN_PLAY_TICKS = builder.comment("Minimum time (game ticks) a battle plan runs before another plan can replace it.",
                        "Broken plans can still cancel early.")
                .defineInRange("minPlayTicks", 200, 20, 2400);
        PLAY_SWITCH_MARGIN = builder.comment("How much better a new battle plan must score to replace the current one.")
                .defineInRange("playSwitchMargin", 10.0, 0.0, 100.0);
        builder.pop();

        builder.push("platoon");
        PLATOON_COHESION_RADIUS = builder.comment("Platoons try to stay within this many blocks of each other, unless tasked into a doctrine play.")
                .defineInRange("platoonCohesionRadius", 30.0, 8.0, 128.0);
        PLATOON_MAX_SIZE = builder.comment("Max members in one platoon (PMC infantry, or ground-vehicle crews — never mixed).")
                .defineInRange("platoonMaxSize", 4, 2, 12);
        PLATOON_MIN_SIZE = builder.comment("A platoon disbands once membership drops below this.")
                .defineInRange("platoonMinSize", 2, 2, 8);
        builder.pop();

        builder.push("flight_ai");
        HELI_ENGAGE_RADIUS = builder.comment(
                        "Preferred horizontal distance (blocks) for helicopter cannon/rocket attacks.",
                        "Missile attacks use heliMinStandoff and heliMaxDepressionDeg instead.")
                .defineInRange("heliEngageRadius", 32.0, 12.0, 64.0);
        HELI_MAX_DEPRESSION_DEG = builder.comment(
                        "Steepest nose-down angle (degrees) for missile attacks. Higher = can sit closer above the target.")
                .defineInRange("heliMaxDepressionDeg", 45.0, 20.0, 55.0);
        HELI_MIN_STANDOFF = builder.comment(
                        "Closest horizontal range (blocks) for helicopter missile attacks,",
                        "even when the target is on a tall peak.")
                .defineInRange("heliMinStandoff", 28.0, 16.0, 96.0);
        HELI_CHUNK_LOADING = builder.comment(
                        "Keep AI helicopters active even when no player is nearby (uses chunk tickets).",
                        "Default true (matches mortarChunkLoading). Existing installs keep whatever",
                        "value is already in tacz_sewv-common.toml until that file is deleted.")
                .define("heliChunkLoading", true);
        PLANE_CHUNK_LOADING = builder.comment(
                        "Keep AI planes active even when no player is nearby (uses chunk tickets).",
                        "Default true (matches mortarChunkLoading). Existing installs keep whatever",
                        "value is already in tacz_sewv-common.toml until that file is deleted.")
                .define("planeChunkLoading", true);
        PLANE_COMMAND_RADIUS = builder.comment("Max distance (blocks) for player orders to aircraft.",
                        "Doubles as the soft leash: past this a plane finishes its pass and returns to you.",
                        "Combat is abandoned outright at 1.5x this distance.",
                        "Must stay comfortably larger than planeEngageRadius: a plane repositions by flying",
                        "outbound until it is clear of its own engage bubble before turning back in, so a",
                        "leash near the bubble size recalls it in the middle of every run-in.")
                .defineInRange("planeCommandRadius", 1024.0, 32.0, 4096.0);
        PLANE_GUN_CONE_DEG = builder.comment(
                        "Widest angle (degrees) off the gun line at which a plane will fire guns, rockets",
                        "and bombs. This is a ceiling, not the gate itself: the plane works out the angle",
                        "at which the shot would still land inside the weapon's own blast radius at the",
                        "current range and uses that, so it holds fire far out and lets go up close.",
                        "It only binds in very close: an A-10 cannon (4-block blast) reaches 12 degrees",
                        "at 19 blocks, so past that the derived angle is what decides.")
                .defineInRange("planeGunConeDeg", 12.0, 1.0, 45.0);
        PLANE_MISSILE_CONE_DEG = builder.comment(
                        "Same ceiling for guided missiles, which steer out the rest after launch.",
                        "Doubles as the seeker cone the lock dwell below is measured against.")
                .defineInRange("planeMissileConeDeg", 15.0, 1.0, 45.0);
        PLANE_MISSILE_LOCK_TICKS = builder.comment(
                        "Game ticks a guided missile's seeker must be held on the target, unbroken, before",
                        "it will launch (20 = 1 second). Leaving the firing cone resets it to zero.",
                        "This is why a missile pass is flown as a shallow dive rather than a level overfly:",
                        "the nose has to stay pointed at the target for the whole count. 0 disables the",
                        "dwell and launches on the first tick the cone is satisfied, which is the old",
                        "behaviour and reliably threw missiles off during a slew.")
                .defineInRange("planeMissileLockTicks", 20, 0, 200);
        PLANE_MIN_CONE_DEG = builder.comment(
                        "Floor under the derived firing angle above. It is a numerical backstop and",
                        "nothing else, and it is set so that it does not bind anywhere inside the engage",
                        "bubble - geometry governs every shot you will ever see.",
                        "Do NOT raise this to make reluctant planes shoot. The miss distance of a shot",
                        "fired X degrees off at range R is R*tan(X), so a wide floor buys shots precisely",
                        "where they cannot land: at 2 degrees an A-10 may fire from 384 blocks and miss by",
                        "13, against a 4-block blast. The derived angle is instead a range gate in",
                        "disguise - it stays shut until the plane is close enough that the accuracy it can",
                        "actually hold puts the round inside the blast (7.6 degrees at 30 blocks, 5.1 at",
                        "45, 2.4 at 96). A plane that finishes passes without firing is a plane that is not",
                        "getting close or not lining up; look at planeEngageRadius and the run-in.",
                        "Note this must be re-checked against planeEngageRadius if that is raised: the",
                        "floor stops being harmless the moment the bubble reaches out past where it binds.")
                .defineInRange("planeMinConeDeg", 0.5, 0.5, 20.0);
        PLANE_AUTO_ROCKET_RANGE = builder.comment(
                        "AUTO ordnance: closer than this (blocks) to the target, a plane uses guns only.",
                        "Heavier stores need room to fall or to guide, so the closer the target the fewer",
                        "of them are eligible.")
                .defineInRange("planeAutoRocketRange", 40.0, 0.0, 320.0);
        PLANE_AUTO_HEAVY_RANGE = builder.comment(
                        "AUTO ordnance: bombs and guided missiles are only used from at least this far out.",
                        "Between this and planeAutoRocketRange the plane uses rockets.",
                        "This tracks the release geometry, not the engage bubble: a bomb let go from the",
                        "80-block run altitude falls for about 52 ticks and travels roughly 125 blocks",
                        "downrange at cruise, so anything nearer than that cannot be bombed at all.")
                .defineInRange("planeAutoHeavyRange", 128.0, 0.0, 1024.0);
        PLANE_BOMB_STICK = builder.comment(
                        "How many bombs a plane releases in one carpet run, spaced along its track.",
                        "1 is a single aimed drop.")
                .defineInRange("planeBombStick", 3, 1, 12);
        PLANE_BOMB_STICK_INTERVAL = builder.comment(
                        "Game ticks between bombs in a carpet stick. Wider spacing covers more ground and",
                        "concentrates less on the aim point. There is a hard floor of 10 ticks in code:",
                        "closer than that the impacts overlap into one blast instead of walking across the",
                        "target, so a smaller number here does nothing.")
                .defineInRange("planeBombStickIntervalTicks", 10, 1, 40);
        PLANE_BOMB_SIGHT_RADIUS = builder.comment(
                        "Smallest release window (blocks) a plane will ever bomb through - a FLOOR under the",
                        "window, not the window itself. The window is normally the bomb's own blast radius,",
                        "on the reasoning that a release is worth taking when the target will be inside the",
                        "explosion rather than only when the bomb will land on it; that is what absorbs the",
                        "few blocks of track error an AI pass cannot help and the distance a target drives",
                        "during the fall. A Mk 82's 22-block blast therefore gives a window far wider than",
                        "this, and this only binds for a store whose datapack declares no blast at all.",
                        "Raising it does not make planes bomb more accurately, it makes them release",
                        "earlier; if planes overfly without dropping, the run-in is what to look at, and",
                        "planeCombatDebug prints the sight error that says which part of it.")
                .defineInRange("planeBombSightRadius", 8.0, 1.0, 32.0);
        PLANE_ENGAGE_RADIUS = builder.comment(
                        "How far out (blocks) a plane rolls in on a target instead of just closing on it.",
                        "This is the single number that sets the scale a plane operates at: it is the length",
                        "of the straight run-in, and the run-in is where the aircraft has to get its nose",
                        "onto the target. A plane is pointed by hauling the whole hull round at a fraction of",
                        "a degree per tick, so a short bubble is a short line, and a short line is a pass",
                        "flown with the gun still swinging through the aim point.",
                        "Lowering this makes planes commit sooner and hit less.")
                .defineInRange("planeEngageRadius", 384.0, 32.0, 1024.0);
        PLANE_ATTACK_RUN_LENGTH = builder.comment(
                        "Length (blocks) of one straight attack run before the plane breaks off and turns back.",
                        "A floor, not a cap: the run is never cut shorter than the engage bubble it started",
                        "from, or it would end before the weapon's own release point.")
                .defineInRange("planeAttackRunLength", 400.0, 40.0, 1024.0);
        PLANE_MAX_ALTITUDE = builder.comment(
                        "Absolute world Y a plane will never fly above. This is a ceiling, not a cruise",
                        "height: the cruise band is measured above the ground under the aircraft, so over",
                        "high terrain it would otherwise stack on top of a mountain and put the aircraft in",
                        "the stratosphere - out of sight, out of the fight, and above the build limit in a",
                        "dimension with a low one. Every climb this AI commands stops here, and the",
                        "dimension's own build height (less a little headroom) binds it further.")
                .defineInRange("planeMaxAltitude", 300.0, 64.0, 1024.0);
        PLANE_DIVE_SNAP = builder.comment(
                        "Place a plane exactly on its attack line at the moment it rolls in on a diving",
                        "pass (guns and rockets; bombs and guided missiles are released from level flight",
                        "and are unaffected). The same trick the airport approach uses, and for the same",
                        "reason: a fixed wing is pointed a fraction of a degree per tick, so anything not",
                        "corrected before the run is still being corrected during it, and the burst goes",
                        "wide. Turn off if you would rather planes never be repositioned.")
                .define("planeDiveSnap", true);
        PLANE_LAND_TRANSIT_AGL = builder.comment(
                        "Height (blocks) above the highest ground on the way in that a landing plane flies",
                        "the approach pattern at, before it lines up with the strip.")
                .defineInRange("planeLandTransitAgl", 48.0, 16.0, 160.0);
        PLANE_LAND_FLARE_AGL = builder.comment("Height (blocks) above ground at which a landing plane flares.")
                .defineInRange("planeLandFlareAgl", 8.0, 2.0, 32.0);
        PLANE_LAND_FLARE_RADIUS = builder.comment(
                        "How close (blocks) to the pad the plane must also be before it flares.",
                        "Height alone used to make it flare over whatever it happened to be crossing.")
                .defineInRange("planeLandFlareRadius", 24.0, 8.0, 96.0);
        PLANE_LAND_SETTLE_RADIUS = builder.comment(
                        "How close (blocks) to the pad a touchdown counts as landed. Touching down further",
                        "out is treated as a missed approach and the plane goes around.")
                .defineInRange("planeLandSettleRadius", 8.0, 4.0, 64.0);
        AIRPORT_MIN_ASPECT_RATIO = builder.comment(
                        "Minimum long:short ratio for a player-defined PMC runway. Below this the strip",
                        "is rejected as not runway-shaped.")
                .defineInRange("airportMinAspectRatio", 3.0, 1.5, 20.0);
        AIRPORT_MIN_LENGTH_BLOCKS = builder.comment(
                        "Minimum long-side length (blocks) for a PMC runway. Matches the clear distance",
                        "DrivePlaneGoal already demands for takeoff.")
                .defineInRange("airportMinLengthBlocks", 64, 32, 512);
        AIRPORT_MAX_AREA_BLOCKS = builder.comment(
                        "Hard ceiling on runway footprint area (blocks). Rejects typo'd coordinates",
                        "before they scan millions of columns on the server thread.")
                .defineInRange("airportMaxAreaBlocks", 65536, 1024, 1048576);
        AIRPORT_LANDING_SEARCH_RADIUS = builder.comment(
                        "How far (blocks) from the AIRCRAFT to look for a cleared PMC airport when it is",
                        "ordered to land. The ordered point is only a fallback, so raise this rather than",
                        "clicking near the strip. 0 disables airport landings and keeps approach inference.")
                .defineInRange("airportLandingSearchRadius", 1024.0, 0.0, 16384.0);
        AIRPORT_ALIGNMENT_DISTANCE = builder.comment(
                        "Length (blocks) of the alignment line an aircraft is placed on before an airport",
                        "landing: the straight-in leg whose far end is this far back from the touchdown.")
                .defineInRange("airportAlignmentDistance", 170.0, 60.0, 512.0);
        AIRPORT_ALIGNMENT_SNAP_RADIUS = builder.comment(
                        "How close (blocks) an aircraft must get to the start of that alignment line before",
                        "it is placed onto it, pointed at the strip, and committed to the approach.")
                .defineInRange("airportAlignmentSnapRadius", 48.0, 8.0, 256.0);
        DUBINS_ALIGN_TOLERANCE_DEG = builder.comment(
                        "Heading error (degrees) off the alignment line's own axis within which an aircraft",
                        "is treated as already lined up and just flies straight in. Past this it flies a",
                        "turn (a Dubins arc, sized off the hull's own measured turn radius) onto the line",
                        "instead of arriving on the wrong heading and being placed straight.")
                .defineInRange("dubinsAlignToleranceDeg", 15.0, 5.0, 45.0);
        DUBINS_FALLBACK_MULTIPLIER = builder.comment(
                        "If the computed turn-in arc would be longer than this many times the straight-line",
                        "distance to the alignment line, the geometry is treated as degenerate (radius too",
                        "wide for how close the aircraft already is) and the turn is skipped in favour of the",
                        "ordinary straight run-in.")
                .defineInRange("dubinsFallbackMultiplier", 2.5, 1.5, 4.0);
        DUBINS_DEVIATION_THRESHOLD = builder.comment(
                        "How far (blocks) an aircraft may drift off its computed turn-in arc — from a nearby",
                        "explosion, for instance — before the arc is recomputed from where it actually is.")
                .defineInRange("dubinsDeviationThreshold", 16.0, 4.0, 64.0);
        // The three below are STARTING VALUES for a newly placed runway block, not live settings.
        // Segmentation belongs to a runway — a forward strip packing in light aircraft wants
        // nothing like a bomber base's spacing — so each one keeps its own, edited by the sliders
        // in its GUI. Changing these does not touch a runway that already exists.
        AIRPORT_SLOT_SIZE_FACTOR = builder.comment(
                        "Default parking slot length as a FRACTION of the runway's long side. Proportional",
                        "rather than a block count so one setting suits a 64-block strip and a 400-block",
                        "airbase; a floor of 8 blocks applies either way. Higher means fewer slots.")
                .defineInRange("airportSlotSizeFactor", 0.10, 0.02, 0.5);
        AIRPORT_SLOT_BUFFER_FACTOR = builder.comment(
                        "Default separation between adjacent parking slots, again as a fraction of runway",
                        "length (floor 2 blocks). The last slot needs no buffer behind it.")
                .defineInRange("airportSlotBufferFactor", 0.03, 0.0, 0.2);
        AIRPORT_EXTRA_TAKEOFF_FACTOR = builder.comment(
                        "Default extra takeoff room beyond the automatic baseline, as a fraction of runway",
                        "length. The baseline is interpolated from the runway's size and always applies;",
                        "this can only ADD to it, at the cost of parking slots.")
                .defineInRange("airportExtraTakeoffFactor", 0.0, 0.0, 0.5);
        AIRPORT_TAXI_SPEED = builder.comment(
                        "Blocks per tick an aircraft backs up the runway toward its parking slot after",
                        "touchdown.")
                .defineInRange("airportTaxiSpeed", 0.18, 0.02, 1.0);
        DEBUG_AUTO_PLANE_DEPLOY = builder.comment(
                        "Testing shortcut: Deploy Plane spawns a fresh aircraft, fuelled, armed and with a",
                        "full PMC crew already aboard, ignoring whatever the container was holding. Off, the",
                        "container is unpacked as it was packed — the aircraft it stored, in the state it",
                        "stored it, with nobody in it — and you crew it yourself.")
                .define("sewvDebugAutoPlaneDeploy", false);
        builder.pop();

        builder.push("mortar_ai");
        MORTAR_USE_DISTANCE = builder.comment("How close (blocks) a soldier must stand to crew a mortar.")
                .defineInRange("mortarUseDistance", 2.0, 1.0, 6.0);
        MORTAR_FIRE_COOLDOWN_TICKS = builder.comment("Minimum wait between mortar shots, in game ticks (20 = 1 second).")
                .defineInRange("mortarFireCooldownTicks", 60, 1, 1200);
        TYPE63_FIRE_COOLDOWN_TICKS = builder.comment(
                        "Minimum wait between Type-63 rocket shots, in game ticks (SBW uses 10).")
                .defineInRange("type63FireCooldownTicks", 10, 1, 1200);
        MORTAR_DISPERSION_RADIUS = builder.comment("How far (blocks) mortar shots may land off the aim point.")
                .defineInRange("mortarDispersionRadius", 3, 0, 16);
        FRIENDLY_FIRE_MORTAR_RADIUS = builder.comment(
                        "Mortars hold fire when the aimpoint is this close (blocks) to the owning player",
                        "or a friendly PMC. Should be at least mortarDispersionRadius. 0 disables.")
                .defineInRange("friendlyFireMortarRadius", 12.0, 0.0, 48.0);
        MORTAR_REQUIRES_AMMO = builder.comment("Mortars need shells in inventory and use one per shot.")
                .define("mortarRequiresAmmo", true);
        MORTAR_CHUNK_LOADING = builder.comment(
                        "Keep crewed mortars loaded during long-range fire missions so they keep firing",
                        "even when you are far away.")
                .define("mortarChunkLoading", true);
        ARTILLERY_CHUNK_LOADING = builder.comment(
                        "Keep crewed artillery vehicles loaded during long-range fire missions.")
                .define("artilleryChunkLoading", true);
        MORTAR_RADIO_RANGE = builder.comment(
                        "How far (blocks) radios can designate targets and find support crews.",
                        "Needs to be long enough for mortar range (raise toward ~2000 for the longest guns).")
                .defineInRange("mortarRadioRange", 768.0, 16.0, 2048.0);
        builder.pop();

        builder.push("voicelines");
        VEHICLE_VOICELINES_ENABLED = builder.comment("Play radio chatter from crews inside vehicles.")
                .define("vehicleVoicelinesEnabled", true);
        IDLE_VOICELINE_DELAY_TICKS = builder.comment("Quiet time (game ticks) before idle crew chatter starts.")
                .defineInRange("idleVoicelineDelayTicks", 320, 20, 12000);
        IDLE_VOICELINE_HEALTH_FRACTION = builder.comment("No idle chatter when the vehicle is below this health (0.3 = 30%).")
                .defineInRange("idleVoicelineHealthFraction", 0.3, 0.0, 1.0);
        builder.pop();

        builder.push("orderFeedback");
        // orderFailureDebug/targetVetoDebug moved to gamerules sewvOrderFailureDebug/
        // sewvTargetVetoDebug (both still default true — silent until something fails/refuses).
        TARGET_VETO_COOLDOWN_TICKS = builder.comment(
                        "Minimum game ticks between two reports of the SAME reason from the SAME unit.")
                .defineInRange("targetVetoCooldownTicks", 200, 20, 12000);
        builder.pop();

        builder.push("interaction");
        BOARD_SCAN_RADIUS = builder.comment("How far (blocks) nearby units can be selected for boarding and similar orders.")
                .defineInRange("boardScanRadius", 64.0, 8.0, 128.0);
        builder.pop();

        builder.push("map");
        MAP_INFANTRY_ENABLED = builder.comment("Also show soldiers on foot on the world map (not only vehicles).")
                .define("mapInfantryEnabled", true);
        MAP_SYNC_INTERVAL_TICKS = builder.comment("How often (game ticks) the server refreshes map markers.")
                .defineInRange("mapSyncIntervalTicks", 20, 5, 200);
        MAP_SPOT_RADIUS = builder.comment("How far (blocks) your side can spot enemy markers (via you or your crews).")
                .defineInRange("mapSpotRadius", 128.0, 0.0, 512.0);
        builder.pop();

        builder.push("sweep");
        SWEEP_QUIET_SECONDS = builder.comment(
                        "Seconds of no fighting before a Sweep & Advance counts the area clear and claims it.")
                .defineInRange("quietSeconds", 15, 1, 300);
        SWEEP_MAX_CHUNK_AREA = builder.comment(
                        "Largest map selection (width × height in chunks) allowed for Sweep & Advance.")
                .defineInRange("maxChunkArea", 256, 1, 1024);
        builder.pop();

        builder.push("invasion");
        UNLIMITED_TEAM_BASES = builder.comment(
                        "Allow placing more than two team bases per dimension (for map building).",
                        "Starting an invasion match still needs exactly two.")
                .define("unlimitedTeamBases", false);
        INVASION_HUD_TEAM_A_COLOR = builder.comment(
                        "Invasion HUD colour for team A (left base). Six hex digits, e.g. 5555FF.")
                .define("hudTeamAColor", "5555FF");
        INVASION_HUD_TEAM_B_COLOR = builder.comment(
                        "Invasion HUD colour for team B (right base). Six hex digits, e.g. FF5555.")
                .define("hudTeamBColor", "FF5555");
        INVASION_HUD_NEUTRAL_COLOR = builder.comment(
                        "Invasion HUD colour for unowned capture points. Six hex digits, e.g. AAAAAA.")
                .define("hudNeutralColor", "AAAAAA");
        builder.pop();

        builder.push("doctrine");
        builder.comment("Default fighting style sliders for RU, US, and PMC commanders (-3 to +3 on each axis).");
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

        builder.push("extermination");
        TRIPOD_SHIELD_ENABLED = builder.comment(
                        "If the Extermination mod is installed: pods get a shield against bullets and shells.",
                        "Melee still hurts them. Shield breaks after soaking tripodShieldBreakDamage.",
                        "Does nothing if Extermination is not installed.")
                .define("tripodShieldEnabled", true);
        TRIPOD_HP_MULTIPLIER = builder.comment(
                        "Multiply Tripod max health when it spawns (2.0 = double health).")
                .defineInRange("tripodHpMultiplier", 2.0, 1.0, 10.0);
        TRIPOD_SHIELD_BREAK_DAMAGE = builder.comment(
                        "Total ranged damage the shield can soak before it breaks.",
                        "While the shield is up those hits do no HP damage; after it breaks, guns work normally.")
                .defineInRange("tripodShieldBreakDamage", 400.0, 1.0, 100000.0);
        TRIPOD_SHIELD_REGEN_TICKS = builder.comment(
                        "Wait after the shield breaks before it comes back, in game ticks (20 = 1 second).",
                        "0 = shield stays down for the rest of the fight.")
                .defineInRange("tripodShieldRegenTicks", 600, 0, 72000);
        TRIPOD_SHIELD_FLARE_TICKS = builder.comment(
                        "How long the hit-spark effect lasts after a blocked shot, in game ticks (~8 ≈ 0.4 s).")
                .defineInRange("tripodShieldFlareTicks", 8, 1, 100);
        // tripodShieldFlareDebugAlwaysOn/tripodShieldDebugWireframe moved to gamerules
        // sewvTripodShieldFlareAlwaysOn/sewvTripodShieldWireframe.
        TRIPOD_SHIELD_AXIS_SCALE = builder.comment(
                        "Size of the shield bubble for sparks/debug outline (1.0 = matches the hitbox).")
                .defineInRange("tripodShieldAxisScale", 1.0, 0.25, 4.0);
        INVASION_POD_AVOID_RADIUS = builder.comment(
                        "With gamerule sewvInvasionOverrides on: AI vehicles stay this many blocks away",
                        "from Extermination combat pods.")
                .defineInRange("invasionPodAvoidRadius", 48.0, 8.0, 128.0);
        HEAT_RAY_SPEED = builder.comment(
                        "Speed of Extermination heat-ray shots. The original mod uses 3.5; default 10.5 is triple that.")
                .defineInRange("heatRaySpeed", 10.5, 3.5, 40.0);
        builder.pop();

        builder.push("tacz_ballistics");
        TACZ_BALLISTIC_TRANSLATION_ENABLED = builder.comment(
                        "TaCZ bullet damage against SuperbWarfare vehicles is rescaled to match an",
                        "equivalent SBW weapon before the hull's own armor tables apply, instead of TaCZ's",
                        "raw (much smaller) numbers. Off restores TaCZ's native damage against vehicles.")
                .define("tacZBallisticTranslationEnabled", true);
        TACZ_BALLISTIC_GLOBAL_SCALE = builder.comment(
                        "Extra multiplier applied on top of the per-category translation factor.",
                        "1.0 = translation table numbers as shipped/configured.")
                .defineInRange("tacZBallisticGlobalScale", 1.0, 0.0, 10.0);
        builder.pop();

        builder.push("tree_felling");
        VEHICLE_TREE_FELLING_ENABLED = builder.comment(
                        "If the Enhanced Falling Trees mod is installed: ground (wheel/track) vehicles fell whole",
                        "trees they drive into instead of treating every trunk as a wall. Does nothing if",
                        "Enhanced Falling Trees is not installed.")
                .define("vehicleTreeFellingEnabled", true);
        VEHICLE_TREE_FELL_DAMAGE = builder.comment(
                        "Self-damage a vehicle takes per tree felled (not per block, not per tick).")
                .defineInRange("vehicleTreeFellDamage", 1.5, 0.0, 20.0);
        VEHICLE_TREE_PATH_MALUS = builder.comment(
                        "Extra A* path cost for routing through a fellable tree, so a clear route is preferred",
                        "when one exists nearby but a forest is never impassable. Comparable scale to the",
                        "existing ford/slope path costs (a few points), never infinite.")
                .defineInRange("vehicleTreePathMalus", 5.0, 0.0, 50.0);
        VEHICLE_TREE_SENSOR_DANGER = builder.comment(
                        "Steering danger (0-1) a fellable tree registers as in the moment-to-moment avoidance fan.",
                        "Must stay below 1.0 (hard-blocked) or trees become impassable walls again regardless of",
                        "vehicleTreeFellingEnabled.")
                .defineInRange("vehicleTreeSensorDanger", 0.6, 0.0, 0.99);
        VEHICLE_TREE_FELLING_EXEMPT_GIANT_TRUNKS = builder.comment(
                        "Multi-block trunks (2x2 or larger — every vanilla dark oak tree, and jungle mega-trees)",
                        "are never felled and stay hard obstacles, since a vehicle should not be able to flatten",
                        "an entire dark oak forest by driving through it. Turn off to fell those too.")
                .define("vehicleTreeFellingExemptGiantTrunks", true);
        VEHICLE_TREE_CONTACT_TICKS = builder.comment(
                        "A hull must keep touching the same tree for this many CONSECUTIVE game ticks (20 = 1s)",
                        "before it falls — a hull that only clips a tree in passing does not fell it. Contact",
                        "must be unbroken; losing touch even for one tick resets that tree's timer to zero.")
                .defineInRange("vehicleTreeContactTicks", 35, 0, 200);
        builder.pop();

        SPEC = builder.build();
    }

    private SewvConfig() {}

    private static boolean isValidResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
