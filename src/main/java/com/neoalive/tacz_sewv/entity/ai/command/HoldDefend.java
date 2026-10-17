package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Always-feasible floor — the group-level HOLD. Hull-down / hold the line at the friendly
 * centroid. Guarantees selection never returns empty.
 */
public final class HoldDefend implements Play {

    static final HoldDefend INSTANCE = new HoldDefend();

    private HoldDefend() {}

    @Override
    public PlayId id() {
        return PlayId.HOLD_DEFEND;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        return group.size() >= 1;
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        double hx = bf.populated ? bf.friendlyCentroidX : 0.0;
        double hz = bf.populated ? bf.friendlyCentroidZ : 0.0;
        if (bf.populated) {
            double[] bof = PlayGeometry.bofPoint(bf);
            hx = bof[0];
            hz = bof[1];
        }
        Assignment[] out = new Assignment[group.size()];
        for (int i = 0; i < group.size(); i++) {
            out[i] = new Assignment(group.memberIds[i], Assignment.Role.HOLD,
                    null, null, hx, hz);
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        // Abort when the group is gone, or when we are being crushed so hard withdrawal must
        // preempt — Hold is the floor for *feasibility*, not an immortal commitment.
        if (group.size() < 1) return false;
        if (bf.populated && bf.forceBalance < 0.35 && bf.enemyCount > 0) return false;
        return group.size() >= 1;
    }
}
