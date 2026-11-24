package com.neoalive.tacz_sewv.entity.ai.targeting;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * One row of a target priority stack, in its text form:
 * <ul>
 *   <li>{@code ns:path}: an entity type, matched on the target or the hull it rides</li>
 *   <li>{@code @modid}: every type of that namespace</li>
 *   <li>{@code cat:name}: a MobCategory, Forge-extended ones included</li>
 *   <li>{@code #ns:path}: an entity type tag</li>
 *   <li>{@code group:name}: a built-in group ({@link Group})</li>
 * </ul>
 */
public record StackEntry(Kind kind, String value) {

    public enum Kind { TYPE, MOD, CATEGORY, TAG, GROUP }

    /** Built-in groups. Gunnery classes are judged by the observer's own weapons. */
    public enum Group {
        HARD_ARMOUR("hard_armour", false),
        LIGHT_ARMOUR("light_armour", false),
        UNARMOURED_VEHICLE("unarmoured_vehicle", false),
        EMPLACEMENTS("emplacements", false),
        AIRCRAFT("aircraft", false),
        HEAVY_CREATURE("heavy_creature", true),
        TROOPS("troops", false),
        PLAYERS("players", false);

        public final String key;
        /** May sit in Excluded: only groups that describe plain mobs. */
        public final boolean excludable;

        Group(String key, boolean excludable) {
            this.key = key;
            this.excludable = excludable;
        }

        @Nullable
        public static Group byKey(String key) {
            for (Group g : values()) {
                if (g.key.equals(key)) return g;
            }
            return null;
        }
    }

    @Nullable
    public static StackEntry parse(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 128) return null;
        if (s.startsWith("group:")) {
            Group g = Group.byKey(s.substring(6));
            return g == null ? null : new StackEntry(Kind.GROUP, g.key);
        }
        if (s.startsWith("cat:")) {
            String name = s.substring(4);
            return validPath(name) ? new StackEntry(Kind.CATEGORY, name) : null;
        }
        if (s.startsWith("@")) {
            String ns = s.substring(1);
            return validNamespace(ns) ? new StackEntry(Kind.MOD, ns) : null;
        }
        if (s.startsWith("#")) {
            String id = s.substring(1);
            return validId(id) ? new StackEntry(Kind.TAG, id) : null;
        }
        return validId(s) ? new StackEntry(Kind.TYPE, s) : null;
    }

    public String text() {
        return switch (kind) {
            case TYPE -> value;
            case MOD -> "@" + value;
            case CATEGORY -> "cat:" + value;
            case TAG -> "#" + value;
            case GROUP -> "group:" + value;
        };
    }

    /** How narrowly the row names a target: id 4, tag 3, mod 2, category or group 1. */
    public int specificity() {
        return switch (kind) {
            case TYPE -> 4;
            case TAG -> 3;
            case MOD -> 2;
            case CATEGORY, GROUP -> 1;
        };
    }

    /**
     * Whether a target stays excluded, given the most specific Excluded row it matches and the most
     * specific Priority row (0 = none). A narrower Priority row wins, so naming a mob's id in
     * Priority lifts a broad {@code cat:misc} exclusion; a tie stays excluded.
     */
    public static boolean stillExcluded(int excludedSpecificity, int prioritySpecificity) {
        return excludedSpecificity > 0 && excludedSpecificity >= prioritySpecificity;
    }

    @Nullable
    public Group group() {
        return kind == Kind.GROUP ? Group.byKey(value) : null;
    }

    /**
     * Whether this row may sit in Excluded. Exclusion never applies to troops, players or crewed
     * hulls, so their groups and vehicle ids are refused there; a vehicle id is only known on the
     * server, so {@code isVehicleType} answers that part.
     */
    public boolean excludable(java.util.function.Predicate<String> isVehicleType) {
        return switch (kind) {
            case GROUP -> group() != null && group().excludable;
            case TYPE -> !isVehicleType.test(value);
            default -> true;
        };
    }

    private static boolean validId(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1 || s.indexOf(':', colon + 1) >= 0) return false;
        return validNamespace(s.substring(0, colon)) && validPath(s.substring(colon + 1));
    }

    private static boolean validNamespace(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    private static boolean validPath(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || c == '/' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }
}
