package com.neoalive.tacz_sewv.debug;

import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.config.SewvConfig;
import org.slf4j.Logger;

/**
 * Temporary Stage 3–5 diagnosis logs. Prefix {@code [sewv-diag]} for grepping.
 * Observe-only — callers must not change control flow based on this class.
 *
 * <p>Ground-pathing investigation noise is split in two tiers:
 * <ul>
 *   <li>{@link #pathing}/{@link #water} — verbose, gated by
 *       {@code groundPathingDebug} (default off). Per-tick / per-search spam.</li>
 *   <li>{@link #pathingEvent}/{@link #waterEvent} — rare recovery / fan-summary
 *       transitions; always on, fire only on state changes.</li>
 * </ul>
 * Callers of the verbose tier should still guard expensive arg construction with
 * {@link #groundPathingVerbose()} — Java evaluates arguments before the early return.
 */
public final class SewvDiag {

    public static final Logger LOG = LogUtils.getLogger();

    private SewvDiag() {}

    /** Heavy ground-pathing / shoreline investigation logs. Default off. */
    public static boolean groundPathingVerbose() {
        return SewvConfig.GROUND_PATHING_DEBUG.get();
    }

    public static void targeting(String msg, Object... args) {
        LOG.info("[sewv-diag][targeting] " + msg, args);
    }

    public static void scan(String msg, Object... args) {
        LOG.info("[sewv-diag][scan] " + msg, args);
    }

    public static void setTarget(String msg, Object... args) {
        LOG.info("[sewv-diag][setTarget] " + msg, args);
    }

    public static void claim(String msg, Object... args) {
        LOG.info("[sewv-diag][claim] " + msg, args);
    }

    public static void sweep(String msg, Object... args) {
        LOG.info("[sewv-diag][sweep] " + msg, args);
    }

    public static void invasion(String msg, Object... args) {
        LOG.info("[sewv-diag][invasion] " + msg, args);
    }

    public static void orderAuth(String msg, Object... args) {
        LOG.info("[sewv-diag][orderAuth] " + msg, args);
    }

    public static void diplomacy(String msg, Object... args) {
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
     * Rare pathing events (fan summary, hullFan reverse START/END/SKIP). Always on —
     * only fire on transitions / full-fan misses, not every tick.
     */
    public static void pathingEvent(String msg, Object... args) {
        LOG.info("[sewv-diag][pathing] " + msg, args);
    }

    /**
     * Rare water events (bankLip reverse START/END/ABORT). Always on — transition-only.
     */
    public static void waterEvent(String msg, Object... args) {
        LOG.info("[sewv-diag][water] " + msg, args);
    }
}
