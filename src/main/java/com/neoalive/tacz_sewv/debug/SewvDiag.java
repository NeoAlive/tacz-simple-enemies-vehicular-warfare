package com.neoalive.tacz_sewv.debug;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Temporary Stage 3–5 diagnosis logs. Prefix {@code [sewv-diag]} for grepping.
 * Observe-only — callers must not change control flow based on this class.
 */
public final class SewvDiag {

    public static final Logger LOG = LogUtils.getLogger();

    private SewvDiag() {}

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

    public static void orderAuth(String msg, Object... args) {
        LOG.info("[sewv-diag][orderAuth] " + msg, args);
    }

    public static void diplomacy(String msg, Object... args) {
        LOG.info("[sewv-diag][diplomacy] " + msg, args);
    }
}
