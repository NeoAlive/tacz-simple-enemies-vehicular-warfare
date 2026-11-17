package com.neoalive.tacz_sewv.zone;

/** Spawn rule of one chunk. Ordinal is saved and sent on the wire: append only. */
public enum ZoneType {
    NEUTRAL,
    PRIORITY,
    NOZONE;

    public static ZoneType byId(int id) {
        ZoneType[] all = values();
        return id >= 0 && id < all.length ? all[id] : NEUTRAL;
    }

    public static ZoneType parse(String s) {
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "prioritization", "priority" -> PRIORITY;
            case "nozone", "no_zone" -> NOZONE;
            default -> null;
        };
    }
}
