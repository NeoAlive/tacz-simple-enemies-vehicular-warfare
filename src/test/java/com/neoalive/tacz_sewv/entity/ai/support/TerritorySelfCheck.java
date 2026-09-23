package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Assignment;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Unit;

/** Headless check for {@link FrontlineMath}: {@code ./gradlew selfCheckTerritory}. */
public final class TerritorySelfCheck {

    private static final Chunk ORIGIN = new Chunk(0, 0);

    private TerritorySelfCheck() {}

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");

        packRoundTrips();
        frontOfBlockExcludesInterior();
        emptyFront();
        spreadTable();
        outsideUnitsSortLast();
        density();
        densityRespectsPriorCoverage();
        deterministic();
        holdBand();
        yieldNeedsArrival();
        System.out.println("TerritorySelfCheck OK");
    }

    /** A 1x5 strip of claims: every chunk is on the front, ordered x = 0..4 from the origin. */
    private static Set<Long> strip() {
        Set<Long> s = new HashSet<>();
        for (int x = 0; x < 5; x++) s.add(FrontlineMath.pack(x, 0));
        return s;
    }

    private static void packRoundTrips() {
        for (int[] p : new int[][] {{0, 0}, {-1, 5}, {7, -3}, {-100, -200}}) {
            Chunk c = FrontlineMath.unpack(FrontlineMath.pack(p[0], p[1]));
            assert c.x() == p[0] && c.z() == p[1] : "pack round-trip " + p[0] + "," + p[1];
        }
    }

    private static void frontOfBlockExcludesInterior() {
        Set<Long> s = new HashSet<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) s.add(FrontlineMath.pack(x, z));
        List<Chunk> f = FrontlineMath.front(s, ORIGIN);
        assert f.size() == 8 : "3x3 block has an 8-chunk front, got " + f.size();
        assert !f.contains(new Chunk(1, 1)) : "interior chunk is not front";
        // Diagonal-only neighbours do not count: a corner is front because its 4-neighbours are open.
        assert f.get(0).equals(new Chunk(0, 0)) : "nearest-origin first";
    }

    private static void emptyFront() {
        assert FrontlineMath.front(Set.of(), ORIGIN).isEmpty() : "no claims, no front";
        List<Chunk> f = FrontlineMath.front(strip(), ORIGIN);
        assert FrontlineMath.assign(List.of(), List.of(new Unit(1, 0, 0, true)), ORIGIN, Map.of()).isEmpty() : "empty front assigns nothing";
        assert FrontlineMath.assign(f, List.of(), ORIGIN, Map.of()).isEmpty() : "no units assigns nothing";
    }

    /** Spec 7.4 table: N units on 5 front chunks. N=4 is the row that separates round from floor. */
    private static void spreadTable() {
        int[][] expected = {
            {0},
            {0, 4},
            {0, 2, 4},
            {0, 1, 3, 4},
            {0, 1, 2, 3, 4},
        };
        List<Chunk> front = FrontlineMath.front(strip(), ORIGIN);
        assert front.size() == 5 : "strip front";
        for (int n = 1; n <= 5; n++) {
            List<Unit> units = new ArrayList<>();
            for (int i = 0; i < n; i++) units.add(new Unit(i + 1, 8, 8, true)); // all on the origin chunk
            TreeSet<Integer> got = new TreeSet<>();
            for (Assignment a : FrontlineMath.assign(front, units, ORIGIN, Map.of())) got.add(a.chunk().x());
            TreeSet<Integer> want = new TreeSet<>();
            for (int i : expected[n - 1]) want.add(i);
            assert got.equals(want) : "N=" + n + " of 5: want " + want + " got " + got;
        }
    }

    /** Off-territory units are ordered last even when they stand closest to the origin. */
    private static void outsideUnitsSortLast() {
        List<Chunk> front = FrontlineMath.front(strip(), ORIGIN);
        Unit inside = new Unit(1, 1 * 16 + 8, 8, true);   // nearest target is chunk 0
        Unit outside = new Unit(2, 8, 8, false);           // standing on chunk 0 but outside territory
        int a = 0, b = 0;
        for (Assignment as : FrontlineMath.assign(front, List.of(outside, inside), ORIGIN, Map.of())) {
            if (as.unitId() == 1) a = as.chunk().x();
            if (as.unitId() == 2) b = as.chunk().x();
        }
        assert a == 0 && b == 4 : "inside unit picks first (0), outside gets the rest (4); got " + a + "," + b;
    }

    private static void density() {
        List<Chunk> front = FrontlineMath.front(strip(), ORIGIN).subList(0, 3);
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) units.add(new Unit(i + 1, 8, 8, true));
        int[] count = new int[3];
        List<Assignment> out = FrontlineMath.assign(front, units, ORIGIN, Map.of());
        assert out.size() == 5 : "every unit assigned";
        for (Assignment a : out) count[a.chunk().x()]++;
        int min = Math.min(count[0], Math.min(count[1], count[2]));
        int max = Math.max(count[0], Math.max(count[1], count[2]));
        assert min >= 1 : "every front chunk covered before stacking";
        assert max - min <= 1 : "leftovers stack on least-covered chunks, got " + count[0] + "," + count[1] + "," + count[2];
    }

    /**
     * Re-run with chunks already posted: leftovers must stack on REAL coverage. Prior {c:1, d:2, e:0}, five
     * units standing on e: pass 1 puts one on each chunk, both leftovers then land on e, so prior + leftovers
     * (pass-1 placements excluded) is {c:1, d:2, e:2}. That case alone does not prove the fix (standing on e, the distance
     * tie-break would pick e even ignoring prior), so a second case stands the units on c: with prior {c:3, d:3, e:0} the
     * single leftover must still go to e, where ignoring prior it would stay on c.
     */
    private static void densityRespectsPriorCoverage() {
        List<Chunk> front = FrontlineMath.front(strip(), ORIGIN).subList(0, 3);
        Chunk c = front.get(0), d = front.get(1), e = front.get(2);
        Map<Chunk, Integer> prior = Map.of(c, 1, d, 2, e, 0);
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) units.add(new Unit(i + 1, e.centreX(), e.centreZ(), true));
        Map<Chunk, Integer> finalCover = new java.util.HashMap<>(prior);
        Map<Chunk, Integer> run = new java.util.HashMap<>();
        for (Assignment a : FrontlineMath.assign(front, units, ORIGIN, prior)) run.merge(a.chunk(), 1, Integer::sum);
        for (Chunk k : front) finalCover.merge(k, run.getOrDefault(k, 0) - 1, Integer::sum);
        assert run.get(e) == 3 && run.get(c) == 1 && run.get(d) == 1 : "both leftovers land on e, got " + run;
        assert finalCover.get(c) == 1 && finalCover.get(d) == 2 && finalCover.get(e) == 2 : "prior + leftovers " + finalCover;

        Map<Chunk, Integer> heavy = Map.of(c, 3, d, 3, e, 0);
        List<Unit> near = new ArrayList<>();
        for (int i = 0; i < 4; i++) near.add(new Unit(i + 1, c.centreX(), c.centreZ(), true));
        Map<Chunk, Integer> run2 = new java.util.HashMap<>();
        for (Assignment a : FrontlineMath.assign(front, near, ORIGIN, heavy)) run2.merge(a.chunk(), 1, Integer::sum);
        assert run2.get(e) == 2 : "leftover follows real coverage onto e, got " + run2;
        run2.clear();
        for (Assignment a : FrontlineMath.assign(front, near, ORIGIN, Map.of())) run2.merge(a.chunk(), 1, Integer::sum);
        assert run2.get(c) == 2 : "sanity: with no prior the same leftover stays on c, got " + run2;
    }

    /** On-foot hold band (spec 8.2): arrive within 3 blocks, pull back only past 6, 3-6 is a dead band. */
    private static void holdBand() {
        double sq = 1; // distances below are blocks, squared
        assert !TerritorySupport.nextHolding(false, 10 * 10 * sq) : "10 blocks out, never arrived: walk";
        assert TerritorySupport.nextHolding(false, 3 * 3) : "exactly 3 blocks: arrived";
        assert !TerritorySupport.nextHolding(false, 4 * 4) : "4 blocks, not yet arrived: keep walking to 3";
        assert TerritorySupport.nextHolding(true, 5 * 5) : "5 blocks after arrival: hold, no pull";
        assert TerritorySupport.nextHolding(true, 6 * 6) : "exactly 6 blocks after arrival: still hold";
        assert !TerritorySupport.nextHolding(true, 6.1 * 6.1) : "past 6 blocks with no target: the pull fires";
        assert !TerritorySupport.nextHolding(true, 10 * 10) : "10 blocks after arrival: the pull fires";
    }

    /**
     * Option B needs arrival: a unit still walking to its post must not yield to (or chase) a target, even one inside
     * the leash — the first runtime pass had units hooked on pre-existing targets 20-32 blocks from the centre.
     */
    private static void yieldNeedsArrival() {
        assert !TerritorySupport.yields(false, true, true) : "en route, target in leash: keep walking, do not chase";
        assert !TerritorySupport.yields(false, true, false) : "en route and outside the leash: no yield";
        assert TerritorySupport.yields(true, true, true) : "arrived, target in leash: pursue";
        assert !TerritorySupport.yields(true, false, true) : "arrived, target outside the leash: hold";
        assert !TerritorySupport.yields(true, true, false) : "arrived but drifted out of the leash: walk back, no chase";
    }

    private static void deterministic() {
        List<Chunk> front = FrontlineMath.front(strip(), ORIGIN);
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < 3; i++) units.add(new Unit(10 - i, 8, 8, true)); // identical positions: ids break ties
        List<Assignment> first = FrontlineMath.assign(front, units, ORIGIN, Map.of());
        List<Unit> reversed = new ArrayList<>(units);
        Collections.reverse(reversed);
        List<Assignment> second = FrontlineMath.assign(front, reversed, ORIGIN, Map.of());
        assert first.equals(second) : "input order must not change the result";
    }
}
