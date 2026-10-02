package com.neoalive.tacz_sewv.notify;

import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Wire-stable HUD notification kinds. Ordinals are persisted on the packet — append only,
 * never reorder. Each kind owns its header accent and Unicode icon for the signal-ticket HUD.
 */
public enum NotificationKind {

    /** PMC unit downed — player attention. */
    PMC_DOWNED(0xFF7F1D1D, "\u271D"),
    /** PMC unit killed — player attention. */
    PMC_KILLED(0xFF7F1D1D, "\u2620"),
    /** Owned PMC hull destroyed — player attention. */
    VEHICLE_DESTROYED(0xFF7F1D1D, "\u2620"),
    /** Owned unit / emplacement out of ammo. */
    PMC_AMMO(0xFF6B7280, "\u25CE"),
    /** Owned hull low on energy. */
    PMC_ENERGY(0xFF6B7280, "\u26FD"),
    /** Owned unit engaging a contact. */
    PMC_ENGAGING(0xFF6B7280, "\u2694"),
    /** Enemy medic captured by the player / PMC. */
    MEDIC_CAPTURED(0xFF6B7280, "\u25C6"),
    /** Territory / Advance Plan / legacy sweep status. */
    TERRITORY(0xFF6B7280, "\u25C6"),
    /** Faction-vs-faction world event nearby. */
    EVENT_EVE(0xFF2563EB, "\u2691"),
    /** Player-threatening world event nearby. */
    EVENT_PVE(0xFFDC2626, "\u2691"),
    /** Debug / uncategorised. */
    GENERIC(0xFF6B7280, "\u25C6");

    private static final Set<String> EVE_EVENTS = Set.of(
            "convoy", "large_combat", "naval_battle", "far_combat", "military_patrol", "overflight");
    private static final Set<String> PVE_EVENTS = Set.of(
            "mortar_shelling", "asymmetric_invasion", "derelict_vehicle", "cave_extraction");

    private final int accentArgb;
    private final String icon;

    NotificationKind(int accentArgb, String icon) {
        this.accentArgb = accentArgb;
        this.icon = icon;
    }

    /** Solid header fill (ARGB). */
    public int accentArgb() {
        return this.accentArgb;
    }

    /** Slightly darker tile behind the Unicode glyph. */
    public int iconTileArgb() {
        int a = (this.accentArgb >>> 24) & 0xFF;
        int r = Math.max(0, ((this.accentArgb >>> 16) & 0xFF) - 28);
        int g = Math.max(0, ((this.accentArgb >>> 8) & 0xFF) - 28);
        int b = Math.max(0, (this.accentArgb & 0xFF) - 28);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public String icon() {
        return this.icon;
    }

    public static NotificationKind byOrdinal(int ordinal) {
        NotificationKind[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return GENERIC;
        return values[ordinal];
    }

    /** Resolve a SEM / SEWV event id to EvE or PvE; unknown ids default to EvE. */
    public static NotificationKind forEventId(@Nullable String eventId) {
        if (eventId == null || eventId.isEmpty()) return EVENT_EVE;
        String id = eventId.toLowerCase(Locale.ROOT);
        if (PVE_EVENTS.contains(id)) return EVENT_PVE;
        if (EVE_EVENTS.contains(id)) return EVENT_EVE;
        return EVENT_EVE;
    }

    /** Parse a debug / command token; unknown → {@link #GENERIC}. */
    public static NotificationKind parse(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return GENERIC;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return GENERIC;
        }
    }
}
