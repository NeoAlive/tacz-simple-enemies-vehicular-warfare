package com.neoalive.tacz_sewv.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.List;

public class SewvConfig {

    public static final ForgeConfigSpec SPEC;

    // Event spawning
    public static final ForgeConfigSpec.BooleanValue TANKS_IN_EVENTS;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_RU;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE_US;
    public static final ForgeConfigSpec.BooleanValue CONVOY_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue CONVOY_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue CONVOY_FAILURE_MULTIPLIER;

    // Mortar shelling event (an RU/US battery sets up out of sight and shells your base)
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

    // Derelict vehicle event (a knocked-out hull with survivors camped around it)
    public static final ForgeConfigSpec.BooleanValue DERELICT_EVENTS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue DERELICT_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue DERELICT_FAILURE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue DERELICT_HEALTH_FRACTION;
    public static final ForgeConfigSpec.IntValue DERELICT_GUARDS;
    public static final ForgeConfigSpec.IntValue DERELICT_AMMO_COUNT;

    // Vehicle pools — entity ids; one is picked at random per spawn
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_VEHICLE_POOL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_VEHICLE_POOL;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_VEHICLE_POOL;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_AMMO_FALLBACK;

    // Armor issued to units on spawn (any unit, mounted or on foot)
    public static final ForgeConfigSpec.BooleanValue NPC_ARMOR_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_ARMOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_ARMOR;

    // Structure-spawned vehicles (berezka_api soft-compat)
    public static final ForgeConfigSpec.BooleanValue STRUCTURE_VEHICLES_ENABLED;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_MAX_COUNT;
    public static final ForgeConfigSpec.IntValue STRUCTURE_VEHICLE_RAMP_DAYS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> RU_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> US_VEHICLE_STRUCTURES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PMC_VEHICLE_STRUCTURES;

    // Crew AI behavior
    public static final ForgeConfigSpec.IntValue AI_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue WEAPON_SWITCH_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue AI_FIRE_ASSIST_CONE_DEG;
    public static final ForgeConfigSpec.DoubleValue SMOKE_BLOCK_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<String> AI_AIM_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue AI_AIM_SPREAD_DEG;

    // IFVs (a hull that carries a dismount squad rather than just a crew)
    public static final ForgeConfigSpec.BooleanValue IFV_DISMOUNTS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> IFV_NAME_CLUES;

    // Anti-tank weapons issued to an RU/US dismount squad
    public static final ForgeConfigSpec.ConfigValue<String> AT_WEAPON_RU;
    public static final ForgeConfigSpec.ConfigValue<String> AT_WEAPON_US;
    public static final ForgeConfigSpec.DoubleValue AT_SECOND_GUNNER_CHANCE;
    public static final ForgeConfigSpec.IntValue AT_BACKUP_AMMO;
    public static final ForgeConfigSpec.DoubleValue AT_ENGAGE_RANGE;
    public static final ForgeConfigSpec.IntValue AT_AIM_TICKS;

    // PMC medics (heal each other out of contact with a SuperbWarfare medical kit)
    public static final ForgeConfigSpec.BooleanValue MEDIC_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MEDIC_SEARCH_RADIUS;

    // Scavenging an abandoned hull (RU/US only — a PMC boards on the player's order instead)
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue AUTO_BOARD_MIN_HEALTH_FRACTION;
    public static final ForgeConfigSpec.BooleanValue AUTO_BOARD_STEALS_PLAYER_VEHICLES;

    // Vehicle formations (player-designated wedge/column on a fixed cardinal)
    public static final ForgeConfigSpec.DoubleValue VEHICLE_FORMATION_SPACING;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_FORMATION_ARRIVE_RADIUS;

    // Mounted-crew target scan (cylinder around the vehicle)
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_TARGET_SCAN_HEIGHT;
    public static final ForgeConfigSpec.IntValue VEHICLE_TARGET_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TARGET_REQUIRE_LOS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_ALLY_ASSIST_RANGE;

    // Stalemate breaker (a crew that holds a target it cannot hit repositions itself)
    public static final ForgeConfigSpec.BooleanValue STALEMATE_BREAKER_ENABLED;
    public static final ForgeConfigSpec.IntValue STALEMATE_SILENCE_TICKS;

    // Terrain avoidance (look-ahead sensor while driving)
    public static final ForgeConfigSpec.BooleanValue VEHICLE_TERRAIN_AVOIDANCE;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_LOOKAHEAD_DISTANCE;

    // Patrol order (a ground vehicle wanders valid ground inside a circle)
    public static final ForgeConfigSpec.IntValue PATROL_ROTATE_INTERVAL_TICKS;

    // Helicopter/flight AI
    public static final ForgeConfigSpec.DoubleValue HELI_ENGAGE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue HELI_ALT_DEADBAND;
    public static final ForgeConfigSpec.DoubleValue HELI_CRUISE_SPEED;
    public static final ForgeConfigSpec.IntValue HELI_WEAPON_SWITCH_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue HELI_ATTACK_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue HELI_CHUNK_LOADING;

    // Mortar crew AI (a unit stands beside the mortar, it has no seats to ride)
    public static final ForgeConfigSpec.DoubleValue MORTAR_USE_DISTANCE;
    public static final ForgeConfigSpec.IntValue MORTAR_APPROACH_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.IntValue MORTAR_FIRE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue MORTAR_DISPERSION_RADIUS;
    public static final ForgeConfigSpec.BooleanValue MORTAR_REQUIRES_AMMO;
    public static final ForgeConfigSpec.BooleanValue MORTAR_CHUNK_LOADING;
    public static final ForgeConfigSpec.DoubleValue MORTAR_RADIO_RANGE;
    public static final ForgeConfigSpec.BooleanValue MORTAR_DEBUG_LOGGING;

    // Unit voicelines while crewing a vehicle
    public static final ForgeConfigSpec.BooleanValue VEHICLE_VOICELINES_ENABLED;

    // Player interaction
    public static final ForgeConfigSpec.DoubleValue BOARD_SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SHOW_ORDER_FEEDBACK;

    // Faction colours on SuperbWarfare's hover overlay
    public static final ForgeConfigSpec.BooleanValue FACTION_COLORS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_RU;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_US;
    public static final ForgeConfigSpec.ConfigValue<String> COLOR_PMC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("event_vehicles");

        TANKS_IN_EVENTS = builder
                .comment("Allow RU/US tanks to (by default very rarely) spawn in combat events.")
                .define("tanksInEvents", true);

        TANK_SPAWN_CHANCE_RU = builder
                .comment("Chance (0.0-1.0) for an RU tank to spawn when a combat event occurs.",
                         "This is CONDITIONAL: it is rolled only once SimpleEnemyMod's far_combat event has",
                         "already fired, so the rate you actually see is this number TIMES that event's own",
                         "chance. It therefore needs to look far too high to come out about right — the old",
                         "0.02 default worked out to a tank every several hours of play.")
                .defineInRange("tankSpawnChanceRu", 0.12, 0.0, 1.0);

        TANK_SPAWN_CHANCE_US = builder
                .comment("Chance (0.0-1.0) for a US tank to spawn when a combat event occurs.",
                         "Conditional on far_combat having fired, exactly like tankSpawnChanceRu.")
                .defineInRange("tankSpawnChanceUs", 0.12, 0.0, 1.0);

        CONVOY_EVENTS_ENABLED = builder
                .comment("Enable the standalone convoy event. Each convoy is RU or US only, never PMC.")
                .define("convoyEventsEnabled", true);

        CONVOY_BASE_CHANCE = builder
                .comment("Initial chance (0.0-1.0) for the convoy event on each event cycle.",
                         "SimpleEnemyMod rolls every registered event once per 1200 ticks (60 SECONDS), so this is",
                         "a per-minute chance, and its chance grows by convoyFailureMultiplier after each miss",
                         "until it fires. For scale, SEM's own far_combat runs 0.06 base / 0.12 escalation.")
                .defineInRange("convoyBaseChance", 0.06, 0.0, 1.0);

        CONVOY_FAILURE_MULTIPLIER = builder
                .comment("Amount added to the convoy event's chance after a missed event roll.",
                         "Higher = the wait between convoys is more consistent; lower = streakier.")
                .defineInRange("convoyFailureMultiplier", 0.06, 0.0, 1.0);

        SHELLING_EVENTS_ENABLED = builder
                .comment("Enable the mortar_shelling event: an RU or US mortar battery sets up out of sight and",
                         "shells your base. It only fires while you are actually at a base — see shellingBaseRadius —",
                         "so it threatens something you built rather than open ground you happen to be crossing.",
                         "The crew cannot see that far (96 blocks), so it works a fire mission on the position",
                         "instead, for shellingDurationMin/MaxTicks, and then leaves its tubes for good. The crews",
                         "stay put afterwards as ordinary infantry — the barrage ends, the battery doesn't.")
                .define("shellingEventsEnabled", true);

        SHELLING_BASE_CHANCE = builder
                .comment("Starting chance for the mortar_shelling event on each of SEM's event rolls (one per",
                         "60 seconds). Kept below the convoy's because this one is further gated on you being",
                         "at your base, so a large share of its rolls are discarded before they can fire.")
                .defineInRange("shellingBaseChance", 0.05, 0.0, 1.0);

        SHELLING_FAILURE_MULTIPLIER = builder
                .comment("Amount added to the shelling event's chance after a missed event roll.")
                .defineInRange("shellingFailureMultiplier", 0.04, 0.0, 1.0);

        SHELLING_BASE_RADIUS = builder
                .comment("How close (in blocks) you must be to your respawn point for the shelling event to fire.",
                         "Your bed/anchor is the stand-in for 'your base' — it is the one spot the game already",
                         "knows you chose to call home. Never having slept means the event can never fire.")
                .defineInRange("shellingBaseRadius", 48, 8, 256);

        SHELLING_MORTARS = builder
                .comment("How many mortars a shelling battery sets up (each gets its own crew).")
                .defineInRange("shellingMortars", 2, 1, 6);

        SHELLING_GUARDS = builder
                .comment("Infantry guarding a shelling battery. Killing the crews is the only way to stop the",
                         "barrage, so the guards are what make that a fight rather than an execution — a mortar",
                         "crew cannot depress its tube inside about 27 blocks and falls back on its rifle once you",
                         "are close. 0 leaves the crews on their own.")
                .defineInRange("shellingGuards", 4, 0, 12);

        SHELLING_DURATION_MIN_TICKS = builder
                .comment("Shortest a shelling battery works its fire mission before standing down (20 ticks = 1",
                         "second). One duration is rolled for the whole battery, so its tubes stop together.")
                .defineInRange("shellingDurationMinTicks", 600, 20, 24000);

        SHELLING_DURATION_MAX_TICKS = builder
                .comment("Longest a shelling battery works its fire mission before standing down. When the mission",
                         "expires the crews leave their tubes for good and revert to ordinary infantry — they do not",
                         "despawn, so the battery is still there to be cleared out afterwards.",
                         "Set at or below the minimum for a fixed duration.")
                .defineInRange("shellingDurationMaxTicks", 1200, 20, 24000);

        HIGH_CHANCE_MORTAR_SHELL = builder
                .comment("Item id of the shell a spawned mortar crew usually gets (75% of crews).",
                         "Rolled once per crew, so a battery can mix but a given tube shoots one thing throughout.",
                         "Must be a mortar shell the tube will actually accept; anything else falls back to",
                         "superbwarfare:mortar_shell rather than leaving the crew unable to fire.")
                .define("highChanceMortarShell", "superbwarfare:mortar_shell", SewvConfig::isValidResourceId);

        LOW_CHANCE_MORTAR_SHELL = builder
                .comment("Item id of the shell a spawned mortar crew occasionally gets (25% of crews).",
                         "Same rules as highChanceMortarShell. Default is white phosphorus.")
                .define("lowChanceMortarShell", "superbwarfare:mortar_shell_wp", SewvConfig::isValidResourceId);

        DERELICT_EVENTS_ENABLED = builder
                .comment("Enable the derelict_vehicle event: a knocked-out RU or US hull with its surviving crew",
                         "camped around it on foot. The hull has no energy, almost no ammunition and is nearly",
                         "destroyed, so it is salvage rather than a threat — the fight is with the survivors, and",
                         "the prize is a vehicle you can recover if you can repair and refuel it.",
                         "This is the counterpart to the convoy event: something between 'a crewed tank attacks",
                         "you' and 'no vehicle at all'.")
                .define("derelictEventsEnabled", true);

        DERELICT_BASE_CHANCE = builder
                .comment("Starting chance for the derelict_vehicle event on each of SEM's event rolls (one per",
                         "60 seconds), growing by derelictFailureMultiplier after each miss.")
                .defineInRange("derelictBaseChance", 0.05, 0.0, 1.0);

        DERELICT_FAILURE_MULTIPLIER = builder
                .comment("Amount added to the derelict event's chance after a missed event roll.")
                .defineInRange("derelictFailureMultiplier", 0.05, 0.0, 1.0);

        DERELICT_HEALTH_FRACTION = builder
                .comment("Health a derelict hull spawns with, as a fraction of its maximum.",
                         "KEEP THIS BELOW autoBoardMinHealthFraction (default 0.25). That is not a style rule --",
                         "it is the only thing stopping the survivors from simply climbing back in: scavenging",
                         "AI refuses any hull below that threshold, and a crewed hull also gets infinite energy",
                         "from the faction-energy rule, so raising this past it quietly turns this event into",
                         "'a working tank spawns and attacks you', which the convoy event already is.")
                .defineInRange("derelictHealthFraction", 0.15, 0.01, 1.0);

        DERELICT_GUARDS = builder
                .comment("Maximum survivors camped around a derelict hull (at least one always spawns).",
                         "They are ordinary infantry on foot, NOT a crew -- nobody is in the vehicle.")
                .defineInRange("derelictGuards", 4, 1, 12);

        DERELICT_AMMO_COUNT = builder
                .comment("Rounds left in a derelict hull's container. Enough to be worth looting and to make the",
                         "vehicle briefly useful if you recover it, not enough to fight with.")
                .defineInRange("derelictAmmoCount", 2, 0, 64);

        builder.pop();

        builder.push("vehicle_pools");

        // Pools accept any registered SW-based VehicleEntity id, including those from
        // Superb Warfare addons (e.g. "dragonrise_reforge:...", "fcp:...", "mcsp:...").
        // Ids from mods that aren't installed are skipped safely, so it's fine to list
        // addon vehicles here even when the addon may be absent. Ground vehicles and
        // helicopters are fully supported. NOT recommended: fixed-wing aircraft/jets
        // (flown with helicopter hover logic they can't sustain), and artillery /
        // indirect-fire hulls like the TOS-1A (their AI crew can't self-load and won't
        // fire).
        //
        // Mortars don't belong here either, but for a different reason: a mortar has no
        // seats, so there is nothing for a spawned crew to ride and TankSpawner can't
        // place anyone in it. Mortars are crewed instead by ordering an existing PMC unit
        // onto a deployed one with the board key — see the mortar_ai section.
        RU_VEHICLE_POOL = builder
                .comment("Vehicle entity ids RU crews can spawn with (e.g. \"superbwarfare:t_90a\").",
                         "List several to have one picked at random per spawn. Ground vehicles and helicopters are supported.",
                         "Addon ids work too (e.g. \"dragonrise_reforge:t90mh\", \"mcsp:t90a_green\", \"superbwarfare:mi_28\").")
                .defineList("ruVehiclePool", List.of("superbwarfare:t_90a", "superbwarfare:bmp_2", "superbwarfare:mi_28"), SewvConfig::isValidResourceId);

        US_VEHICLE_POOL = builder
                .comment("Vehicle entity ids US crews can spawn with (e.g. \"superbwarfare:m_1a_2\").",
                         "List several to have one picked at random per spawn. Ground vehicles and helicopters are supported.",
                         "Addon ids work too (e.g. \"dragonrise_reforge:m1a2sepv2\", \"fcp:humvee\", \"superbwarfare:ah_6\").")
                .defineList("usVehiclePool", List.of("superbwarfare:m_1a_2", "superbwarfare:bradley", "superbwarfare:ah_6"), SewvConfig::isValidResourceId);

        PMC_VEHICLE_POOL = builder
                .comment("Vehicle entity ids for debug PMC units spawning",
                         "List several to have one picked at random per spawn. Ground vehicles and helicopters are supported.",
                         "Addon ids work too (e.g. \"fcp:littlebird\", \"mcsp:m1a2\", \"superbwarfare:ah_6\").")
                .defineList("pmcVehiclePool", List.of("superbwarfare:t_90a", "superbwarfare:ah_6"), SewvConfig::isValidResourceId);

        CREATIVE_AMMO_FALLBACK = builder
                .comment("A spawned vehicle is stocked with the real, finite, lootable ammunition its guns use.",
                         "This only decides the fallback when that ammo can't be determined (an energy- or",
                         "infinite-ammo hull, or unreadable modded gun data): ON gives it a bottomless creative",
                         "ammo box so it can still fire; turn OFF for a strict survival world (empty container).")
                .define("creativeAmmoFallback", true);

        builder.pop();

        builder.push("npc_armor");

        NPC_ARMOR_ENABLED = builder
                .comment("Issue armor to SimpleEnemyMod units when they spawn. Applies to EVERY unit, crewing a",
                         "vehicle or on foot, however it was spawned (events, structures, /sewv, a spawn egg).",
                         "Armor is issued once per unit and only into slots that are still empty, so a unit you",
                         "have re-equipped by hand keeps what you gave it.",
                         "Turn off to leave units bare — the armor grants SuperbWarfare bullet resistance, so this",
                         "is the toggle to reach for if infantry feel too tough.")
                .define("npcArmorEnabled", true);

        // Each entry is equipped into whatever slot its own item declares, so the lists extend to
        // legs and boots by themselves if SuperbWarfare (or an addon) ever ships them.
        //
        // These want armor that brings its own model — all of SuperbWarfare's does. Plain
        // texture armor (vanilla iron, most modded sets) equips and grants its protection, but
        // SimpleEnemyMod only draws that on PMC units, so on an RU or US unit it will be
        // invisible.
        RU_ARMOR = builder
                .comment("Armor item ids every RU unit spawns wearing.")
                .defineList("ruArmor",
                        List.of("superbwarfare:ru_helmet_6b47", "superbwarfare:ru_chest_6b43"),
                        SewvConfig::isValidResourceId);

        US_ARMOR = builder
                .comment("Armor item ids every US unit spawns wearing.")
                .defineList("usArmor",
                        List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"),
                        SewvConfig::isValidResourceId);

        PMC_ARMOR = builder
                .comment("Armor item ids every PMC unit spawns wearing. US kit by default — a PMC unit is the",
                         "player's own, and its armor sits in slots 2-5 of the inventory you can open, so this is",
                         "a starting loadout you can swap rather than a fixed uniform.")
                .defineList("pmcArmor",
                        List.of("superbwarfare:us_helmet_pasgt", "superbwarfare:us_chest_iotv"),
                        SewvConfig::isValidResourceId);

        builder.pop();

        builder.push("structure_vehicles");

        STRUCTURE_VEHICLES_ENABLED = builder
                .comment("Spawn vehicles from the faction pools when a compatible berezka_api structure generates.",
                         "Soft compat: does nothing unless berezka_api and a matching structure mod are installed.",
                         "RU/US structures get a fully crewed, armed, fuelled vehicle; PMC structures get a BARE hull",
                         "(no crew, no ammo, no energy) for the player to capture and use.")
                .define("structureVehiclesEnabled", true);

        STRUCTURE_VEHICLE_MAX_COUNT = builder
                .comment("Hard ceiling on how many vehicles one structure can field. The actual count ramps from 1",
                         "toward this cap as the world ages (see structureVehicleRampDays), with per-vehicle randomness.")
                .defineInRange("structureVehicleMaxCount", 5, 1, 16);

        STRUCTURE_VEHICLE_RAMP_DAYS = builder
                .comment("Elapsed in-game days over which the per-structure vehicle count ramps from 1 to the cap.",
                         "Early on a structure fields a lone vehicle; by this many days it can field the full cap.",
                         "Measured from total world play time, so it survives sleeping and /time set. 0 = no ramp",
                         "(always rolls against the full cap).")
                .defineInRange("structureVehicleRampDays", 24, 0, 1000);

        RU_VEHICLE_STRUCTURES = builder
                .comment("Structure ids (the namespace:path of the structure's start pool) that field a crewed RU",
                         "vehicle. Add any berezka_api structure id here to have RU armor spawn at it.")
                .defineList("ruVehicleStructures", List.of("russian_army_structures:tank"), SewvConfig::isValidResourceId);

        US_VEHICLE_STRUCTURES = builder
                .comment("Structure ids that field a crewed US vehicle.")
                .defineList("usVehicleStructures", List.of("us_army_structures:convoy"), SewvConfig::isValidResourceId);

        PMC_VEHICLE_STRUCTURES = builder
                .comment("Structure ids that field a BARE PMC vehicle (no crew, ammo or energy) for the player to take.")
                .defineList("pmcVehicleStructures", List.of("pmc_structures:buggy"), SewvConfig::isValidResourceId);

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

        AI_AIM_ACCURACY = builder
                .comment("How accurately an AI vehicle crew lays its guns. SuperbWarfare's auto-aim solves a",
                         "perfect ballistic firing solution with perfect target lead, so an untreated AI crew",
                         "never misses at any range.",
                         "  realistic - every crew disperses by aiAimSpreadDegrees, however many of them there are.",
                         "  scaled    - dispersion is divided by the number of occupied seats, so a full crew",
                         "              shoots better than a lone survivor (spotter + loader + gunner).",
                         "  accurate  - no dispersion at all; the pre-1.x behaviour.",
                         "Only affects units crewing a vehicle. Mortar crews stand beside their tube rather than",
                         "riding it and are unaffected, as are players.")
                // Arrays.asList, NOT List.of: when the key is absent from an existing config file
                // Forge's correct() pass tests the acceptable-values list against null, and
                // List.of().contains(null) throws NPE — which aborts server start with a config
                // stack trace that names no mod. Arrays.asList answers false and lets the default apply.
                .defineInList("aiAimAccuracy", "realistic", Arrays.asList("realistic", "scaled", "accurate"));

        AI_AIM_SPREAD_DEG = builder
                .comment("Dispersion cone, in degrees, added to an AI crew's shots (see aiAimAccuracy).",
                         "This is ANGULAR, so the miss distance grows with range on its own: 1 degree is roughly",
                         "0.35 blocks at 20 blocks and 1.75 blocks at 100. Values are a triangular distribution",
                         "about the true line, so most shots land far nearer than the full cone.",
                         "For scale, SuperbWarfare's own built-in spreads are 0.02 for a tank cannon, 0.5 for a",
                         "coaxial MG and 5.0 for grapeshot. Ignored when aiAimAccuracy is 'accurate'.")
                .defineInRange("aiAimSpreadDegrees", 1.0, 0.0, 30.0);

        IFV_DISMOUNTS_ENABLED = builder
                .comment("Let infantry fighting vehicles fight like IFVs instead of like small tanks: when the",
                         "hull comes up against an ARMOURED target it drops its squad, who fight on foot from",
                         "there while the vehicle keeps working its gun. Against infantry the squad stays aboard,",
                         "since the hull's own cannon and MGs already cover that.",
                         "The squad is NOT recalled afterwards -- once out they are ordinary infantry, and picking",
                         "them back up is whatever you'd normally do to put a unit in a seat. No smoke is fired",
                         "either: an IFV drops its squad on every contact, so screening each one would leave the",
                         "whole map permanently fogged.",
                         "Which hulls count is decided by ifvNameClues. Turn this off to have every hull keep its",
                         "full crew buttoned up, which is the pre-1.x behaviour.")
                .define("ifvDismountsEnabled", true);

        IFV_NAME_CLUES = builder
                .comment("Substrings that mark a vehicle id as an IFV. Matched case-insensitively against the",
                         "whole registry id, so \"bmp\" catches superbwarfare:bmp_2, fcp:bmp1u and mcsp:bmp2_camo",
                         "alike, and an addon's hull is picked up without naming it explicitly.",
                         "Keep them SPECIFIC: a clue that also matches a tank id turns that tank into an IFV and",
                         "its crew will climb out mid-battle. \"m1\" would catch m1a2 for exactly that reason.",
                         "",
                         "Who stays aboard is not configurable, because it cannot be read off the seat data",
                         "reliably: the DRIVER (seat 0) and the TURRET (the seat carrying the most weapons) hold",
                         "their positions and everyone else dismounts. Seats are not consistent enough for a",
                         "finer rule -- superbwarfare:bmp_2 gives its six rear seats firing-port MGs, so an",
                         "\"only weaponless seats dismount\" rule would empty nothing at all, while fcp's BMPs put",
                         "a WEAPONLESS driver in seat 0 and the turret in seat 1.")
                .defineList("ifvNameClues",
                        List.of("bradley", "bmp", "bmd", "cv90", "puma", "marder"),
                        o -> o instanceof String s && !s.isBlank());

        AT_WEAPON_RU = builder
                .comment("SuperbWarfare launcher handed to an RU dismount squad's anti-tank gunners.",
                         "A dismount squad only ever leaves the hull because it met ARMOUR, and a TACZ rifle cannot",
                         "hurt armour — without this the squad is put on the ground with nothing useful to do.",
                         "The weapon REPLACES the unit's rifle (there is only one main hand), and it comes with its",
                         "own ammunition supply (atBackupAmmo) rather than drawing from an inventory, because RU/US",
                         "units have none. Set blank to leave RU dismounts with their rifles.")
                .define("atWeaponRu", "superbwarfare:rpg");

        AT_WEAPON_US = builder
                .comment("The same, for a US dismount squad. The Javelin is guided and fire-and-forget, so it works",
                         "at any range the unit can acquire a target at; the RPG is an unguided rocket and wants to",
                         "be much closer (see atEngageRange). Set blank to leave US dismounts with their rifles.")
                .define("atWeaponUs", "superbwarfare:javelin");

        AT_SECOND_GUNNER_CHANCE = builder
                .comment("Chance that a dismount squad gets a SECOND anti-tank gunner. The first dismount always",
                         "gets one; nobody past the second ever does, whatever this is set to.",
                         "0.0 = always exactly one AT gunner per squad, 1.0 = always two.")
                .defineInRange("atSecondGunnerChance", 0.5, 0.0, 1.0);

        AT_BACKUP_AMMO = builder
                .comment("Rockets/missiles issued with the launcher. RU/US units have no inventory, so this is an",
                         "ISSUED supply (SuperbWarfare's own item-free ammo channel) rather than carried items —",
                         "the same way mortar shells and TOW missiles reach these crews. When it runs out the",
                         "gunner is left with an empty tube; it is not resupplied.")
                .defineInRange("atBackupAmmo", 8, 1, 64);

        AT_ENGAGE_RANGE = builder
                .comment("Maximum range (in blocks) at which an AT gunner will fire.",
                         "This is a FIRE gate, not an approach order: SimpleEnemyMod walks a unit to within 90",
                         "blocks of its target and then stops, so a gunner further out than this simply holds fire",
                         "rather than wasting its small supply of rockets on shots that fall short.",
                         "Raise it well past 90 for the Javelin, which is guided and does not care about the drop.")
                .defineInRange("atEngageRange", 48.0, 8.0, 200.0);

        AT_AIM_TICKS = builder
                .comment("How long an AT gunner tracks its target before the first shot, in goal ticks.",
                         "Note goals tick every OTHER game tick, so this is roughly 2x its value in game ticks.",
                         "Purely a feel knob — it stops a launcher going off the instant the unit hits the ground.")
                .defineInRange("atAimTicks", 15, 0, 200);

        MEDIC_ENABLED = builder
                .comment("Let PMC units treat themselves and each other with superbwarfare:medical_kit while OUT",
                         "of contact (neither the medic nor the patient holding a target).",
                         "Kits are NOT issued automatically — put them in a unit's inventory yourself, and they are",
                         "consumed one per treatment. A unit with no kit simply never does this.")
                .define("medicEnabled", true);

        MEDIC_SEARCH_RADIUS = builder
                .comment("How far (in blocks) a PMC medic will look for a wounded squadmate to treat.",
                         "It treats ITSELF first when hurt; only then does it walk to someone else.")
                .defineInRange("medicSearchRadius", 12.0, 2.0, 48.0);

        AUTO_BOARD_ENABLED = builder
                .comment("Let RU/US infantry climb into an abandoned vehicle they walk past, so a hull whose crew",
                         "bailed out (or one you parked and left) gets crewed again instead of sitting there.",
                         "A unit only does this while it has NO target — it will not break off a firefight to go",
                         "looking for a ride. PMC units are unaffected: they board on your order instead.",
                         "Note this deliberately cannot recall an IFV's dismount squad — the hull they left still",
                         "holds its driver and gunner, and only a COMPLETELY empty vehicle is scavenged.")
                .define("autoBoardEnabled", true);

        AUTO_BOARD_SCAN_RADIUS = builder
                .comment("Radius (in blocks) an idle RU/US unit searches for an abandoned vehicle.",
                         "This scan is the whole cost of the feature, so keep it modest — it runs on every idle",
                         "unit on the map. Lower it if you spawn very large numbers of infantry.")
                .defineInRange("autoBoardScanRadius", 32.0, 4.0, 128.0);

        AUTO_BOARD_MIN_HEALTH_FRACTION = builder
                .comment("Minimum health (as a fraction of maximum) an abandoned vehicle must still have before a",
                         "crew will take it. Below this the hull is a burning wreck in waiting and a crew that",
                         "boarded it would only bail straight back out.")
                .defineInRange("autoBoardMinHealthFraction", 0.25, 0.0, 1.0);

        AUTO_BOARD_STEALS_PLAYER_VEHICLES = builder
                .comment("Whether enemies may take a vehicle a PLAYER last drove.",
                         "SuperbWarfare has no concept of vehicle ownership at all — the last driver is the only",
                         "signal there is — so turning this on means any hull you step out of is fair game, and",
                         "leaving it off means a vehicle you have driven even once is permanently off limits.")
                .define("autoBoardStealsPlayerVehicles", false);

        VEHICLE_FORMATION_SPACING = builder
                .comment("Distance (in blocks) between successive slots in a vehicle wedge or column.",
                         "This is the step directly astern; a wedge also fans out sideways 1.25x as fast, which is",
                         "SimpleEnemyMod's own wedge proportion kept at vehicle scale.",
                         "MUST stay comfortably above your widest hull's width — a T-90A is 4.62 blocks across, and",
                         "the terrain sensor treats an allied hull as a no-go box reaching a full hull width out from",
                         "its centre. Set this too low and a column puts each hull's slot inside its leader's no-go",
                         "box: the follower can never settle there and turns in place beside the formation forever.",
                         "Larger = a looser, safer formation that covers more ground.")
                .defineInRange("vehicleFormationSpacing", 12.0, 5.0, 32.0);

        VEHICLE_FORMATION_ARRIVE_RADIUS = builder
                .comment("How close (in blocks, measured horizontally) a hull must get to its formation slot to park.",
                         "Formations need their own arrival distance because the ordinary one is a hull width plus 8",
                         "blocks — 11.62 for a T-90A, wider than the whole formation, so every hull would count as",
                         "'arrived' from anywhere in it and the wedge would collapse onto the point man.",
                         "KEEP THIS BELOW HALF vehicleFormationSpacing, or neighbouring slots' arrival circles overlap",
                         "and a hull can 'arrive' while sitting in its neighbour's slot.")
                .defineInRange("vehicleFormationArriveRadius", 3.0, 1.0, 8.0);

        STALEMATE_BREAKER_ENABLED = builder
                .comment("Let an AI crew reposition on its own initiative when it is holding a target it cannot",
                         "actually shoot — a turret that can't depress far enough, terrain in the way, or any other",
                         "reason. Without this two tanks can sit staring at each other forever, since holding still",
                         "is exactly what the standoff doctrine asks for and nothing notices no shots are coming out.",
                         "Disable to restore the old behavior of holding the standoff ring unconditionally.")
                .define("stalemateBreakerEnabled", true);

        STALEMATE_SILENCE_TICKS = builder
                .comment("How long an AI crew may hold a live target without landing a shot before it assumes it is",
                         "stuck and repositions (20 ticks = 1 second).",
                         "MUST stay above the slowest legitimate reload on your hulls, or crews will wander off mid-duel",
                         "between normal shots: SBW cannon reloads run 6-13 seconds, so the default sits clear of them.",
                         "Raise it if you run slow-firing modded vehicles. Targets outside the turret's elevation range",
                         "are detected immediately and don't wait for this timer.")
                .defineInRange("stalemateSilenceTicks", 300, 40, 2400);

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
                .comment("Look ahead while driving and steer AI vehicles around water and lava instead of",
                         "ploughing straight into them. Drops are deliberately NOT avoided — SBW's fall damage",
                         "on vehicles is forgiving, so refusing them cost far more mobility than it saved.",
                         "Disabling restores legacy behavior of driving in a straight line at the destination.")
                .define("vehicleTerrainAvoidance", true);

        VEHICLE_LOOKAHEAD_DISTANCE = builder
                .comment("How far ahead (in blocks) the terrain sensor probes for hazards.",
                         "Higher numbers = scans ahead for obstacles at the cost of performance.")
                .defineInRange("vehicleLookaheadDistance", 5.0, 1.0, 16.0);

        PATROL_ROTATE_INTERVAL_TICKS = builder
                .comment("How long a patrolling ground vehicle holds a spot before moving to a new random point",
                         "inside its patrol area (20 ticks = 1 second; default 3600 = 3 minutes).")
                .defineInRange("patrolRotateIntervalTicks", 3600, 200, 24000);

        builder.pop();

        builder.push("flight_ai");

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

        builder.push("mortar_ai");

        MORTAR_USE_DISTANCE = builder
                .comment("How close (in blocks) a unit must get to a mortar before it can work it. A mortar has",
                         "no seats, so the crew stands beside it rather than riding it. Raise this if crews take",
                         "splash damage from their own launches; lower it for a tighter, more natural crew stance.")
                .defineInRange("mortarUseDistance", 2.0, 1.0, 6.0);

        MORTAR_APPROACH_TIMEOUT_TICKS = builder
                .comment("How long a unit keeps trying to walk to its assigned mortar before giving up and",
                         "releasing it for someone else (20 ticks = 1 second). The clock only runs while the",
                         "crew is walking, so a unit already in position never times out.")
                .defineInRange("mortarApproachTimeoutTicks", 300, 20, 1200);

        MORTAR_FIRE_COOLDOWN_TICKS = builder
                .comment("Minimum delay between shots from one mortar crew (20 ticks = 1 second). This is on top",
                         "of the mortar's own ~1.25 s load cycle, so it sets the sustained rate of fire.",
                         "Lower = faster barrages and heavier ammo drain.")
                .defineInRange("mortarFireCooldownTicks", 60, 1, 1200);

        MORTAR_DISPERSION_RADIUS = builder
                .comment("Scatter radius (in blocks) an AI crew aims within, around its target. Each shot is",
                         "re-rolled inside this circle, so a mortar walks its rounds over an area instead of",
                         "hammering one point. 0 aims dead-on every shot.")
                .defineInRange("mortarDispersionRadius", 3, 0, 16);

        MORTAR_REQUIRES_AMMO = builder
                .comment("Require a crew to carry mortar shells in its inventory (sneak+right-click a unit to open",
                         "it) and consume one per shot. Disable for unlimited shells — useful for testing.")
                .define("mortarRequiresAmmo", true);

        MORTAR_CHUNK_LOADING = builder
                .comment("Keep a crewed mortar and its crew loaded and ticking when no player is nearby.",
                         "This is what lets a radio fire mission be worked from far outside your render distance —",
                         "without it a mortar simply stops existing once you walk away, and the barrage stops with it.",
                         "Only chunks with a crewed mortar in them are held, and only until the crew stands down,",
                         "so the cost scales with how many mortars you have manned, not with the map.",
                         "Disable if you are manning many mortars at once and the server is struggling.")
                .define("mortarChunkLoading", true);

        MORTAR_RADIO_RANGE = builder
                .comment("Range (in blocks) of the handheld radio: how far it looks for the mob you are pointing at,",
                         "and how far its fire mission reaches out to your manned mortars and TOW launchers.",
                         "A mortar itself can shoot roughly 27-770 blocks, so the default covers most of that.")
                .defineInRange("mortarRadioRange", 400.0, 16.0, 1024.0);

        MORTAR_DEBUG_LOGGING = builder
                .comment("Log why a mortar crew is holding fire (to the server log, once per change of reason).",
                         "Turn this on if a crew reaches its mortar but won't shoot — it names the exact gate,",
                         "e.g. no target, out of range, no shells.")
                .define("mortarDebugLogging", false);

        builder.pop();

        builder.push("voicelines");

        VEHICLE_VOICELINES_ENABLED = builder
                .comment("Give a unit crewing a vehicle its own radio voicelines, and let only ONE of them speak.",
                         "SimpleEnemyMod's hurt/death/alert lines are per-unit with per-unit cooldowns, so a shell",
                         "that hits a loaded troop carrier fires one death line PER RIDER at the same instant --",
                         "seven men shouting over each other from inside one hull. With this on, only the driver",
                         "speaks for the vehicle, and what plays is muffled radio traffic rather than the shouted",
                         "infantry line (which is the wrong sound to hear from inside a closed vehicle anyway).",
                         "Units on foot are completely unaffected either way.",
                         "Turn off to restore SimpleEnemyMod's own behaviour, overlaps included.")
                .define("vehicleVoicelinesEnabled", true);

        builder.pop();

        builder.push("interaction");

        BOARD_SCAN_RADIUS = builder
                .comment("Radius (in blocks) around the player searched for owned units when pressing the board/dismount key.")
                .defineInRange("boardScanRadius", 64.0, 8.0, 128.0);

        SHOW_ORDER_FEEDBACK = builder
                .comment("Show an action-bar confirmation when a board/dismount/mortar order is issued.")
                .define("showOrderFeedback", true);

        builder.pop();

        builder.push("overlay");

        FACTION_COLORS_ENABLED = builder
                .comment("Colour the name and health bar of SuperbWarfare's hover overlay by who is CREWING the",
                         "vehicle, so you can tell an enemy tank from your own at a glance.",
                         "SuperbWarfare colours that overlay from the driver's scoreboard team, and an NPC unit is",
                         "not on a team, so without this every AI-crewed hull renders the same plain white.",
                         "Only a hull crewed ENTIRELY by one faction is coloured; an empty, mixed, or",
                         "player-occupied one keeps SuperbWarfare's own colour.",
                         "This is a client-side preference — on a server each player sees their own setting.")
                .define("factionColorsEnabled", true);

        COLOR_RU = builder
                .comment("Colour for a hull crewed entirely by RU units, as RRGGBB hex. Default is red.")
                .define("colorRu", "FF5555");

        COLOR_US = builder
                .comment("Colour for a hull crewed entirely by US units, as RRGGBB hex. Default is blue.")
                .define("colorUs", "5555FF");

        COLOR_PMC = builder
                .comment("Colour for a hull crewed entirely by PMC units, as RRGGBB hex. Default is green.")
                .define("colorPmc", "55FF55");

        builder.pop();

        SPEC = builder.build();
    }

    // Config-load validation only checks the id is well-formed; whether it resolves to a
    // real entity type or item is checked at spawn time (registries aren't ready here).
    private static boolean isValidResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    /**
     * Parse an {@code RRGGBB} config colour into an opaque ARGB int.
     *
     * <p>Deliberately lenient: a colour is a cosmetic preference, so a typo falls back to the
     * overlay's own white rather than throwing out of a render call. The alpha byte is forced
     * on because SuperbWarfare ORs {@code 255 << 24} onto the text colour for the health bar
     * and a config value of {@code 000000} would otherwise render as fully transparent.
     */
    public static int parseColor(String hex, int fallback) {
        try {
            return 0xFF000000 | (Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
