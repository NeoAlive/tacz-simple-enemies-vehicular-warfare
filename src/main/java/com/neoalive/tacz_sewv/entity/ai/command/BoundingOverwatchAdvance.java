package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Advance while one element always overwatches — phased movement.
 */
public final class BoundingOverwatchAdvance implements Play {

    static final BoundingOverwatchAdvance INSTANCE = new BoundingOverwatchAdvance();

    private BoundingOverwatchAdvance() {}

    @Override
    public PlayId id() {
        return PlayId.BOUNDING_OVERWATCH_ADVANCE;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < 2) return false;
        if (bf.pocketCount < 1 && bf.enemyCount < 1) return false;
        return bf.forceBalance >= 0.75;
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        // Advance point: midway toward enemy along the axis.
        double ax = bf.friendlyCentroidX + bf.axisX * 20.0;
        double az = bf.friendlyCentroidZ + bf.axisZ * 20.0;
        double[] overwatch = PlayGeometry.bofPoint(bf);

        int[] order = PlayGeometry.orderByLeft(bf, group);
        int n = group.size();
        int overwatchN = Math.max(1, n / 2);
        Assignment[] out = new Assignment[n];
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (k < overwatchN) {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.OVERWATCH,
                        null, null, overwatch[0], overwatch[1]);
            } else {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                        null, null, ax, az);
            }
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        if (!bf.populated || group.size() < 2) return false;
        // Abort when the advance is no longer supported — force collapses or enemy gone.
        if (bf.enemyCount < 1 && bf.pocketCount < 1) return false;
        return bf.forceBalance >= 0.6;
    }
}
