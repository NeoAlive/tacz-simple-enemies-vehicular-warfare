package com.neoalive.tacz_sewv.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SewvConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue TANKS_IN_EVENTS;
    public static final ForgeConfigSpec.DoubleValue TANK_SPAWN_CHANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("event_vehicles");

        TANKS_IN_EVENTS = builder
                .comment("Allow RU/US tanks to (very rarely) spawn in combat events.")
                .define("tanksInEvents", true);

        TANK_SPAWN_CHANCE = builder
                .comment("Chance (0.0-1.0) per faction for a tank to spawn when a combat event fires. Keep it LOW for rarity.")
                .defineInRange("tankSpawnChance", 0.02, 0.0, 1.0);

        builder.pop();

        SPEC = builder.build();
    }
}