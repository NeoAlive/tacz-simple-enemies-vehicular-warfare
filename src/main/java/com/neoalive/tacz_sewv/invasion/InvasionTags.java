package com.neoalive.tacz_sewv.invasion;

/**
 * Persistent-NBT keys for invasion-tagged entities (Stage E writes these at spawn).
 */
public final class InvasionTags {

    /** Scoreboard team name this unit/hull fights for during an invasion. */
    public static final String TEAM = "sewv:invasion_team";

    /** Marked for despawn on {@code /sewv invasion stop}. */
    public static final String SPAWN = "sewv:invasion_spawn";

    /** {@link net.minecraft.core.BlockPos#asLong()} of the team_base that spawned this hull. */
    public static final String BASE = "sewv:invasion_base";

    /** AI-base fleet hull (topped up to that base's {@code aiVehicleCount} while the session is live). */
    public static final String AI = "sewv:invasion_ai";

    private InvasionTags() {}
}
