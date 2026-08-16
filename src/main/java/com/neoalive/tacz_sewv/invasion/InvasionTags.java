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

    /**
     * Scoreboard team name that owns this PMC for order auth when the team base's PMC Owner is a
     * team (membership re-polled live — not a frozen UUID list).
     */
    public static final String PMC_OWNER_TEAM = "sewv:pmc_owner_team";

    /**
     * Scoreboard team names this crew treats as enemies (ListTag of strings), stamped from the
     * spawning team_base's enemy list. Empty = invasion hostility does not apply.
     */
    public static final String ENEMIES = "sewv:invasion_enemies";

    private InvasionTags() {}
}
