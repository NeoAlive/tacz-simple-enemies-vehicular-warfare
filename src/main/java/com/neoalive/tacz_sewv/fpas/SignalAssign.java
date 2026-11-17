package com.neoalive.tacz_sewv.fpas;

import java.util.ArrayList;
import java.util.List;

/**
 * Who shoots what. Rounds of nearest pairing: each round every ally takes its nearest enemy nobody
 * has claimed yet, the ally with the fewest options left picking first and the closest pair
 * breaking ties, until no ally can take another. Allies left with
 * nothing once the enemies run out double up on their nearest one. So two allies against two enemies
 * split them, one ally against two takes both in order, three against one all take the one.
 * {@code able[a][e]} false keeps that pair apart entirely (a rifleman and a tank). Deterministic:
 * ties break on index.
 */
public final class SignalAssign {

    private SignalAssign() {}

    public static List<List<Integer>> assign(double[][] allies, double[][] enemies) {
        boolean[][] able = new boolean[allies.length][enemies.length];
        for (boolean[] row : able) java.util.Arrays.fill(row, true);
        return assign(allies, enemies, able);
    }

    /** @return per ally, enemy indices in the order they should be engaged (empty if it can take none) */
    public static List<List<Integer>> assign(double[][] allies, double[][] enemies, boolean[][] able) {
        int na = allies.length, ne = enemies.length;
        List<List<Integer>> out = new ArrayList<>(na);
        for (int i = 0; i < na; i++) out.add(new ArrayList<>());
        if (na == 0 || ne == 0) return out;
        boolean[] taken = new boolean[ne];
        boolean progress = true;
        while (progress) {
            progress = false;
            boolean[] moved = new boolean[na];
            while (true) {
                int bestA = -1, bestE = -1, bestOptions = Integer.MAX_VALUE;
                double best = Double.MAX_VALUE;
                for (int a = 0; a < na; a++) {
                    if (moved[a]) continue;
                    // The ally with the fewest targets left picks first, so a choosier unit is not starved.
                    int options = 0;
                    for (int e = 0; e < ne; e++) if (!taken[e] && able[a][e]) options++;
                    if (options == 0 || options > bestOptions) continue;
                    if (options < bestOptions) {
                        bestOptions = options;
                        best = Double.MAX_VALUE;
                    }
                    for (int e = 0; e < ne; e++) {
                        if (taken[e] || !able[a][e]) continue;
                        double d = d2(allies[a], enemies[e]);
                        if (d < best) {
                            best = d;
                            bestA = a;
                            bestE = e;
                        }
                    }
                }
                if (bestA < 0) break;
                moved[bestA] = true;
                taken[bestE] = true;
                out.get(bestA).add(bestE);
                progress = true;
            }
        }
        for (int a = 0; a < na; a++) {
            if (!out.get(a).isEmpty()) continue;
            int bestE = -1;
            double best = Double.MAX_VALUE;
            for (int e = 0; e < ne; e++) {
                if (!able[a][e]) continue;
                double d = d2(allies[a], enemies[e]);
                if (d < best) {
                    best = d;
                    bestE = e;
                }
            }
            if (bestE >= 0) out.get(a).add(bestE);
        }
        return out;
    }

    /** Fraction of the range a unit stops at, so it is just inside it on arrival. */
    public static final double STANDOFF_FRACTION = 0.85;

    /**
     * Where a unit at (ux, uz) stops for a contact at (tx, tz): on its own side, short by the range.
     * Already in range, it stays where it is.
     */
    public static double[] standoff(double ux, double uz, double tx, double tz, double range) {
        double dx = ux - tx, dz = uz - tz;
        double d = Math.hypot(dx, dz);
        double r = range * STANDOFF_FRACTION;
        if (d <= range) return new double[] {ux, uz};
        return new double[] {tx + dx / d * r, tz + dz / d * r};
    }

    static double d2(double[] p, double[] q) {
        double dx = p[0] - q[0], dz = p[1] - q[1];
        return dx * dx + dz * dz;
    }
}
