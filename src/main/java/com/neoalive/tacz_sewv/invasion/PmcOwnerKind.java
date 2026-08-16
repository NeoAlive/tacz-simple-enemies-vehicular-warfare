package com.neoalive.tacz_sewv.invasion;

/** Who owns PMC crews spawned from a team_base when crew faction is PMC. */
public enum PmcOwnerKind {
    NONE,
    PLAYER,
    TEAM;

    public static PmcOwnerKind fromOrdinal(int ordinal) {
        PmcOwnerKind[] vals = values();
        return vals[Math.floorMod(ordinal, vals.length)];
    }

    public static PmcOwnerKind parse(String name) {
        if (name == null || name.isEmpty()) return NONE;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
