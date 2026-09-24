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
        manualDrawOrder();
        advanceLayers();
        advanceParenting();
        advanceArrowsAndBake();
        advanceClaimRule();
        advanceStepBranches();
        advanceStepComposite();
        advanceContactScope();
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

    /** Manual mode: the drag path IS the fill order, fed to the same assign() the auto path uses. */
    private static void manualDrawOrder() {
        List<Long> along = FrontlineMath.chunksAlong(8, 8, 56, 8);
        assert along.equals(List.of(FrontlineMath.pack(0, 0), FrontlineMath.pack(1, 0), FrontlineMath.pack(2, 0), FrontlineMath.pack(3, 0)))
                : "a straight segment crosses each chunk once, in order, got " + along;
        assert FrontlineMath.chunksAlong(8, 8, 8, 8).equals(List.of(FrontlineMath.pack(0, 0))) : "a point is its own chunk";
        assert FrontlineMath.chunksAlong(56, 8, 8, 8).get(0) == FrontlineMath.pack(3, 0) : "direction is start to end";

        Set<Long> front = strip(); // chunks x=0..4, z=0
        List<Long> visited = List.of(FrontlineMath.pack(4, 0), FrontlineMath.pack(9, 9), FrontlineMath.pack(3, 0),
                FrontlineMath.pack(4, 0), FrontlineMath.pack(0, 0));
        List<Chunk> drawn = FrontlineMath.drawnOrder(front, visited);
        assert drawn.equals(List.of(new Chunk(4, 0), new Chunk(3, 0), new Chunk(0, 0)))
                : "non-front dropped, duplicates collapsed, first visit wins, gaps allowed; got " + drawn;
        assert FrontlineMath.drawnOrder(front, List.of(FrontlineMath.pack(9, 9))).isEmpty() : "an empty drag captures nothing";

        // Drag runs east to west across the whole strip; two units take the FIRST and LAST drawn chunk (x=4 and x=0).
        List<Chunk> line = List.of(new Chunk(4, 0), new Chunk(3, 0), new Chunk(2, 0), new Chunk(1, 0), new Chunk(0, 0));
        Unit east = new Unit(1, 4 * 16 + 8, 8, true);
        Unit west = new Unit(2, 8, 8, true);
        int eastGot = -1, westGot = -1;
        for (Assignment a : FrontlineMath.assign(line, List.of(west, east), line.get(0), Map.of())) {
            if (a.unitId() == 1) eastGot = a.chunk().x();
            if (a.unitId() == 2) westGot = a.chunk().x();
        }
        assert eastGot == 4 && westGot == 0 : "spread follows drawn order, anchored on the first chunk; got " + eastGot + "," + westGot;
    }

    // ---- Advance Plan geometry (AdvanceMath) ----------------------------------------------------------------

    private static Set<Long> keys(int... xz) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < xz.length; i += 2) out.add(FrontlineMath.pack(xz[i], xz[i + 1]));
        return out;
    }

    private static Set<Long> rect(int x0, int z0, int x1, int z1) {
        Set<Long> out = new HashSet<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) out.add(FrontlineMath.pack(x, z));
        return out;
    }

    /** An L of claims: the row z=0 (x 0..5) plus the column x=0 (z 1..5). Every chunk is on the front. */
    private static Set<Long> lClaims() {
        Set<Long> l = rect(0, 0, 5, 0);
        l.addAll(rect(0, 1, 0, 5));
        return l;
    }

    private static void advanceLayers() {
        Set<Long> claims = rect(0, 0, 9, 0); // a straight front

        // Max 5 dilations is NOT a layer count: a 1x5 region beside a straight front is a single layer.
        Set<Long> strip = rect(2, 1, 6, 1);
        AdvanceMath.Layers one = AdvanceMath.layers(claims, strip, AdvanceMath.MAX_DILATIONS);
        assert one.layers().size() == 1 && one.layers().get(0).equals(strip) : "a 1x5 region is 1 layer, got " + one.layers().size();
        assert one.unreachable().isEmpty() : "nothing unreachable";

        // Layers equal the ring formula (dilate4 minus the previous ring, intersected with the region).
        Set<Long> deep = rect(2, 1, 3, 3);
        AdvanceMath.Layers three = AdvanceMath.layers(claims, deep, AdvanceMath.MAX_DILATIONS);
        assert three.layers().size() == 3 : "3 deep = 3 layers, got " + three.layers().size();
        for (int k = 1; k <= 3; k++) {
            Set<Long> expect = AdvanceMath.ring(claims, k);
            expect.retainAll(deep);
            assert three.layers().get(k - 1).equals(expect) : "layer " + k + " must equal ring(" + k + ") ∩ region";
        }

        // An L-shaped front needs no orientation maths: the rings wrap the inner corner by construction.
        AdvanceMath.Layers l = AdvanceMath.layers(lClaims(), rect(1, 1, 3, 3), AdvanceMath.MAX_DILATIONS);
        assert l.layers().size() == 3 : "inner-corner 3x3 = 3 layers, got " + l.layers().size();
        assert l.layers().get(0).equals(keys(1, 1, 2, 1, 3, 1, 1, 2, 1, 3)) : "layer 1 hugs both arms: " + l.layers().get(0);
        assert l.layers().get(1).equals(keys(2, 2, 3, 2, 2, 3)) : "layer 2: " + l.layers().get(1);
        assert l.layers().get(2).equals(keys(3, 3)) : "layer 3 is the far corner";

        // Empty rings are dropped: distances 1 and 3 with none at 2 is two layers, in order.
        AdvanceMath.Layers gap = AdvanceMath.layers(claims, keys(2, 1, 2, 3), AdvanceMath.MAX_DILATIONS);
        assert gap.layers().size() == 2 : "compacted, not padded to distance";
        assert gap.layerOf().get(FrontlineMath.pack(2, 1)) == 0 && gap.layerOf().get(FrontlineMath.pack(2, 3)) == 1 : "layer indices are compact";

        // Beyond the dilation limit is unreachable, not silently a layer; an already-claimed chunk is in no layer.
        AdvanceMath.Layers far = AdvanceMath.layers(keys(0, 0), keys(1, 0, 6, 0), AdvanceMath.MAX_DILATIONS);
        assert far.unreachable().equals(keys(6, 0)) && far.layers().size() == 1 : "distance 6 is unreachable at 5 dilations";
        assert far.dropped().isEmpty() : "an unclaimed chunk is unreachable, never dropped";
        AdvanceMath.Layers owned = AdvanceMath.layers(claims, keys(2, 0, 2, 1), AdvanceMath.MAX_DILATIONS);
        assert owned.layers().size() == 1 && owned.layers().get(0).equals(keys(2, 1)) : "a claimed region chunk belongs to no layer";
        assert owned.dropped().equals(keys(2, 0)) : "...and is reported as dropped, not silently lost: " + owned.dropped();

        // Every region chunk lands in exactly one of layers / unreachable / dropped.
        Set<Long> mixed = keys(1, 0, 2, 1, 6, 0);
        AdvanceMath.Layers all = AdvanceMath.layers(keys(0, 0, 1, 0), mixed, AdvanceMath.MAX_DILATIONS);
        int placed = all.layerOf().size() + all.unreachable().size() + all.dropped().size();
        assert placed == mixed.size() : "layer + unreachable + dropped partition the region, got " + placed + " of " + mixed.size();
        assert one.dropped().isEmpty() && three.dropped().isEmpty() && l.dropped().isEmpty() : "nothing dropped when nothing is claimed";

        // Connectivity: one blob, two blobs, nothing.
        assert AdvanceMath.isSingleBlob(rect(0, 0, 2, 2)) : "a filled square is one blob";
        assert !AdvanceMath.isSingleBlob(keys(0, 0, 2, 0)) : "two separate chunks are two blobs";
        assert !AdvanceMath.isSingleBlob(keys(0, 0, 1, 1)) : "diagonal only is not 4-connected";
        assert !AdvanceMath.isSingleBlob(Set.of()) : "empty is not a blob";
    }

    private static void advanceParenting() {
        Set<Long> claims = lClaims();
        Set<Long> front = AdvanceMath.frontKeys(claims);
        Set<Long> inner = keys(1, 1);

        assert AdvanceMath.contactChunks(front, inner).equals(keys(1, 0, 0, 1)) : "region (1,1) touches both arms";
        // The two contact chunks are only DIAGONAL neighbours of each other, yet share one front component through the
        // corner chunk. Judging connectivity among the contact chunks would call this split; on the front it is healthy.
        assert AdvanceMath.parentState(front, inner) == AdvanceMath.Parent.HEALTHY : "L corner must not read as fragmented";

        Set<Long> split = new HashSet<>(claims);
        split.remove(FrontlineMath.pack(0, 0));
        assert AdvanceMath.parentState(AdvanceMath.frontKeys(split), inner) == AdvanceMath.Parent.FRAGMENTED
                : "with the corner gone the two arms are separate components";
        assert AdvanceMath.parentState(front, keys(9, 9)) == AdvanceMath.Parent.GONE : "no front touches a distant remainder";

        // Hysteresis: only three consecutive bad passes clear a plan; a healthy pass resets the streak.
        boolean[] bad = {true, true, false, true, true, true};
        int[] streak = {1, 2, 0, 1, 2, 3};
        int s = 0;
        for (int i = 0; i < bad.length; i++) {
            s = AdvanceMath.nextBadStreak(s, bad[i]);
            assert s == streak[i] : "streak at " + i + " was " + s;
            assert AdvanceMath.shouldClear(s) == (i == 5) : "clears only on the third consecutive bad pass (i=" + i + ")";
        }
    }

    private static void advanceArrowsAndBake() {
        // A straight front and a strip beside it: ONE arrow, perpendicular to the edge, at the centroid of the contact.
        Set<Long> row = AdvanceMath.frontKeys(rect(0, 0, 4, 0));
        List<AdvanceMath.Arrow> straight = AdvanceMath.arrows(row, rect(0, 1, 4, 1));
        assert straight.size() == 1 : "straight edge = one arrow, got " + straight.size();
        assert straight.get(0).dx() == 0 && straight.get(0).dz() == 1 : "points from the front toward the region";
        assert straight.get(0).blockX() == 40 && straight.get(0).blockZ() == 8 : "centroid of the five chunk centres: " + straight.get(0);

        // An L-shaped contact has two edge normals, so two arrows, sorted by (dx, dz).
        List<AdvanceMath.Arrow> corner = AdvanceMath.arrows(AdvanceMath.frontKeys(lClaims()), keys(1, 1));
        assert corner.size() == 2 : "an L contact is two arrows, got " + corner.size();
        assert corner.get(0).dx() == 0 && corner.get(0).dz() == 1 && corner.get(1).dx() == 1 && corner.get(1).dz() == 0 : "normals " + corner;

        double[] c = AdvanceMath.centroid(keys(0, 0, 2, 0));
        assert c[0] == 24.0 && c[1] == 8.0 : "centroid of chunks (0,0) and (2,0)";

        // Bake ownership rule: unclaimed and ENEMY pass; SELF, ALLY and any non-enemy foreign claim refuse and are named.
        java.util.Map<Long, AdvanceMath.Owner> owners = new java.util.HashMap<>();
        owners.put(FrontlineMath.pack(3, 3), AdvanceMath.Owner.ENEMY);
        owners.put(FrontlineMath.pack(1, 1), AdvanceMath.Owner.UNCLAIMED);
        AdvanceMath.Verdict fine = AdvanceMath.classify(owners);
        assert fine.ok() && fine.enemy() == 1 : "unclaimed + enemy is allowed (enemy chunks are conquered)";

        owners.put(FrontlineMath.pack(9, 9), AdvanceMath.Owner.SELF);
        owners.put(FrontlineMath.pack(5, 5), AdvanceMath.Owner.SELF);
        owners.put(FrontlineMath.pack(2, 2), AdvanceMath.Owner.SELF);
        owners.put(FrontlineMath.pack(1, 9), AdvanceMath.Owner.SELF);
        owners.put(FrontlineMath.pack(4, 4), AdvanceMath.Owner.ALLY);
        owners.put(FrontlineMath.pack(6, 6), AdvanceMath.Owner.OTHER);
        AdvanceMath.Verdict refused = AdvanceMath.classify(owners);
        assert !refused.ok() && refused.self() == 4 && refused.ally() == 1 && refused.other() == 1 : "counts per class";
        assert refused.selfSamples().equals(List.of(FrontlineMath.pack(1, 9), FrontlineMath.pack(2, 2), FrontlineMath.pack(5, 5)))
                : "first three offending chunks in (x, z) order: " + refused.selfSamples();
        owners.clear();
        owners.put(FrontlineMath.pack(0, 0), AdvanceMath.Owner.OTHER);
        assert !AdvanceMath.classify(owners).ok() : "a foreign claim that is not diplomatic ENEMY refuses";
    }

    /** Claiming a layer is all-or-nothing on headroom: free AND foreign chunks consume it, chunks already ours do not. */
    private static void advanceClaimRule() {
        java.util.Map<Long, AdvanceMath.ChunkState> layer = new java.util.HashMap<>();
        layer.put(FrontlineMath.pack(0, 0), AdvanceMath.ChunkState.FREE);
        layer.put(FrontlineMath.pack(1, 0), AdvanceMath.ChunkState.FREE);
        layer.put(FrontlineMath.pack(2, 0), AdvanceMath.ChunkState.FOREIGN);
        layer.put(FrontlineMath.pack(3, 0), AdvanceMath.ChunkState.FOREIGN);
        layer.put(FrontlineMath.pack(4, 0), AdvanceMath.ChunkState.OURS);
        AdvanceMath.ClaimPlan plan = AdvanceMath.planClaim(layer);
        assert plan.free() == 2 && plan.foreign() == 2 && plan.ours() == 1 : "classified " + plan;
        assert plan.toClaim() == 4 : "an OURS chunk consumes no headroom, a FOREIGN one does";
        assert plan.fits(4) && plan.fits(10) : "fits when headroom covers the whole layer";
        assert !plan.fits(3) : "headroom < layer size holds the ENTIRE layer, no partial claim";
        assert !plan.fits(0) && !plan.fits(-2) : "at or over the limit never fits";
        assert AdvanceMath.planClaim(java.util.Map.of()).fits(0) : "an empty layer always fits";
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

    /** Each branch of the per-pass decision, in isolation. */
    private static void advanceStepBranches() {
        int none = AdvanceMath.NOT_DISPATCHED;
        assert none != 0 : "the sentinel must not be the first layer's index";
        assert AdvanceMath.nextStep(none, 0, true, 3, 3) == AdvanceMath.Step.DISPATCH : "first pass after Start dispatches";
        assert AdvanceMath.nextStep(0, 1, true, 3, 3) == AdvanceMath.Step.DISPATCH : "a new layer dispatches";
        assert AdvanceMath.nextStep(0, 0, false, 3, 3) == AdvanceMath.Step.WAIT_SOAK : "soak is a minimum";
        assert AdvanceMath.nextStep(0, 0, true, 0, 0) == AdvanceMath.Step.REDISPATCH : "nobody on the edge never proceeds";
        assert AdvanceMath.nextStep(0, 0, true, 3, 2) == AdvanceMath.Step.ARRIVING : "one straggler blocks";
        assert AdvanceMath.nextStep(0, 0, true, 3, 3) == AdvanceMath.Step.PROCEED : "all arrived proceeds";
    }

    /** The happy path over passes: dispatch, soak unmet, arriving, proceed, then the next layer dispatches. */
    private static void advanceStepComposite() {
        int dispatched = AdvanceMath.NOT_DISPATCHED;
        int layer = 0;

        // pass 1: nothing sent yet
        assert AdvanceMath.nextStep(dispatched, layer, false, 0, 0) == AdvanceMath.Step.DISPATCH;
        dispatched = layer; // the manager records the dispatch and starts the soak

        // pass 2: units posted on the edge, all arrived already, but the minimum soak has not elapsed
        assert AdvanceMath.nextStep(dispatched, layer, false, 2, 2) == AdvanceMath.Step.WAIT_SOAK;

        // pass 3: soak done, one of two still walking
        assert AdvanceMath.nextStep(dispatched, layer, true, 2, 1) == AdvanceMath.Step.ARRIVING;

        // pass 4: both there
        assert AdvanceMath.nextStep(dispatched, layer, true, 2, 2) == AdvanceMath.Step.PROCEED;

        // the claim advances the layer; the very next decision is a dispatch, not a second claim
        layer++;
        assert AdvanceMath.nextStep(dispatched, layer, true, 2, 2) == AdvanceMath.Step.DISPATCH;
    }

    /**
     * A 40-chunk front with a 6-chunk plan: the contact edge is only the chunks touching the plan, and assigning over
     * it posts only the units passed in, only onto those chunks.
     */
    private static void advanceContactScope() {
        Set<Long> claims = new HashSet<>();
        for (int x = 0; x < 40; x++) claims.add(FrontlineMath.pack(x, 0)); // a 40-chunk strip: all front
        Set<Long> front = AdvanceMath.frontKeys(claims);
        assert front.size() == 40;
        Set<Long> remainder = new HashSet<>();
        for (int x = 10; x < 16; x++) remainder.add(FrontlineMath.pack(x, 1)); // the plan: six chunks north of it
        Set<Long> contact = AdvanceMath.contactChunks(front, remainder);
        assert contact.size() == 6 : "contact is the six touching chunks, not the 40-chunk front: " + contact.size();

        List<Chunk> ordered = new ArrayList<>();
        for (long key : contact) ordered.add(FrontlineMath.unpack(key));
        FrontlineMath.sortByDistance(ordered, new Chunk(12, 1));
        List<Unit> units = List.of(new Unit(1, 200, 8, true), new Unit(2, 230, 8, true));
        List<Assignment> out = FrontlineMath.assign(ordered, units, new Chunk(12, 1), Map.of());
        assert out.size() == 2 : "only the passed units are assigned";
        for (Assignment a : out) {
            assert contact.contains(FrontlineMath.pack(a.chunk().x(), a.chunk().z())) : "posted off the contact edge: " + a;
            assert a.unitId() == 1 || a.unitId() == 2;
        }
    }
}
