package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.util.VehiclePoolCatalogSource;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Client cache of vehicle entity ids for the pool editor autocomplete.
 *
 * <p>Built lazily ({@link #ensureLoaded}) — on first editor open or after login — not during mod
 * startup, so registry/datapack init from other mods is not raced.
 */
public final class VehiclePoolCatalog {

    private static volatile List<String> ids = List.of();
    private static volatile boolean ready = false;

    private VehiclePoolCatalog() {}

    public static boolean isReady() {
        return ready;
    }

    public static List<String> ids() {
        return ids;
    }

    /**
     * Build once on the client game thread. Safe to call from screen {@code init} or a deferred
     * login hook.
     */
    public static void ensureLoaded() {
        if (ready) return;
        build();
    }

    /** Re-scan when the first pass produced nothing (datapacks may have arrived late). */
    public static void rebuildIfEmpty() {
        if (!ready || !ids.isEmpty()) return;
        ready = false;
        build();
    }

    private static void build() {
        try {
            ids = List.copyOf(VehiclePoolCatalogSource.scan());
        } catch (Throwable ignored) {
            ids = List.of();
        }
        ready = true;
    }

    /**
     * Union of client scan and server snapshot from the open packet — either alone may be
     * incomplete on the first frame after connect.
     */
    public static List<String> mergedWith(List<String> serverCatalog) {
        ensureLoaded();
        if (serverCatalog.isEmpty()) return ids();
        if (ids.isEmpty()) return List.copyOf(serverCatalog);
        TreeSet<String> merged = new TreeSet<>(ids);
        merged.addAll(serverCatalog);
        return List.copyOf(merged);
    }

    /**
     * Best catalog id for autocomplete: prefix match first (shortest id), then substring match.
     * Skips ids already in {@code excluded}.
     */
    @Nullable
    public static String suggest(String typed, Collection<String> catalog, Collection<String> excluded) {
        if (catalog.isEmpty()) return null;
        String q = typed.trim();
        if (q.isEmpty()) {
            for (String id : catalog) {
                if (!excluded.contains(id)) return id;
            }
            return null;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        String bestPrefix = null;
        for (String id : catalog) {
            if (excluded.contains(id)) continue;
            String idLower = id.toLowerCase(Locale.ROOT);
            if (!idLower.startsWith(lower) && !idLower.endsWith(":" + lower)) continue;
            if (bestPrefix == null || id.length() < bestPrefix.length()) bestPrefix = id;
        }
        if (bestPrefix != null) return bestPrefix;
        for (String id : catalog) {
            if (excluded.contains(id)) continue;
            if (id.toLowerCase(Locale.ROOT).contains(lower)) return id;
        }
        return null;
    }

    /** Suffix after {@code typed} for inline ghost text (path-only or full-id prefix). */
    public static String completionSuffix(String typed, String suggestion) {
        if (suggestion == null || typed == null) return "";
        String t = typed.trim();
        if (t.isEmpty()) return suggestion;
        String lower = t.toLowerCase(Locale.ROOT);
        String sugLower = suggestion.toLowerCase(Locale.ROOT);
        if (sugLower.startsWith(lower)) {
            return suggestion.substring(t.length());
        }
        int colon = sugLower.lastIndexOf(':');
        if (colon >= 0 && sugLower.substring(colon + 1).startsWith(lower)) {
            return suggestion.substring(colon + 1 + lower.length());
        }
        return "";
    }
}
