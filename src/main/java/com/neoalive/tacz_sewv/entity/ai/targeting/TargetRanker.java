package com.neoalive.tacz_sewv.entity.ai.targeting;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders candidates by the priority stack. Pure, so the self-check can pin it.
 *
 * <p>Each candidate gets a precomputed key: hurtable first, then distance times a rank weight that
 * runs linearly from 1 (top entry) to {@code maxFactor} (unlisted), then the focus target, then
 * the entity id. A key, never a pairwise rule: "B wins if much closer" is not transitive, and a
 * sort over a non-transitive comparator can throw.
 */
public final class TargetRanker {

    /** A re-rank only moves when the best is under this fraction of the current target's distance. */
    public static final double SWITCH_FRACTION = 0.8;

    /** One candidate. {@code ref} is the caller's object, handed back in order. */
    public static final class Candidate<T> {
        public final T ref;
        final int id;
        final double dist;
        final int rank;
        final boolean hurtable;
        final boolean focus;
        double eff;

        public Candidate(T ref, int id, double dist, int rank, boolean hurtable, boolean focus) {
            this.ref = ref;
            this.id = id;
            this.dist = dist;
            this.rank = rank;
            this.hurtable = hurtable;
            this.focus = focus;
        }

        public double eff() {
            return eff;
        }

        public boolean hurtable() {
            return hurtable;
        }
    }

    private static final Comparator<Candidate<?>> ORDER = Comparator
            .<Candidate<?>, Boolean>comparing(c -> !c.hurtable)
            .thenComparingDouble(c -> c.eff)
            .thenComparing(c -> !c.focus)
            .thenComparingInt(c -> c.id);

    private TargetRanker() {}

    /** Rank weight: 1 at the top entry, {@code maxFactor} for unlisted ({@code rank == size}). */
    public static double weight(int rank, int size, double maxFactor) {
        double f = Math.max(1.0, maxFactor);
        if (size <= 0) return 1.0;
        int r = Math.max(0, Math.min(rank, size));
        return 1.0 + (f - 1.0) * r / size;
    }

    /** Fills every key and sorts {@code list} best first. */
    public static <T> void sort(List<Candidate<T>> list, int size, double maxFactor) {
        Map<Integer, Double> bestOfRank = new HashMap<>();
        for (Candidate<T> c : list) {
            c.eff = c.dist * weight(c.rank, size, maxFactor);
            if (!c.focus) bestOfRank.merge(c.rank, c.eff, Math::min);
        }
        // Focus fire: the focus target takes the best distance of its own entry, never another's.
        for (Candidate<T> c : list) {
            if (!c.focus) continue;
            Double best = bestOfRank.get(c.rank);
            if (best != null && best < c.eff) c.eff = best;
        }
        list.sort(ORDER);
    }

    /** Whether a re-rank moves from {@code current} to {@code best}. Both must come from one sort. */
    public static boolean shouldSwitch(Candidate<?> best, Candidate<?> current) {
        if (best == current) return false;
        if (best.hurtable != current.hurtable) return best.hurtable;
        return best.eff < current.eff * SWITCH_FRACTION;
    }
}
