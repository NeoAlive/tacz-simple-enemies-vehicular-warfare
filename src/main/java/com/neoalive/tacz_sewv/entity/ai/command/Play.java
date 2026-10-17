package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * One grand-tactic option. Feasibility is a hard gate; score is soft; {@link #stillValid} is the
 * abort / reversal condition — never a constant {@code true}.
 */
public interface Play {

    PlayId id();

    /** Hard preconditions — infeasible ≠ low score. */
    boolean feasible(BattleField bf, GroupSnapshot group);

    /** Soft utility via {@link UtilityWeights#scorePlay}. */
    double score(BattleField bf, GroupSnapshot group, UtilityWeights weights);

    /** Carve BoF / maneuver / reserve from BattleField geometry. */
    Roles assignRoles(BattleField bf, GroupSnapshot group);

    /**
     * Abort when the play can no longer develop. Immediate — bypasses min-duration hysteresis.
     * Must encode a real failure mode for this play (never {@code return true}).
     *
     * @param roles last committed assignments (may be empty on first commit check)
     */
    boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles);
}
