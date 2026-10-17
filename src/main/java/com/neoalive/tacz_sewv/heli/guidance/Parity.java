package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Which way a pilot goes round: +1 left (positive rotation about +Y), -1 right. Even ids go left,
 * the same predicate as the ground brain's flank choice ({@code TacticalBrain.preferredFlankOf}:
 * FLANK_LEFT on even), so a helicopter's orbit sense, its run break side and its avoidance tie-break
 * can never disagree with each other or with the ground crews.
 */
public final class Parity {

    private Parity() {}

    public static int side(int pilotId) {
        return (pilotId & 1) == 0 ? 1 : -1;
    }
}
