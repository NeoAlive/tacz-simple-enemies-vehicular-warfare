package com.neoalive.tacz_sewv.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

/**
 * At-most-once warning per key so a hot AI path can log a datapack failure without spamming.
 */
public final class WarnOnce {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private WarnOnce() {}

    public static void warn(Logger log, String key, String message, Throwable t) {
        if (SEEN.add(key)) {
            log.warn(message, t);
        }
    }

    public static void warn(Logger log, String key, String message) {
        if (SEEN.add(key)) {
            log.warn(message);
        }
    }

    /** Same at-most-once guarantee for a line that reports normal operation rather than a fault. */
    public static void info(Logger log, String key, String message) {
        if (SEEN.add(key)) {
            log.info(message);
        }
    }
}
