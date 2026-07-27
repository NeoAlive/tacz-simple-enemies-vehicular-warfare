package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Base-of-fire pins the pocket; a maneuver element takes one enemy-side open flank.
 */
public final class FrontalFixAndFlank implements Play {

    static final FrontalFixAndFlank INSTANCE = new FrontalFixAndFlank();

    private FrontalFixAndFlank() {}

    @Override
    public PlayId id() {
        return PlayId.FRONTAL_FIX_AND_FLANK;
    }

    @Override
    public boolean feasible(BattleField bf, GroupSnapshot group) {
        if (!bf.populated || group.size() < 2) return false;
        if (bf.pocketCount < 1) return false;
        if (!bf.openFlankLeft && !bf.openFlankRight) return false;
        return bf.forceBalance >= 0.55;
    }

    @Override
    public double score(BattleField bf, GroupSnapshot group, UtilityWeights weights) {
        return PlayGeometry.scoreOf(id(), bf, weights);
    }

    @Override
    public Roles assignRoles(BattleField bf, GroupSnapshot group) {
        // Prefer left if open, else right — deterministic.
        boolean useLeft = bf.openFlankLeft;
        int side = useLeft ? +1 : -1;
        Assignment.FlankSide flank = useLeft ? Assignment.FlankSide.LEFT : Assignment.FlankSide.RIGHT;
        double mx = PlaySignals.flankMarkX(bf, side);
        double mz = PlaySignals.flankMarkZ(bf, side);
        double[] bof = PlayGeometry.bofPoint(bf);

        int[] order = PlayGeometry.orderByLeft(bf, group);
        int n = group.size();
        // Far-side half (toward the open flank) maneuvers; the rest holds BoF.
        int maneuverN = Math.max(1, n / 2);
        Assignment[] out = new Assignment[n];
        if (useLeft) {
            // Highest left-projection → maneuver
            for (int k = 0; k < n; k++) {
                int i = order[n - 1 - k];
                if (k < maneuverN) {
                    out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                            null, flank, mx, mz);
                } else {
                    out[i] = new Assignment(group.memberIds[i], Assignment.Role.BASE_OF_FIRE,
                            null, null, bof[0], bof[1]);
                }
            }
        } else {
            // Lowest (right) → maneuver
            for (int k = 0; k < n; k++) {
                int i = order[k];
                if (k < maneuverN) {
                    out[i] = new Assignment(group.memberIds[i], Assignment.Role.MANEUVER,
                            null, flank, mx, mz);
                } else {
                    out[i] = new Assignment(group.memberIds[i], Assignment.Role.BASE_OF_FIRE,
                            null, null, bof[0], bof[1]);
                }
            }
        }
        return new Roles(out);
    }

    @Override
    public boolean stillValid(BattleField bf, GroupSnapshot group, Roles roles) {
        if (!bf.populated || group.size() < 2) return false;
        if (bf.pocketCount < 1) return false;
        // Chosen flank closed, or force collapsed — reverse.
        if (roles != null && roles.size() > 0) {
            Assignment.FlankSide side = null;
            for (Assignment a : roles.assignments) {
                if (a.role == Assignment.Role.MANEUVER && a.flankSide != null) {
                    side = a.flankSide;
                    break;
                }
            }
            if (side == Assignment.FlankSide.LEFT && !bf.openFlankLeft) return false;
            if (side == Assignment.FlankSide.RIGHT && !bf.openFlankRight) return false;
        } else if (!bf.openFlankLeft && !bf.openFlankRight) {
            return false;
        }
        return bf.forceBalance >= 0.45;
    }
}
