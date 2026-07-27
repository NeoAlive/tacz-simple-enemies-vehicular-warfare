package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Two maneuver wings on both open flanks; BoF in the centre. Higher feasibility bar.
 */
public final class DoubleEnvelopment implements Play {

    static final DoubleEnvelopment INSTANCE = new DoubleEnvelopment();
    static final int MIN_MEMBERS = 4;

    private DoubleEnvelopment() {}

    @Override
    public PlayId id() {
        return PlayId.DOUBLE_ENVELOPMENT;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < MIN_MEMBERS) return false;
        if (bf.pocketCount < 1) return false;
        if (!bf.openFlankLeft || !bf.openFlankRight) return false;
        return bf.forceBalance >= 1.0;
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        double lx = PlaySignals.flankMarkX(bf, +1);
        double lz = PlaySignals.flankMarkZ(bf, +1);
        double rx = PlaySignals.flankMarkX(bf, -1);
        double rz = PlaySignals.flankMarkZ(bf, -1);
        double[] bof = PlayGeometry.bofPoint(bf);

        int[] order = PlayGeometry.orderByLeft(bf, group);
        int n = group.size();
        int wing = Math.max(1, n / 3);
        Assignment[] out = new Assignment[n];
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (k < wing) {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                        null, Assignment.FlankSide.RIGHT, rx, rz);
            } else if (k >= n - wing) {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                        null, Assignment.FlankSide.LEFT, lx, lz);
            } else {
                out[i] = new Assignment(group.memberIds[i], Assignment.Role.BASE_OF_FIRE,
                        null, null, bof[0], bof[1]);
            }
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        if (!bf.populated || group.size() < MIN_MEMBERS) return false;
        // A flank closing or a wing broken (too few left on a maneuver side) aborts.
        if (!bf.openFlankLeft || !bf.openFlankRight) return false;
        if (roles != null && roles.size() > 0) {
            int leftWing = 0;
            int rightWing = 0;
            for (Assignment a : roles.assignments) {
                if (a.role != Assignment.Role.MANEUVER || a.flankSide == null) continue;
                // Still in the live group?
                boolean alive = false;
                for (int id : group.memberIds) {
                    if (id == a.unitId) {
                        alive = true;
                        break;
                    }
                }
                if (!alive) continue;
                if (a.flankSide == Assignment.FlankSide.LEFT) leftWing++;
                else rightWing++;
            }
            if (leftWing < 1 || rightWing < 1) return false;
        }
        return bf.forceBalance >= 0.85;
    }
}
