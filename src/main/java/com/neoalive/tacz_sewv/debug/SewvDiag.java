package com.neoalive.tacz_sewv.debug;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.init.ModGameRules;
import com.neoalive.tacz_sewv.util.WarnOnce;

/**
 * Temporary Stage 3–5 diagnosis logs. Prefix {@code [sewv-diag]} for grepping.
 * Observe-only — callers must not change control flow based on this class.
 *
 * <p>All channels are gated by a per-world gamerule (default off), toggled in-game with
 * {@code /gamerule <name> true|false} rather than editing config and reloading:
 * <ul>
 *   <li>{@link #pathing}/{@link #water}/{@link #pathingEvent}/{@link #waterEvent} —
 *       {@code sewvGroundPathingDebug}</li>
 *   <li>{@link #ship} — {@code sewvShipPathingDebug}</li>
 *   <li>{@link #flight} — {@code sewvHeliFlightDebug}</li>
 *   <li>{@link #posture}/{@link #cover} — {@code sewvIndividualTacticsDebug}</li>
 *   <li>Everything else — {@code sewvDiagDebug}</li>
 * </ul>
 * Callers of the verbose pathing / flight tiers should still guard expensive arg construction with
 * {@link #groundPathingVerbose()} / {@link #heliFlightVerbose()} — Java evaluates arguments
 * before the early return.
 */
public final class SewvDiag {

    public static final Logger LOG = LogUtils.getLogger();

    private SewvDiag() {}

    /** Heavy ground-pathing / shoreline investigation logs. Default off. */
    public static boolean groundPathingVerbose() {
        return ModGameRules.server(ModGameRules.GROUND_PATHING_DEBUG);
    }

    /** Heavy ship-pathing / shoreline investigation logs. Default off. */
    public static boolean shipPathingVerbose() {
        return ModGameRules.server(ModGameRules.SHIP_PATHING_DEBUG);
    }

    /** Helicopter flyToward / hover investigation logs. Default off. */
    public static boolean heliFlightVerbose() {
        return ModGameRules.server(ModGameRules.HELI_FLIGHT_DEBUG);
    }

    /** Non-pathing [sewv-diag] channels. Default off. */
    public static boolean diagEnabled() {
        return ModGameRules.server(ModGameRules.SEWV_DIAG_DEBUG);
    }

    /** Individual tactics / cover-cache investigation. Default off. */
    public static boolean individualTacticsVerbose() {
        return ModGameRules.server(ModGameRules.INDIVIDUAL_TACTICS_DEBUG);
    }

    public static void targeting(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][targeting] " + msg, args);
    }

    public static void scan(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][scan] " + msg, args);
    }

    public static void setTarget(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][setTarget] " + msg, args);
    }

    public static void claim(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][claim] " + msg, args);
    }

    public static void sweep(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][sweep] " + msg, args);
    }

    public static void invasion(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][invasion] " + msg, args);
    }

    public static void orderAuth(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][orderAuth] " + msg, args);
    }

    public static void diplomacy(String msg, Object... args) {
        if (!diagEnabled()) return;
        LOG.info("[sewv-diag][diplomacy] " + msg, args);
    }

    /** Verbose ground-pathing noise. No-op when {@link #groundPathingVerbose()} is false. */
    public static void pathing(String msg, Object... args) {
        if (!groundPathingVerbose()) return;
        LOG.info("[sewv-diag][pathing] " + msg, args);
    }

    /** Verbose shoreline / water-margin noise. No-op when {@link #groundPathingVerbose()} is false. */
    public static void water(String msg, Object... args) {
        if (!groundPathingVerbose()) return;
        LOG.info("[sewv-diag][water] " + msg, args);
    }

    /**
     * Rare pathing events (fan summary, hullFan reverse START/END/SKIP). Same gate as
     * {@link #pathing} — {@code groundPathingDebug}.
     */
    public static void pathingEvent(String msg, Object... args) {
        if (!groundPathingVerbose()) return;
        LOG.info("[sewv-diag][pathing] " + msg, args);
    }

    /**
     * Rare water events (bankLip reverse START/END/ABORT). Same gate as {@link #water} —
     * {@code groundPathingDebug}.
     */
    public static void waterEvent(String msg, Object... args) {
        if (!groundPathingVerbose()) return;
        LOG.info("[sewv-diag][water] " + msg, args);
    }

    /** Verbose ship-pathing / shoreline noise. No-op when {@link #shipPathingVerbose()} is false. */
    public static void ship(String msg, Object... args) {
        if (!shipPathingVerbose()) return;
        LOG.info("[sewv-diag][ship] " + msg, args);
    }

    /** Helicopter flight-steering / hover diagnosis. No-op when {@link #heliFlightVerbose()} is false. */
    public static void flight(String msg, Object... args) {
        if (!heliFlightVerbose()) return;
        LOG.info("[sewv-diag][flight] " + msg, args);
    }

    /**
     * Per-crew tactical posture (active tactics, scoot/ambush/shield). Guard expensive arg
     * construction with {@link #individualTacticsVerbose()}.
     */
    public static void posture(String msg, Object... args) {
        if (!individualTacticsVerbose()) return;
        LOG.info("[sewv-diag][posture] " + msg, args);
    }

    /**
     * Cover-cache bake / exposure. Same gate as {@link #posture}. Prefer event-style lines
     * (chunk baked, scoot committed) over per-tick spam.
     */
    public static void cover(String msg, Object... args) {
        if (!individualTacticsVerbose()) return;
        LOG.info("[sewv-diag][cover] " + msg, args);
    }

    /**
     * Refused orders, and why. <b>Default on</b>, unlike every other channel here, because it is
     * silent until something actually fails and one line per reason per tick is not a log volume
     * worth opting into. It is the only report a refusal produces in text — the player-facing half
     * is the crew's spoken reply — so switching it off means a failed order leaves no written trace
     * anywhere.
     */
    public static void orderFail(String msg, Object... args) {
        if (!ModGameRules.server(ModGameRules.ORDER_FAILURE_DEBUG)) return;
        LOG.info("[sewv-diag][order] " + msg, args);
    }

    /** Fixed-wing mode / aim / landing diagnosis. Default off. */
    public static boolean planeVerbose() {
        return ModGameRules.server(ModGameRules.PLANE_COMBAT_DEBUG);
    }

    /** Fixed-wing diagnosis. No-op when {@link #planeVerbose()} is false. */
    public static void plane(String msg, Object... args) {
        if (!planeVerbose()) return;
        LOG.info("[sewv-diag][plane] " + msg, args);
    }

    /**
     * Fixed-wing diagnosis for lines a flight goal would otherwise emit <b>every tick</b>. Twenty
     * identical lines a second per aircraft is not a log, it is a way of hiding the one line that
     * mattered, which is what the first version of the plane channel did.
     *
     * @param gameTime absolute game time; the throttle is a deadline on it rather than a counter,
     *                 because a goal ticks every other tick and a counter would silently halve
     */
    public static void planeThrottled(long gameTime, String msg, Object... args) {
        if (!planeVerbose()) return;
        if (gameTime - lastPlaneLog < PLANE_LOG_INTERVAL_TICKS) return;
        lastPlaneLog = gameTime;
        LOG.info("[sewv-diag][plane] " + msg, args);
    }

    /**
     * The per-tick "what is the aircraft doing" heartbeat, on a deadline of its <b>own</b>.
     *
     * <p>It cannot share {@link #planeThrottled}'s budget. The heartbeat runs every tick and runs
     * <em>first</em>, so it claimed every window and permanently starved the lines that say why
     * something did not happen — "holding fire ... gate=CONE err=14" and "run refused" were
     * unreachable for as long as the channel was on, which is exactly when they are wanted.
     */
    public static void planeHeartbeat(long gameTime, String msg, Object... args) {
        if (!planeVerbose()) return;
        if (gameTime - lastPlaneHeartbeat < PLANE_LOG_INTERVAL_TICKS) return;
        lastPlaneHeartbeat = gameTime;
        LOG.info("[sewv-diag][plane] " + msg, args);
    }

    /** ~1 s between throttled plane lines. */
    private static final long PLANE_LOG_INTERVAL_TICKS = 20L;
    private static volatile long lastPlaneLog = Long.MIN_VALUE;
    private static volatile long lastPlaneHeartbeat = Long.MIN_VALUE;

    /**
     * Said once, ever, the first time a fixed-wing AI takes a hull — at INFO whether or not the
     * channel is on. It answers the question the channel cannot answer about itself: a player who
     * turns the flag on and still sees nothing has no way to tell "the AI is not running" from
     * "the flag did not take", and those need completely different fixes.
     */
    public static void planeAttached() {
        WarnOnce.info(LOG, "sewv-plane-ai", "[sewv-plane] fixed-wing AI active"
                + (planeVerbose() ? " (sewvPlaneCombatDebug on — per-tick detail follows)"
                        : " (run '/gamerule sewvPlaneCombatDebug true' for flight detail)"));
    }
}
