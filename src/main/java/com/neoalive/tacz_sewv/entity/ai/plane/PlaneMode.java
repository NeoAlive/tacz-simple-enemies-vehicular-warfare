package com.neoalive.tacz_sewv.entity.ai.plane;

/**
 * The one flight state a fixed-wing crew is in. Replaces the old two-value attack phase plus the
 * ad-hoc takeoff/landing branches scattered through {@code DrivePlaneGoal.tick}, which is what
 * allowed a plane to be "landing" and "attacking" at once and to fly an attack run while a land
 * order stood.
 *
 * <p>Ordering is meaningless — this is a state label, not a ladder. Precedence between modes lives
 * in {@code DrivePlaneGoal.chooseMode}, in one readable block, so a new mode cannot silently
 * outrank an emergency.
 */
public enum PlaneMode {

    /** Parked with no takeoff order. Inputs released; sticky until an order arrives. */
    GROUNDED,
    /** Ground roll down a cleared heading, rotate at speed. */
    TAKEOFF,
    /** Airborne off the roll, climbing to the cruise band before normal duty resumes. */
    CLIMBOUT,
    /** Flying to a resolved destination (order, escort, ally assist). */
    CRUISE,
    /** No destination: a closed circular hold about an anchor, not an open-loop turn. */
    HOLD,
    /** Target held but outside the engage bubble: close, do not dive. */
    INGRESS,
    /** On the run: aim, hold the line, fire when the nose is genuinely on the aim point. */
    ATTACK,
    /** Pass over: climb away and reverse for the next run. */
    BREAK,
    /** Leash exceeded or ordered home: return to the anchor before anything else. */
    RTB,
    /** Flying the approach pattern onto the final axis at transit height. */
    LAND_PATTERN,
    /** Established on the approach axis: glideslope, flare, touchdown. */
    LAND_FINAL,
    /** Down and shut off. Sticky until a new takeoff order. */
    LANDED;

    /** Combat modes — the ones that need a live target to be meaningful. */
    public boolean needsTarget() {
        return this == INGRESS || this == ATTACK || this == BREAK;
    }

    /** Modes that own the whole tick and may not be interrupted by combat or orders. */
    public boolean isCommitted() {
        return this == TAKEOFF || this == LAND_PATTERN || this == LAND_FINAL
                || this == LANDED || this == GROUNDED;
    }

    /** Landing pair, for the "am I on an approach" tests that must cover both halves. */
    public boolean isLanding() {
        return this == LAND_PATTERN || this == LAND_FINAL;
    }
}
