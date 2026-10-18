package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Advance on the enemy as one: the hulls abreast, a file of our own infantry walking in the lee of each, the whole
 * line held to a walk so nobody is left behind. The approach, not the brawl — once the line is close the fighting
 * plays take over.
 *
 * <p>Feasible when the group is confident (it outweighs what it faces) and has its infantry with it: at least one
 * rifleman per hull close to the hulls — and while there is still room to advance. The play only deals the role; the line itself — pace, slots, the infantry
 * files, waiting for stragglers — is a {@code COHESIVE_LINE} march the coordinator keeps for the group while this
 * play stands ({@code entity/ai/march}).
 */
public final class CohesiveLine implements Play {

    static final CohesiveLine INSTANCE = new CohesiveLine();
    /** Confidence is up: the group outweighs the enemy by this much. */
    static final double CONFIDENT_BALANCE = 1.2;
    /** Below this the advance is no longer supported and the play aborts. */
    static final double HOLD_BALANCE = 1.0;
    /**
     * The line starts only from further out than this (blocks, × tactical scale)... It must leave a real advance
     * before {@link #HANDOVER_DISTANCE}: at 40 against 35 the road was shorter than one scouted leg, the march was
     * refused, and the play flapped in and out every pass.
     */
    static final double START_DISTANCE = 60.0;
    /** ...and hands over to the fighting plays once inside this. */
    static final double HANDOVER_DISTANCE = 35.0;

    private CohesiveLine() {}

    @Override
    public PlayId id() {
        return PlayId.COHESIVE_LINE;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < 2 || bf.enemyCount < 1) return false;
        return bf.forceBalance >= CONFIDENT_BALANCE
                && bf.friendlyInfantry >= group.size()
                && PlayGeometry.enemyDistance(bf) > TacticalScale.of(START_DISTANCE);
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        // One point for the whole line; PlayFormations spreads it into the cohesive_line template's line.
        double[] advance = PlayGeometry.advancePoint(bf);
        Assignment[] out = new Assignment[group.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER, null, null, advance[0], advance[1]);
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        if (!bf.populated || group.size() < 2 || bf.enemyCount < 1) return false;
        // Wider than feasible on every axis, so the line does not flap on a threshold.
        return bf.forceBalance >= HOLD_BALANCE
                && bf.friendlyInfantry * 2 >= group.size()
                && PlayGeometry.enemyDistance(bf) > TacticalScale.of(HANDOVER_DISTANCE);
    }
}
