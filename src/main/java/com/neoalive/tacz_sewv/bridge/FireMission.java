package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;

/**
 * A standing order to shell a place until a deadline.
 *
 * <p>The deadline is an absolute <b>game time</b>, not a countdown, and that is the whole
 * reason this persists correctly: game time keeps advancing across a save/load, so an
 * expiry stored as "at tick N" still means the same moment after a restart. A "ticks
 * remaining" counter would have to be ticked down by something, and the something that
 * would tick it is exactly what stops running when the chunk unloads.
 *
 * <p>Held by {@link IMortarCrew}; see {@link IMortarCrew#sewv$getFireMission} for why a crew
 * needs to be told about a place at all.
 */
public record FireMission(BlockPos pos, long expiresAt) {

    /**
     * A mission with no end. {@code gameTime >= Long.MAX_VALUE} is never true, so this needs
     * no sentinel branch anywhere — it simply never expires.
     */
    public static final long NEVER = Long.MAX_VALUE;

    /** An indefinite mission, for a crew nobody has given a deadline. */
    public static FireMission standing(BlockPos pos) {
        return new FireMission(pos, NEVER);
    }

    public boolean isExpired(long gameTime) {
        return gameTime >= this.expiresAt;
    }
}
