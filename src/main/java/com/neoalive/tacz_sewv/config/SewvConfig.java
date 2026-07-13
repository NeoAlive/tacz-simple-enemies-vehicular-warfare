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
    public static final ForgeConfigSpec.DoubleValue AI_FIRE_ASSIST_CONE_DEG;
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
    public static final ForgeConfigSpec.IntValue HELI_WEAPON_SWITCH_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue HELI_ATTACK_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue HELI_CHUNK_LOADING;

    // Player interaction
    public static final ForgeConfigSpec.DoubleValue BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("event_vehicles");

        TANKS_IN_EVENTS = builder
                .comment("Allow RU/US tanks to (by default very rarely) spawn in combat events.")
                .define("tanksInEvents", true);

        TANK_SPAWN_CHANCE_RU = builder
                .comment("Chance (0.0-1.0) for an RU tank to spawn when a combat event occours. Keep it LOW for rarity.")
                .defineInRange("tankSpawnChanceRu", 0.02, 0.0, 1.0);

        TANK_SPAWN_CHANCE_US = builder
                .comment("Chance (0.0-1.0) for a US tank to spawn when a combat event occours. Keep it LOW for rarity.")
                .defineInRange("tankSpawnChanceUs", 0.02, 0.0, 1.0);

        builder.pop();

        builder.push("vehicle_pools");

        RU_VEHICLE_POOL = builder
                .comment("Vehicle entity ids RU crews can spawn with (e.g. \"superbwarfare:t_90a\").",
                         "List several to have one picked at random per spawn. (Ground Vehicles and Helicopters are supported.)",
                         "(e.g. \"superbwarfare:mi_28\")")
                .defineList("ruVehiclePool", List.of("superbwarfare:t_90a", "superbwarfare:bmp_2", "superbwarfare:mi_28"), SewvConfig::isValidVehicleId);

        US_VEHICLE_POOL = builder
                .comment("Vehicle entity ids US crews can spawn with (e.g. \"superbwarfare:m_1a_2\").",
                         "List several to have one picked at random per spawn. (Ground Vehicles and Helicopters are supported.)",
                         "(e.g. \"superbwarfare:ah_6\")")
                .defineList("usVehiclePool", List.of("superbwarfare:m_1a_2", "superbwarfare:bradley", "superbwarfare:ah_6"), SewvConfig::isValidVehicleId);

        PMC_VEHICLE_POOL = builder
                .comment("Vehicle entity ids for debug PMC units spawning",
                         "List several to have one picked at random per spawn. (Ground Vehicles and Helicopters are supported.)",
                         "(e.g. \"superbwarfare:ah_6\")")
                .defineList("pmcVehiclePool", List.of("superbwarfare:t_90a", "superbwarfare:ah_6"), SewvConfig::isValidVehicleId);

        builder.pop();

        builder.push("crew_ai");

        AI_FIRE_COOLDOWN_TICKS = builder
                .comment("Minimum ticks between shots for an AI-crewed vehicle weapon (20 ticks = 1 second).")
                .defineInRange("aiFireCooldownTicks", 5, 1, 200);

        WEAPON_SWITCH_COOLDOWN_TICKS = builder
                .comment("Minimum ticks between AI weapon switches, prevents rapid cannon/MG spam.")
                .defineInRange("weaponSwitchCooldownTicks", 5, 1, 200);

        AI_FIRE_ASSIST_CONE_DEG = builder
                .comment("Defines the radius of accuracy a helicopter should prefer. Lower numbers makes the helicopter's movement less chaotic and unpredictable but also less likely to fire with Helicopter weapons")
                .defineInRange("aiFireAssistConeDeg", 12.0, 4.0, 30.0);

        SMOKE_BLOCK_RADIUS = builder
                .comment("How close (in blocks) a smoke decoy must be to an AI crew's line of fire to block the shot.",
                         "Larger = smoke screens are wider and more protective.")
                .defineInRange("smokeBlockRadius", 6.0, 1.0, 16.0);

        VEHICLE_TARGET_SCAN_RADIUS = builder
                .comment("Horizontal radius (in blocks) of the cylindrical target scan used by mounted AI crews.",
                         "Replaces the vanilla follow-range scan, which is far too short for vehicle engagement ranges.",
                         "Larger = crews spot enemies farther out, but each scan touches more of the world (Lower this for performance).")
                .defineInRange("vehicleTargetScanRadius", 96.0, 8.0, 128.0);

        VEHICLE_TARGET_SCAN_HEIGHT = builder
                .comment("Total height (in blocks) of the target-scan cylinder, centered on the vehicle.",
                         "Raise it if enemies on tall cliffs should be engaged.")
                .defineInRange("vehicleTargetScanHeight", 128, 4.0, 128.0);

        VEHICLE_TARGET_SCAN_INTERVAL_TICKS = builder
                .comment("Ticks between target scans per crew member (20 = 1 second).",
                         "Higher number = cheaper, but crews react slower to threats.")
                .defineInRange("vehicleTargetScanIntervalTicks", 20, 1, 200);

        VEHICLE_TARGET_REQUIRE_LOS = builder
                .comment("If vehicles should use LineOfSight. Disable to squeeze much more performance, keep enabled for realism.")
                .define("vehicleTargetRequireLineOfSight", true);

        VEHICLE_ALLY_ASSIST_RANGE = builder
                .comment("Range a vehicle uses to scan for allies in combat")
                .defineInRange("vehicleAllyAssistRange", 128.0, 0.0, 256.0);

        VEHICLE_TERRAIN_AVOIDANCE = builder
                .comment("Look ahead while driving and steer AI vehicles around water, deep drops (ravines/cliffs)",
                         "and lava instead of ploughing straight into them. Disabling restores legacy behavior",
                         "of driving in a straight line at the destination.")
                .define("vehicleTerrainAvoidance", true);

        VEHICLE_LOOKAHEAD_DISTANCE = builder
                .comment("How far ahead (in blocks) the terrain sensor probes for hazards.",
                         "Higher numbers = scans ahead for obstacles at the cost of performance.")
                .defineInRange("vehicleLookaheadDistance", 5.0, 1.0, 16.0);

        VEHICLE_MAX_SAFE_DROP = builder
                .comment("Vertical drop (in blocks) an AI vehicle will still drive down. Drops deeper than this are",
                         "treated as a cliff and avoided. Matches the pathfinder's fall tolerance (vanilla default 3),",
                         "so ordinary small step-downs are not mistaken for ravines.")
                .defineInRange("vehicleMaxSafeDrop", 8, 1, 16);

        builder.pop();

        builder.push("flight_ai");

        HELI_CRUISE_ALTITUDE = builder
                .comment("The average amount of blocks an helicopter should hover in the air in relation to the ground")
                .defineInRange("heliCruiseAltitude", 35.0, 30.0, 50.0);

        HELI_ENGAGE_RADIUS = builder
                .comment("Horizontal standoff (in blocks) a NPC helicopter holds from a combat target while",
                         "aiming: beyond it the aircraft closes in, inside it the aircraft holds and pitches",
                         "its nose down onto the target so its weapons bear.")
                .defineInRange("heliEngageRadius", 32.0, 12.0, 64.0);

        HELI_ALT_DEADBAND = builder
                .comment("Altitude-hold tolerance (in blocks). The NPC helicopter only applies climb/descend",
                         "collective when it is more than this far off its target height, so it settles into",
                         "a stable hover instead of hunting up and down. Smaller = tighter but twitchier.")
                .defineInRange("heliAltDeadband", 2.5, 0.5, 8.0);

        HELI_CRUISE_SPEED = builder
                .comment("Target horizontal cruise speed (blocks/tick) an helicopter flies a leg at.",
                         "The pilot brakes toward this as a ceiling and eases below it on approach so it",
                         "decelerates onto the destination instead of overshooting. Lower = gentler, safer.")
                .defineInRange("heliCruiseSpeed", 0.6, 0.1, 2.0);

        HELI_WEAPON_SWITCH_INTERVAL_TICKS = builder
                .comment("Ticks between weapon switches for an helicopter crew in combat (20 = 1 second).")
                .defineInRange("heliWeaponSwitchIntervalTicks", 60, 1, 1200);

        HELI_ATTACK_HEIGHT = builder
                .comment("Height (in blocks) above the TARGET an AI helicopter holds while aiming in combat.",
                         "Lower = shallower dive needed to bring fixed weapons to bear (fires much more",
                         "reliably) but a more exposed, terrain-hugging attack profile. Outside the engage",
                         "ring the aircraft still transits at its normal cruise flight level.")
                .defineInRange("heliAttackHeight", 15.0, 8.0, 40.0);

        HELI_CHUNK_LOADING = builder
                .comment("If enabled, helicopters will keep flying even if chunks are unloaded. Can cause performance issues if many helicopters are flying at once.")
                .define("heliChunkLoading", false);

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
