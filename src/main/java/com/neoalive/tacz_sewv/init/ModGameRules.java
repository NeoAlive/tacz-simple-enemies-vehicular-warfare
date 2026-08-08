package com.neoalive.tacz_sewv.init;

import net.minecraft.world.level.GameRules;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Per-world boolean gates formerly in {@code SewvConfig} spawn_gates / tanksInEvents.
 * Toggle in-game with {@code /gamerule <name> true|false}.
 */
public final class ModGameRules {

    public static final GameRules.Key<GameRules.BooleanValue> RU_SPAWNS =
            GameRules.register("sewvRuSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> US_SPAWNS =
            GameRules.register("sewvUsSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> PMC_AMBIENT_SPAWNS =
            GameRules.register("sewvPmcAmbientSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> TANKS_IN_EVENTS =
            GameRules.register("sewvTanksInEvents", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    /**
     * When on, SEM {@code DynamicEventManager} multiplies every event's player min/max spawn
     * distance by 2.5 (this mod's events and SEM's own). Toggle off to restore packed base ranges.
     */
    public static final GameRules.Key<GameRules.BooleanValue> FAR_EVENT_SPAWNS =
            GameRules.register("sewvFarEventSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    /**
     * When on, mob melee against SuperbWarfare hulls uses SEWV's score-based damage instead of
     * datapack {@code DamageModifiers} that zero {@code minecraft:mob_attack}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> CAN_MOBS_DAMAGE_VEHICLES =
            GameRules.register("canMobsDamageVehicles", GameRules.Category.MOBS, GameRules.BooleanValue.create(true));

    /**
     * Extermination softcompat only — registered in {@link #bootstrap()} when that mod is loaded.
     * Gates vehicle keep-out of pods, emperor/uber body-glow suppress, and related invasion tweaks.
     */
    @Nullable
    public static GameRules.Key<GameRules.BooleanValue> INVASION_OVERRIDES;

    private ModGameRules() {}

    /** Force class init so {@link GameRules#register} runs before any world loads. */
    public static void bootstrap() {
        if (INVASION_OVERRIDES == null && ModList.get().isLoaded("extermination")) {
            INVASION_OVERRIDES = GameRules.register(
                    "sewvInvasionOverrides",
                    GameRules.Category.MOBS,
                    GameRules.BooleanValue.create(true));
        }
    }
}
