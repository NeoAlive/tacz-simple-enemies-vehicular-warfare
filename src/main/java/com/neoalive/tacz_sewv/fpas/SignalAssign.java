package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayList;
import java.util.List;

/**
 * Who shoots what. Rounds of nearest pairing: each round every ally takes its nearest enemy nobody
 * has claimed yet, the closest pairs picking first, until every enemy is taken. Allies left over
 * once the enemies run out double up on their nearest one. So two allies against two enemies split
 * them, one ally against two takes both in order, three against one all take the one.
 * Deterministic: ties break on index.
 */
public final class SignalAssign {

    private SignalAssign() {}

    /** @return per ally, enemy indices in the order they should be engaged (never empty if enemies exist) */
    public static List<List<Integer>> assign(double[][] allies, double[][] enemies) {
        int na = allies.length, ne = enemies.length;
        List<List<Integer>> out = new ArrayList<>(na);
        for (int i = 0; i < na; i++) out.add(new ArrayList<>());
        if (na == 0 || ne == 0) return out;
        boolean[] taken = new boolean[ne];
        int left = ne;
        while (left > 0) {
            boolean[] moved = new boolean[na];
            for (int k = 0; k < na && left > 0; k++) {
                int bestA = -1, bestE = -1;
                double best = Double.MAX_VALUE;
                for (int a = 0; a < na; a++) {
                    if (moved[a]) continue;
                    for (int e = 0; e < ne; e++) {
                        if (taken[e]) continue;
                        double d = d2(allies[a], enemies[e]);
                        if (d < best) {
                            best = d;
                            bestA = a;
                            bestE = e;
                        }
                    }
                }
                moved[bestA] = true;
                taken[bestE] = true;
                out.get(bestA).add(bestE);
                left--;
            }
        }
        for (int a = 0; a < na; a++) {
            if (!out.get(a).isEmpty()) continue;
            int bestE = 0;
            double best = Double.MAX_VALUE;
            for (int e = 0; e < ne; e++) {
                double d = d2(allies[a], enemies[e]);
                if (d < best) {
                    best = d;
                    bestE = e;
                }
            }
            out.get(a).add(bestE);
        }
        return out;
    }

    private static double d2(double[] p, double[] q) {
        double dx = p[0] - q[0], dz = p[1] - q[1];
        return dx * dx + dz * dz;
    }
}
