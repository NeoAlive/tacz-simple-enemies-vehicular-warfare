package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Exploit a beaten enemy: everyone advances on the enemy centroid. Feasible when the group clearly outweighs the
 * enemy, or when the enemy has lost at least half the strength it peaked at since the group formed.
 */
public final class Pursuit implements Play {

    static final Pursuit INSTANCE = new Pursuit();
    static final double DOMINANT_BALANCE = 3.0;
    /** A "collapse" needs a real force behind it: one tank going down to half a rifleman is not a rout. */
    static final double MIN_PEAK = 2.0;

    private Pursuit() {}

    @Override
    public PlayId id() {
        return PlayId.PURSUIT;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < 2 || bf.enemyCount < 1) return false;
        return bf.forceBalance >= DOMINANT_BALANCE || collapsed(bf, 0.5);
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        Assignment[] out = new Assignment[group.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                    null, null, bf.enemyCentroidX, bf.enemyCentroidZ);
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        // Wider than feasible, so the pursuit does not flap on the threshold.
        if (!bf.populated || group.size() < 2 || bf.enemyCount < 1) return false;
        return bf.forceBalance >= 2.0 || collapsed(bf, 0.6);
    }

    private static boolean collapsed(BattleField bf, double fraction) {
        return bf.peakEnemyWeight >= MIN_PEAK && bf.enemyWeight <= bf.peakEnemyWeight * fraction;
    }
}
