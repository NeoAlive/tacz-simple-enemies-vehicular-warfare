package com.neoalive.tacz_sewv.debug;

import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import org.slf4j.Logger;

/**
 * Temporary Stage 3–5 diagnosis logs. Prefix {@code [sewv-diag]} for grepping.
 * Observe-only — callers must not change control flow based on this class.
 *
 * <p>All channels are config-gated (default off):
 * <ul>
 *   <li>{@link #pathing}/{@link #water}/{@link #pathingEvent}/{@link #waterEvent} —
 *       {@code groundPathingDebug}</li>
 *   <li>{@link #flight} — {@code heliFlightDebug}</li>
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
        // OpenPacCompat (and similar) may call SewvDiag during COMMON_SETUP before the
        // server config is baked — ConfigValue.get() throws in that window in userdev.
        return SewvConfig.SPEC.isLoaded() && SewvConfig.GROUND_PATHING_DEBUG.get();
    }

    /** Helicopter flyToward / hover investigation logs. Default off. */
    public static boolean heliFlightVerbose() {
        return SewvConfig.SPEC.isLoaded() && SewvConfig.HELI_FLIGHT_DEBUG.get();
    }

    /** Non-pathing [sewv-diag] channels. Default off. */
    public static boolean diagEnabled() {
        return SewvConfig.SPEC.isLoaded() && SewvConfig.SEWV_DIAG_DEBUG.get();
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

    /** Helicopter flight-steering / hover diagnosis. No-op when {@link #heliFlightVerbose()} is false. */
    public static void flight(String msg, Object... args) {
        if (!heliFlightVerbose()) return;
        LOG.info("[sewv-diag][flight] " + msg, args);
    }
}
