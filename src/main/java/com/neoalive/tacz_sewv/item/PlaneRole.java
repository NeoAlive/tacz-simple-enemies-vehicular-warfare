package com.neoalive.tacz_sewv.item;

/**
 * Which targets a fixed-wing pilot may pick, set from the quick wheel's Airplane Mode. A filter
 * only: the goal still chooses ordnance and flight profile from {@link PlaneAttackMode}.
 */
public enum PlaneRole {
    /** No filter, the crew AI decides. */
    AUTO,
    /** Anything but fixed-wing aircraft. */
    CAS,
    /** Fixed-wing aircraft only. */
    FIGHTER;

    public PlaneRole next() {
        PlaneRole[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public PlaneRole previous() {
        PlaneRole[] values = values();
        return values[(ordinal() + values.length - 1) % values.length];
    }

    /** Whether this role may engage a target that is, or is not, a fixed-wing aircraft. */
    public boolean allows(boolean targetIsPlane) {
        return switch (this) {
            case AUTO -> true;
            case CAS -> !targetIsPlane;
            case FIGHTER -> targetIsPlane;
        };
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static PlaneRole byOrdinal(int ordinal) {
        PlaneRole[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : AUTO;
    }

    public static PlaneRole byKey(String key) {
        for (PlaneRole role : values()) {
            if (role.key().equals(key)) return role;
        }
        return AUTO;
    }
}
