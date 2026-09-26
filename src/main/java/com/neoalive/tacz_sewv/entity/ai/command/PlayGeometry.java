package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.Arrays;

import com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale;
import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

/**
 * Shared geometry helpers for role assignment — sort members by lateral projection on the
 * enemy→us left axis.
 *
 * <p>{@link BattleField#axisX}/{@code axisZ} point from the enemy centroid TO ours, so "toward the enemy" is
 * {@code -axis}. The first version stepped along {@code +axis} and put every withdraw point in front of the group
 * and every support point behind it (confirmed by the playtest log's per-point distances). Distances scale with
 * {@link TacticalScale}.
 */
public final class PlayGeometry {

    static final double BOF_STEP = 8.0;
    static final double WITHDRAW_STEP = 24.0;
    static final double ADVANCE_STEP = 20.0;
    /** Spacing of support tanks laid on a firing line across the axis. */
    static final double LINE_SPACING = 16.0;

    private PlayGeometry() {}

    /** Indices into the group sorted by projection onto left = (-axisZ, axisX), ascending. */
    static int[] orderByLeft(BattleField bf, GroupSnapshot group) {
        int n = group.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        double leftX = -bf.axisZ;
        double leftZ = bf.axisX;
        double ox = bf.friendlyCentroidX;
        double oz = bf.friendlyCentroidZ;
        Arrays.sort(idx, (a, b) -> {
            double sa = (group.x[a] - ox) * leftX + (group.z[a] - oz) * leftZ;
            double sb = (group.x[b] - ox) * leftX + (group.z[b] - oz) * leftZ;
            int c = Double.compare(sa, sb);
            return c != 0 ? c : Integer.compare(group.memberIds[a], group.memberIds[b]);
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        return out;
    }

    /** Hold / BoF point: friendly centroid stepped a bit toward the enemy. */
    static double[] bofPoint(BattleField bf) {
        return towardEnemy(bf, TacticalScale.of(BOF_STEP));
    }

    /** Withdraw point: back away from the enemy. */
    static double[] withdrawPoint(BattleField bf) {
        return towardEnemy(bf, -TacticalScale.of(WITHDRAW_STEP));
    }

    /** Bounding advance point: one bound toward the enemy. */
    static double[] advancePoint(BattleField bf) {
        return towardEnemy(bf, TacticalScale.of(ADVANCE_STEP));
    }

    private static double[] towardEnemy(BattleField bf, double distance) {
        return new double[] {
                bf.friendlyCentroidX - bf.axisX * distance,
                bf.friendlyCentroidZ - bf.axisZ * distance
        };
    }

    static double scoreOf(PlayId id, BattleField bf, UtilityWeights weights) {
        return PlaySignals.score(id, bf, weights);
    }
}
