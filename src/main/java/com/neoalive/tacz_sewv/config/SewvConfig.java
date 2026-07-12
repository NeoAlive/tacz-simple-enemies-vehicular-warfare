package com.neoalive.tacz_sewv.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class SewvConfig {

    public static final ForgeConfigSpec SPEC;

    // Event spawning
    public static final ForgeConfigSpec.BooleanValue TANKS_IN_EVENTS;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_RU;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_US;

    // Vehicle pools — entity ids; one is picked at random per spawn
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_VEHICLE_POOL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_VEHICLE_POOL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_VEHICLE_POOL;

    // Crew AI behavior
    public static final ForgeConfigSpec.IntValue AI_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue WEAPON_SWITCH_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue SMOKE_BLOCK_RADIUS;

    // Mounted-crew target scan (cylinder around the vehicle)
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_HEIGHT;
    public static final ForgeConfigSpec.IntValue VEHICLE_TARGET_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TARGET_REQUIRE_LOS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_ALLY_ASSIST_RANGE;

    // Terrain avoidance (look-ahead sensor while driving)
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TERRAIN_AVOIDANCE;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_LOOKAHEAD_DISTANCE;
    public static final ForgeConfigSpec.IntValue VEHICLE_MAX_SAFE_DROP;

    // Helicopter/flight AI
    public static final ForgeConfigSpec.DoubleValue HELI_CRUISE_ALTITUDE;
    public static final ForgeConfigSpec.DoubleValue HELI_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue HELI_ALT_DEADBAND;
    public static final ForgeConfigSpec.DoubleValue HELI_CRUISE_SPEED;

    // Player interaction
    public static final ForgeConfigSpec.DoubleValue BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("event_vehicles");

        TANKS_IN_EVENTS = builder
                .comment("Allow RU/US tanks to (very rarely) spawn in combat events.")
                .define("tanksInEvents", true);

        TANK_SPAWN_CHANCE_RU = builder
                .comment("Chance (0.0-1.0) for an RU tank to spawn when a combat event fires. Keep it LOW for rarity.")
                .defineInRange("tankSpawnChanceRu", 0.02, 0.0, 1.0);

        TANK_SPAWN_CHANCE_US = builder
                .comment("Chance (0.0-1.0) for a US tank to spawn when a combat event fires. Keep it LOW for rarity.")
                .defineInRange("tankSpawnChanceUs", 0.02, 0.0, 1.0);

        builder.pop();

        builder.push("vehicle_pools");

        RU_VEHICLE_POOL = builder
                .comment("Vehicle entity ids RU crews can spawn with (e.g. \"superbwarfare:t_90a\").",
                         "List several to have one picked at random per spawn.")
                .defineList("ruVehiclePool", List.of("superbwarfare:t_90a"), SewvConfig::isValidVehicleId);

        US_VEHICLE_POOL = builder
                .comment("Vehicle entity ids US crews can spawn with (e.g. \"superbwarfare:m_1a_2\").",
                         "List several to have one picked at random per spawn.")
                .defineList("usVehiclePool", List.of("superbwarfare:m_1a_2"), SewvConfig::isValidVehicleId);

        PMC_VEHICLE_POOL = builder
                .comment("Vehicle entity ids for /sewv spawn pmctank (player-commandable crew).",
                         "List several to have one picked at random per spawn.")
                .defineList("pmcVehiclePool", List.of("superbwarfare:t_90a"), SewvConfig::isValidVehicleId);

        builder.pop();

        builder.push("crew_ai");

        AI_FIRE_COOLDOWN_TICKS = builder
                .comment("Minimum ticks between shots for an AI-crewed vehicle weapon (20 ticks = 1 second).")
                .defineInRange("aiFireCooldownTicks", 25, 1, 200);

        WEAPON_SWITCH_COOLDOWN_TICKS = builder
                .comment("Minimum ticks between AI weapon switches, prevents rapid cannon/MG flip-flopping.")
                .defineInRange("weaponSwitchCooldownTicks", 40, 1, 200);

        SMOKE_BLOCK_RADIUS = builder
                .comment("How close (in blocks) a smoke decoy must be to an AI crew's line of fire to block the shot.",
                         "Larger = smoke screens are wider and more protective. Only affects AI-crewed vehicles.")
                .defineInRange("smokeBlockRadius", 6.0, 1.0, 16.0);

        VEHICLE_TARGET_SCAN_RADIUS = builder
                .comment("Horizontal radius (in blocks) of the cylindrical target scan used by mounted AI crews.",
                         "Replaces the vanilla follow-range scan, which is far too short for vehicle engagement ranges.",
                         "Larger = crews spot enemies farther out, but each scan touches more of the world (perf cost).")
                .defineInRange("vehicleTargetScanRadius", 48.0, 8.0, 128.0);

        VEHICLE_TARGET_SCAN_HEIGHT = builder
                .comment("Total height (in blocks) of the target-scan cylinder, centered on the vehicle.",
                         "Keeping it flat is the cheap-and-effective shape for ground vehicles: wide reach without",
                         "paying to scan sky and caves. Raise it if enemies on tall cliffs should be engaged.")
                .defineInRange("vehicleTargetScanHeight", 24.0, 4.0, 128.0);

        VEHICLE_TARGET_SCAN_INTERVAL_TICKS = builder
                .comment("Ticks between target scans per crew member (20 = 1 second).",
                         "Larger = cheaper, but crews react slower to new threats.")
                .defineInRange("vehicleTargetScanIntervalTicks", 20, 1, 200);

        VEHICLE_TARGET_REQUIRE_LOS = builder
                .comment("Require line of sight before a mounted crew locks a scanned target.",
                         "Disabling skips the visibility raycasts (cheaper) but lets crews acquire enemies through cover.")
                .define("vehicleTargetRequireLineOfSight", true);

        VEHICLE_ALLY_ASSIST_RANGE = builder
                .comment("Range (in blocks) within which an idle AI vehicle crew notices an allied vehicle in combat",
                         "and drives to support it, stopping once inside the ally's comfortable ring. 0 disables it.",
                         "The check runs on the vehicleTargetScanIntervalTicks cadence, so it shares that perf knob.")
                .defineInRange("vehicleAllyAssistRange", 64.0, 0.0, 256.0);

        VEHICLE_TERRAIN_AVOIDANCE = builder
                .comment("Look ahead while driving and steer AI vehicles around water, deep drops (ravines/cliffs)",
                         "and lava instead of ploughing straight into them. Disabling restores the old behavior",
                         "of driving in a straight line at the destination.")
                .define("vehicleTerrainAvoidance", true);

        VEHICLE_LOOKAHEAD_DISTANCE = builder
                .comment("How far ahead (in blocks) the terrain sensor probes for hazards.",
                         "This is the performance knob: shorter = cheaper but reacts later (may clip a hazard at speed),",
                         "longer = earlier, wider avoidance at more block lookups per driving tick.")
                .defineInRange("vehicleLookaheadDistance", 5.0, 1.0, 16.0);

        VEHICLE_MAX_SAFE_DROP = builder
                .comment("Vertical drop (in blocks) an AI vehicle will still drive down. Drops deeper than this are",
                         "treated as a cliff and avoided. Matches the pathfinder's fall tolerance (vanilla default 3),",
                         "so ordinary small step-downs are not mistaken for ravines.")
                .defineInRange("vehicleMaxSafeDrop", 3, 1, 16);

        builder.pop();

        builder.push("flight_ai");

        HELI_CRUISE_ALTITUDE = builder
                .comment("Flight level (in blocks) above the TAKEOFF ORIGIN that an AI helicopter climbs to",
                         "and then holds as a consistent Y level for the whole flight. Constrained to 30-50",
                         "so the aircraft always clears trees/buildings but stays in engagement reach.")
                .defineInRange("heliCruiseAltitude", 35.0, 30.0, 50.0);

        HELI_ENGAGE_RADIUS = builder
                .comment("Horizontal standoff (in blocks) an AI helicopter holds from a combat target while",
                         "aiming: beyond it the aircraft closes in, inside it the aircraft holds and pitches",
                         "its nose down onto the target so its weapons bear.")
                .defineInRange("heliEngageRadius", 28.0, 12.0, 64.0);

        HELI_ALT_DEADBAND = builder
                .comment("Altitude-hold tolerance (in blocks). The helicopter only applies climb/descend",
                         "collective when it is more than this far off its target height, so it settles into",
                         "a stable hover instead of hunting up and down. Smaller = tighter but twitchier.")
                .defineInRange("heliAltDeadband", 2.5, 0.5, 8.0);

        HELI_CRUISE_SPEED = builder
                .comment("Target horizontal cruise speed (blocks/tick) an AI helicopter flies a leg at.",
                         "The pilot brakes toward this as a ceiling and eases below it on approach so it",
                         "decelerates onto the destination instead of overshooting. Lower = gentler, safer.")
                .defineInRange("heliCruiseSpeed", 0.6, 0.1, 2.0);

        builder.pop();

        builder.push("interaction");

        BOARD_SCAN_RADIUS = builder
                .comment("Radius (in blocks) around the player searched for owned units when pressing the board/dismount key.")
                .defineInRange("boardScanRadius", 64.0, 8.0, 128.0);

        SHOW_ORDER_FEEDBACK = builder
                .comment("Show an action-bar confirmation when a board/dismount order is issued.")
                .define("showOrderFeedback", true);

        builder.pop();

        SPEC = builder.build();
    }

    // Config-load validation only checks the id is well-formed; whether the entity
    // type actually exists is checked at spawn time (registries aren't ready here).
    private static boolean isValidVehicleId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
