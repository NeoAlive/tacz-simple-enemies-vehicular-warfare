package com.neoalive.tacz_sewv.init;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Per-world boolean gates formerly in {@code SewvConfig} spawn_gates / tanksInEvents.
 * Toggle in-game with {@code /gamerule <name> true|false}, or via Config UI → World rules.
 *
 * <p>Diagnostic toggles live in {@link com.neoalive.tacz_sewv.config.ClientConfig} (Config UI
 * Client → Debug). {@code farEventSpawns} is a server config under Events.
 */
public final class ModGameRules {

    public static final GameRules.Key<GameRules.BooleanValue> RU_SPAWNS =
            GameRules.register("sewvRuSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    /**
     * Master switch for all automatic spawns: SEM events, village garrisons, berezka structure
     * vehicles, and SEWV procedural events. Does not block player spawn eggs or {@code /sewv spawn}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> AMBIENT_SPAWNS =
            GameRules.register("sewvAmbientSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> US_SPAWNS =
            GameRules.register("sewvUsSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> PMC_AMBIENT_SPAWNS =
            GameRules.register("sewvPmcAmbientSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> TANKS_IN_EVENTS =
            GameRules.register("sewvTanksInEvents", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

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

    /**
     * Server-side read for a boolean gamerule from anywhere, with no {@code Level} needed at the
     * call site — {@code ServerLifecycleHooks.getCurrentServer()} resolves the running (or
     * integrated singleplayer) server directly. Answers false with no server running (main menu,
     * or a call that raced world unload) rather than throwing.
     */
    public static boolean server(GameRules.Key<GameRules.BooleanValue> rule) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getGameRules().getBoolean(rule);
    }

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
