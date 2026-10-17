package com.neoalive.tacz_sewv.entity.ai.formation;

/**
 * Who stands in which slot: the assignment of members to slots that minimises total squared travel,
 * plus a penalty for leaving the slot a member held last pass.
 *
 * <p>Squared distance is what keeps paths from crossing — two members swapping slots always costs
 * more than each taking the nearer one. The stickiness penalty is hysteresis: a formation that is
 * re-laid every command pass would otherwise shuffle its members whenever the anchor drifts.
 *
 * <p>Exact (Hungarian, O(n³)) up to {@link #EXACT_LIMIT} members, greedy above it. Plays split a
 * group by role first, so a single formation is rarely more than a handful. Deterministic: the
 * result depends only on the input order, never on hashing or randomness.
 */
public final class SlotMatcher {

    /** Largest formation solved exactly. */
    public static final int EXACT_LIMIT = 16;

    private SlotMatcher() {}

    /**
     * @param prevSlot each member's slot last pass, or -1; may be null
     * @return {@code slotOf[i]}, the slot member {@code i} takes — a permutation of {@code 0..n-1}
     */
    public static int[] match(double[] mx, double[] mz, double[] sx, double[] sz,
                              int[] prevSlot, double stickPenalty) {
        int n = mx.length;
        if (mz.length != n || sx.length != n || sz.length != n) {
            throw new IllegalArgumentException("one slot per member");
        }
        double[][] cost = new double[n][n];
        for (int i = 0; i < n; i++) {
            int prev = prevSlot == null ? -1 : prevSlot[i];
            for (int j = 0; j < n; j++) {
                double dx = mx[i] - sx[j];
                double dz = mz[i] - sz[j];
                cost[i][j] = dx * dx + dz * dz + (prev >= 0 && prev != j ? stickPenalty : 0.0);
            }
        }
        return n <= EXACT_LIMIT ? hungarian(cost) : greedy(cost);
    }

    /** Total cost of an assignment — exposed for the self-check's brute-force comparison. */
    public static double total(double[][] cost, int[] slotOf) {
        double sum = 0.0;
        for (int i = 0; i < slotOf.length; i++) sum += cost[i][slotOf[i]];
        return sum;
    }

    /** Minimum-cost perfect matching on a square matrix (shortest augmenting path with potentials). */
    static int[] hungarian(double[][] cost) {
        int n = cost.length;
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1]; // p[j] = row matched to column j (1-based), 0 = free
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;
                    double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] slotOf = new int[n];
        for (int j = 1; j <= n; j++) slotOf[p[j] - 1] = j - 1;
        return slotOf;
    }

    /** Each member in input order takes its cheapest free slot. */
    static int[] greedy(double[][] cost) {
        int n = cost.length;
        int[] slotOf = new int[n];
        boolean[] taken = new boolean[n];
        for (int i = 0; i < n; i++) {
            int best = -1;
            for (int j = 0; j < n; j++) {
                if (!taken[j] && (best < 0 || cost[i][j] < cost[i][best])) best = j;
            }
            taken[best] = true;
            slotOf[i] = best;
        }
        return slotOf;
    }
}
