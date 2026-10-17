package com.neoalive.tacz_sewv.entity.ai.command;

import javax.annotation.Nullable;

/**
 * One crew's role inside a play. {@code priorityTargetId} is stored for Stage 5 focus-fire;
 * Stage 4 does not bias targeting yet.
 */
public final class Assignment {

    public enum Role {
        BASE_OF_FIRE,
        MANEUVER,
        OVERWATCH,
        RESERVE,
        HOLD,
        WITHDRAW,
        /** Out-of-contact polygon formation hold. */
        IDLE_HOLD,
        /** Out-of-contact constant-bearing column travel. */
        IDLE_TRAVEL
    }

    /** Open-flank side relative to the enemy→us axis, when the role is a flank maneuver. */
    public enum FlankSide {
        LEFT, RIGHT
    }

    public final int unitId;
    public final Role role;
    @Nullable
    public final Integer priorityTargetId;
    @Nullable
    public final FlankSide flankSide;
    /** World XZ destination for the role (BoF hold point, flank mark, withdraw point, …). */
    public final double destX;
    public final double destZ;

    public Assignment(int unitId, Role role, @Nullable Integer priorityTargetId,
                      @Nullable FlankSide flankSide, double destX, double destZ) {
        this.unitId = unitId;
        this.role = role;
        this.priorityTargetId = priorityTargetId;
        this.flankSide = flankSide;
        this.destX = destX;
        this.destZ = destZ;
    }
}
