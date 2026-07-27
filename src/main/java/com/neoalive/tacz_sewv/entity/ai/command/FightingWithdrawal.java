package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Leapfrog back under mutual cover — the reversal target when aggressive plays abort.
 */
public final class FightingWithdrawal implements Play {

    static final FightingWithdrawal INSTANCE = new FightingWithdrawal();

    private FightingWithdrawal() {}

    @Override
    public PlayId id() {
        return PlayId.FIGHTING_WITHDRAWAL;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < 2) return false;
        // Escape hatch when outnumbered or even — not when dominating.
        return bf.forceBalance <= 1.05;
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        double[] back = PlayGeometry.withdrawPoint(bf);
        double[] cover = PlayGeometry.bofPoint(bf);
        int[] order = PlayGeometry.orderByLeft(bf, group);
        int n = group.size();
        int coverN = Math.max(1, n / 2);
        Assignment[] out = new Assignment[n];
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (k < coverN) {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.OVERWATCH,
                        null, null, cover[0], cover[1]);
            } else {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.WITHDRAW,
                        null, null, back[0], back[1]);
            }
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        if (!bf.populated || group.size() < 1) return false;
        // Done withdrawing once force recovers — hand back to aggressive / hold menu.
        return bf.forceBalance <= 1.15;
    }
}
