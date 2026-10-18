package com.neoalive.tacz_sewv.invasion.force;

import javax.annotation.Nullable;

import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;

/**
 * What a team_base fields, and what a unit or hull was fielded as (stamped in
 * {@link com.neoalive.tacz_sewv.invasion.InvasionTags#KIND}). Ordinal is the NBT tag value and the
 * wire order of {@link BaseForce}: append, never reorder.
 */
public enum ForceKind {
    GROUND("ground"),
    ROTOR("rotor"),
    PLANE("plane"),
    INFANTRY("infantry");

    public static final ForceKind[] VEHICLES = {GROUND, ROTOR, PLANE};

    private final String key;

    ForceKind(String key) {
        this.key = key;
    }

    /** NBT key and lang suffix ({@code gui.tacz_sewv.invasion.force.<key>}). */
    public String key() {
        return key;
    }

    public boolean isVehicle() {
        return this != INFANTRY;
    }

    /** Aircraft never take part in capture — they neither capture nor contest a zone. */
    public boolean isAircraft() {
        return this == ROTOR || this == PLANE;
    }

    /** World pool this kind's "fill from world pool" reads, or null for infantry. */
    @Nullable
    public Category poolCategory() {
        return switch (this) {
            case GROUND -> Category.GROUND;
            case ROTOR -> Category.ROTOR;
            case PLANE -> Category.PLANE;
            case INFANTRY -> null;
        };
    }

    public static ForceKind fromOrdinal(int ordinal) {
        ForceKind[] vals = values();
        return vals[Math.floorMod(ordinal, vals.length)];
    }

    @Nullable
    public static ForceKind byKey(String key) {
        for (ForceKind kind : values()) {
            if (kind.key.equals(key)) return kind;
        }
        return null;
    }
}
