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
    /**
     * This member's slot in its role's formation, or -1 before one is laid. A play deals the role's shared
     * point; {@code PlayFormations} turns it into a slot, and the next pass reads the slot back to keep
     * members where they were.
     */
    public final int slot;
    /** Heading to hold on the slot, unit XZ; NaN when the role has none. */
    public final double facingX;
    public final double facingZ;

    public Assignment(int unitId, Role role, @Nullable Integer priorityTargetId,
                      @Nullable FlankSide flankSide, double destX, double destZ) {
        this(unitId, role, priorityTargetId, flankSide, destX, destZ, -1, Double.NaN, Double.NaN);
    }

    public Assignment(int unitId, Role role, @Nullable Integer priorityTargetId,
                      @Nullable FlankSide flankSide, double destX, double destZ,
                      int slot, double facingX, double facingZ) {
        this.unitId = unitId;
        this.role = role;
        this.priorityTargetId = priorityTargetId;
        this.flankSide = flankSide;
        this.destX = destX;
        this.destZ = destZ;
        this.slot = slot;
        this.facingX = facingX;
        this.facingZ = facingZ;
    }

    public boolean hasFacing() {
        return Double.isFinite(this.facingX) && Double.isFinite(this.facingZ);
    }

    /** Same assignment, moved to {@code (x, z)} — slot and facing kept. */
    Assignment withDest(double x, double z) {
        return new Assignment(this.unitId, this.role, this.priorityTargetId, this.flankSide, x, z,
                this.slot, this.facingX, this.facingZ);
    }

    Assignment withPriorityTarget(@Nullable Integer target) {
        return new Assignment(this.unitId, this.role, target, this.flankSide, this.destX, this.destZ,
                this.slot, this.facingX, this.facingZ);
    }

    /** This unit takes {@code donor}'s whole task — role, side, point, slot and facing. */
    Assignment withTaskOf(Assignment donor) {
        return new Assignment(this.unitId, donor.role, this.priorityTargetId, donor.flankSide,
                donor.destX, donor.destZ, donor.slot, donor.facingX, donor.facingZ);
    }

    Assignment withSlot(int slot, double x, double z, double facingX, double facingZ) {
        return new Assignment(this.unitId, this.role, this.priorityTargetId, this.flankSide, x, z,
                slot, facingX, facingZ);
    }
}
