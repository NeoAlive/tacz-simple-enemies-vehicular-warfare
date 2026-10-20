package com.neoalive.tacz_sewv.init;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
     * Master switch for all automatic spawns: SEM events, village garrisons,
     * and SEWV procedural events. Does not block player spawn eggs or {@code /sewv spawn}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> AMBIENT_SPAWNS =
            GameRules.register("sewvAmbientSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> US_SPAWNS =
            GameRules.register("sewvUsSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    public static final GameRules.Key<GameRules.BooleanValue> PMC_AMBIENT_SPAWNS =
            GameRules.register("sewvPmcAmbientSpawns", GameRules.Category.SPAWNING, GameRules.BooleanValue.create(true));

    /**
     * When on, mob melee against SuperbWarfare hulls uses SEWV's score-based damage instead of
     * datapack {@code DamageModifiers} that zero {@code minecraft:mob_attack}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> CAN_MOBS_DAMAGE_VEHICLES =
            GameRules.register("canMobsDamageVehicles", GameRules.Category.MOBS, GameRules.BooleanValue.create(true));

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
    }
}
