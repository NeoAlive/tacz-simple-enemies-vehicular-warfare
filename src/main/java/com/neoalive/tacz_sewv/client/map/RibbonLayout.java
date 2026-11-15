package com.neoalive.tacz_sewv.client.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which units the map ribbon shows, in what order, and where its group brackets go. Pure, so
 * {@code selfCheckRibbon} pins it headless.
 *
 * <p>Groups are not exclusive: a unit can be in a platoon, a formation and a shared order at once.
 * They are nested by precedence in three lanes, platoon outermost, and units are sorted by
 * (platoon, formation, order) so every inner group is contiguous inside its outer one. A group that
 * crosses an outer boundary (a formation drawn from two platoons) is split into segments with open
 * ends that share one index, so it still reads as one group.
 */
public final class RibbonLayout {

    public enum Lane { PLATOON, FORMATION, ORDER }

    /** {@code order} is the order batch only while the unit stands under a real order, 0 otherwise. */
    public record Unit(int vehicleId, int driverId, int platoonId, long formation, long order, int kind,
                       boolean selected, boolean inView) {

        public boolean attributeless() {
            return this.platoonId == 0 && this.formation == 0L && this.order == 0L;
        }

        long key(Lane lane) {
            return switch (lane) {
                case PLATOON -> this.platoonId;
                case FORMATION -> this.formation;
                case ORDER -> this.order;
            };
        }
    }

    /** Units {@code first..last} (inclusive) of one group; {@code index} numbers the group in its lane from 1. */
    public record Span(Lane lane, int first, int last, long key, int index, boolean openLeft, boolean openRight) {}

    /** {@code idleStart} is the index of the first attributeless unit (== size when there are none). */
    public record Result(List<Unit> units, List<Span> spans, int idleStart) {}

    private static final Comparator<Unit> ORDER = Comparator
            .comparing(Unit::attributeless)
            .thenComparing(u -> u.platoonId() == 0)
            .thenComparingInt(Unit::platoonId)
            .thenComparing(u -> u.formation() == 0L)
            .thenComparingLong(Unit::formation)
            .thenComparing(u -> u.order() == 0L)
            .thenComparingLong(Unit::order)
            .thenComparingInt(Unit::kind)
            .thenComparingInt(Unit::vehicleId);

    private RibbonLayout() {}

    public static Result build(List<Unit> all) {
        List<Unit> seeds = new ArrayList<>();
        for (Unit u : all) {
            if (u.inView() || u.selected()) seeds.add(u);
        }
        Map<Lane, Set<Long>> wanted = new HashMap<>();
        for (Lane lane : Lane.values()) wanted.put(lane, new HashSet<>());
        for (Unit u : seeds) {
            for (Lane lane : Lane.values()) {
                long k = u.key(lane);
                if (k != 0L) wanted.get(lane).add(k);
            }
        }
        List<Unit> shown = new ArrayList<>();
        for (Unit u : all) {
            boolean keep = u.inView() || u.selected();
            for (Lane lane : Lane.values()) {
                long k = u.key(lane);
                if (k != 0L && wanted.get(lane).contains(k)) keep = true;
            }
            if (keep) shown.add(u);
        }
        shown.sort(ORDER);

        int idleStart = shown.size();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).attributeless()) {
                idleStart = i;
                break;
            }
        }

        List<Span> spans = new ArrayList<>();
        for (Lane lane : Lane.values()) spans.addAll(lane(shown, idleStart, lane));
        return new Result(List.copyOf(shown), List.copyOf(spans), idleStart);
    }

    private static List<Span> lane(List<Unit> units, int end, Lane lane) {
        Map<Long, Integer> count = new HashMap<>();
        for (int i = 0; i < end; i++) {
            long k = units.get(i).key(lane);
            if (k != 0L) count.merge(k, 1, Integer::sum);
        }
        record Run(long key, int first, int last) {}
        List<Run> runs = new ArrayList<>();
        int i = 0;
        while (i < end) {
            long k = units.get(i).key(lane);
            int j = i;
            while (j + 1 < end && units.get(j + 1).key(lane) == k && sameOuter(units.get(j), units.get(j + 1), lane)) {
                j++;
            }
            if (k != 0L && count.getOrDefault(k, 0) > 1) runs.add(new Run(k, i, j));
            i = j + 1;
        }
        Map<Long, Integer> index = new HashMap<>();
        Map<Long, Integer> firstRun = new HashMap<>();
        Map<Long, Integer> lastRun = new HashMap<>();
        for (int r = 0; r < runs.size(); r++) {
            long k = runs.get(r).key();
            index.putIfAbsent(k, index.size() + 1);
            firstRun.putIfAbsent(k, r);
            lastRun.put(k, r);
        }
        List<Span> out = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            Run run = runs.get(r);
            out.add(new Span(lane, run.first(), run.last(), run.key(), index.get(run.key()),
                    firstRun.get(run.key()) != r, lastRun.get(run.key()) != r));
        }
        return out;
    }

    /** An inner lane's run stops where an outer group changes, even when its own key carries on. */
    private static boolean sameOuter(Unit a, Unit b, Lane lane) {
        for (Lane outer : Lane.values()) {
            if (outer == lane) return true;
            if (a.key(outer) != b.key(outer)) return false;
        }
        return true;
    }

    /** All units of the group a span belongs to, across every segment. */
    public static List<Unit> members(Result r, Span s) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : r.units()) {
            if (u.key(s.lane()) == s.key()) out.add(u);
        }
        return out;
    }
}
