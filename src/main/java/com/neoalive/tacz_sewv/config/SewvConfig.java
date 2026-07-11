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
