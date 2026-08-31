package com.neoalive.tacz_sewv.init;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Per-world boolean gates formerly in {@code SewvConfig} spawn_gates / tanksInEvents.
 * Toggle in-game with {@code /gamerule <name> true|false}.
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

    // --- Diagnostic/developer logging, formerly SewvConfig booleans under [debug]-ish sections.
    // Moved here so they can be flipped with /gamerule during a live session instead of editing
    // config/tacz_sewv-common.toml and restarting/reloading. See ModGameRules.server(Key) for the
    // read side — every call site funnels through it (or the client-side equivalent for the two
    // that render client-only) rather than reading SewvConfig directly.

    public static final GameRules.Key<GameRules.BooleanValue> GROUND_PATHING_DEBUG =
            GameRules.register("sewvGroundPathingDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> SHIP_PATHING_DEBUG =
            GameRules.register("sewvShipPathingDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> SEWV_DIAG_DEBUG =
            GameRules.register("sewvDiagDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> OUTER_RING_DEBUG_LOGGING =
            GameRules.register("sewvOuterRingDebugLogging", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> HELI_COMBAT_DEBUG =
            GameRules.register("sewvHeliCombatDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> HELI_FLIGHT_DEBUG =
            GameRules.register("sewvHeliFlightDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> PLANE_COMBAT_DEBUG =
            GameRules.register("sewvPlaneCombatDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> MORTAR_DEBUG_LOGGING =
            GameRules.register("sewvMortarDebugLogging", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    /** Prints {@code gunId -> category, factor, half, in->out} for every translated TaCZ hit. */
    public static final GameRules.Key<GameRules.BooleanValue> BALLISTIC_TRANSLATION_DEBUG =
            GameRules.register("sewvBallisticTranslationDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    /** Default ON — silent until an order actually fails; see the old orderFailureDebug comment. */
    public static final GameRules.Key<GameRules.BooleanValue> ORDER_FAILURE_DEBUG =
            GameRules.register("sewvOrderFailureDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(true));
    /** Default ON — the noisy half of order-failure reporting; see the old targetVetoDebug comment. */
    public static final GameRules.Key<GameRules.BooleanValue> TARGET_VETO_DEBUG =
            GameRules.register("sewvTargetVetoDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(true));
    public static final GameRules.Key<GameRules.BooleanValue> TRIPOD_SHIELD_FLARE_ALWAYS_ON =
            GameRules.register("sewvTripodShieldFlareAlwaysOn", GameRules.Category.MISC, GameRules.BooleanValue.create(false));
    public static final GameRules.Key<GameRules.BooleanValue> TRIPOD_SHIELD_WIREFRAME =
            GameRules.register("sewvTripodShieldWireframe", GameRules.Category.MISC, GameRules.BooleanValue.create(false));

    /**
     * When on, {@code spawn_probe} blocks render their barrier placeholder (same MODEL path as
     * holding {@code minecraft:barrier}). Toggle with {@code /sewv debug ShowSpawnProbes} or
     * {@code /gamerule sewvShowSpawnProbes}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> SHOW_SPAWN_PROBES =
            GameRules.register("sewvShowSpawnProbes", GameRules.Category.MISC, GameRules.BooleanValue.create(false));

    /**
     * Individual tactics + cover-cache diagnosis ({@code [sewv-diag][posture]} /
     * {@code [sewv-diag][cover]}). Toggle with {@code /gamerule sewvIndividualTacticsDebug} or
     * {@code /sewv debug IndividualTactics true|false}.
     */
    public static final GameRules.Key<GameRules.BooleanValue> INDIVIDUAL_TACTICS_DEBUG =
            GameRules.register("sewvIndividualTacticsDebug", GameRules.Category.MISC, GameRules.BooleanValue.create(false));

    private ModGameRules() {}

    /**
     * Server-side read for a boolean gamerule from anywhere, with no {@code Level} needed at the
     * call site — {@code ServerLifecycleHooks.getCurrentServer()} resolves the running (or
     * integrated singleplayer) server directly. Answers false with no server running (main menu,
     * or a call that raced world unload) rather than throwing.
     *
     * <p>Client-only render code cannot use this (a pure multiplayer client has no server in this
     * JVM) — see the client-side reads in {@code HeliRunPhaseClient}, {@code
     * MixinVehicleTeamOverlay} and {@code ExterminationShieldDebugRenderer}, which go through
     * {@code Minecraft.getInstance().level.getGameRules()} instead.
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
