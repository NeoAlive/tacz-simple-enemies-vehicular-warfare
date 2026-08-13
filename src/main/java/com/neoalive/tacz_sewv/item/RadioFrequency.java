package com.neoalive.tacz_sewv.item;

import java.util.EnumSet;
import java.util.Set;

import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;

/** Fire-mission band selected on the handheld radio GUI. */
public enum RadioFrequency {
    MORTAR,
    AIR,
    ARTILLERY,
    TOW;

    public Set<FireMissionSupport.Kind> kinds() {
        return switch (this) {
            case MORTAR -> EnumSet.of(FireMissionSupport.Kind.MORTAR);
            case AIR -> EnumSet.of(FireMissionSupport.Kind.CAS);
            case ARTILLERY -> EnumSet.of(FireMissionSupport.Kind.ARTILLERY);
            case TOW -> EnumSet.of(FireMissionSupport.Kind.TOW);
        };
    }

    /** Mortar tubes (fixed and vehicle) accept a coordinated delay timer. */
    public boolean supportsDelay() {
        return this == MORTAR;
    }

    /**
     * Grid designation rather than a live entity. Mortars shell the mark; aircraft bomb it
     * ({@link com.neoalive.tacz_sewv.bridge.FireMission} on the pilot). TOW / artillery stay
     * entity-only — they need a lock, not a grid square.
     */
    public boolean supportsPositionTarget() {
        return this == MORTAR || this == AIR;
    }

    /**
     * The crew shoots along its own line of sight, so an obstructed target is a refusal rather than
     * the normal case. Mortars and artillery are the opposite — shelling something nobody can see is
     * what they are for — and aircraft bring their own eyes.
     */
    public boolean directFire() {
        return this == TOW;
    }

    public RadioFrequency next() {
        RadioFrequency[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
