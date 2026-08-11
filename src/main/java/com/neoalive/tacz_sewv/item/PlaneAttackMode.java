package com.neoalive.tacz_sewv.item;

/**
 * Which ordnance a close-air-support crew is being asked for, chosen on the radio GUI and carried
 * with the fire mission to the pilot it tasks.
 *
 * <p>The mode is a <b>weapon</b> instruction, and picking a weapon picks a flight profile with it:
 * a gun has to be pointed, so it is flown as a dive, while a bomb has to be released from level
 * flight along the target's track and a fire-and-forget missile only needs the target in front. The
 * profiles live in the goal; this enum is the request.
 *
 * <p>{@link #AUTO} is the default because it is the only one that is right without the player
 * knowing what the aircraft is carrying or how far out it is.
 */
public enum PlaneAttackMode {
    /**
     * Let the crew choose, by range. Ordnance is excluded as the target gets closer — heavy stores
     * need room to fall or to guide, so the last thing left to a crew right on top of a target is
     * its gun. See {@code PlaneWeapons.tierForRange}.
     */
    AUTO,
    /** Free-fall stores, released in a stick from a level overfly. */
    BOMB,
    /** Guns first, rockets if the hull has no gun — a strafing pass either way. */
    CANNON,
    /** Guided missiles only: launched at range, steering themselves in. */
    GUIDED;

    public PlaneAttackMode next() {
        PlaneAttackMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static PlaneAttackMode byOrdinal(int ordinal) {
        PlaneAttackMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : AUTO;
    }
}
