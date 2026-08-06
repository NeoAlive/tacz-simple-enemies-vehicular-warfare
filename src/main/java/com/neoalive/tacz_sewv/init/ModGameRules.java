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
