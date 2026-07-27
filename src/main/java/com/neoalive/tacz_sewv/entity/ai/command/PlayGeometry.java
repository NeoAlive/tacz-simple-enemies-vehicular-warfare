package com.neoalive.tacz_sewv.entity.ai.command;

import com.neoalive.tacz_sewv.entity.ai.utility.UtilityWeights;

import java.util.Arrays;

/**
 * Shared geometry helpers for role assignment — sort members by lateral projection on the
 * enemy→us left axis.
 */
final class PlayGeometry {

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

    /** Hold / BoF point: friendly centroid stepped a bit toward the enemy along the axis. */
    static double[] bofPoint(BattleField bf) {
        return new double[]{
                bf.friendlyCentroidX + bf.axisX * 8.0,
                bf.friendlyCentroidZ + bf.axisZ * 8.0
        };
    }

    /** Withdraw point: back along the axis away from the enemy. */
    static double[] withdrawPoint(BattleField bf) {
        return new double[]{
                bf.friendlyCentroidX - bf.axisX * 24.0,
                bf.friendlyCentroidZ - bf.axisZ * 24.0
        };
    }

    static double scoreOf(PlayId id, BattleField bf, UtilityWeights weights) {
        return PlaySignals.score(id, bf, weights);
    }
}
