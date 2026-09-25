package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;

import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Entry;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Kind;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Snapshot;
import com.neoalive.tacz_sewv.entity.ai.sensor.FactionWideScan.PassStats;

/**
 * Headless check of the wide-detection logic. Run via {@code ./gradlew selfCheckWideScan}.
 * The Minecraft-facing halves (rebuild, hostility, board writes) need a world and are argued in
 * docs/fire-los-audit.md; the pair evaluation, cadence and geometry are pure and are covered here.
 */
public final class FactionWideScanSelfCheck {

    private static final double R = 96.0, W = 256.0, HALF_H = 64.0;

    public static void main(String[] args) {
        cadenceInvariant();
        deadlines();
        cylinder();
        pairsAreMutualAndEvaluateSameFaction();
        nonObserverSubjectsGetOneDirection();
        tiersAreIndependent();
        rangeAndVerticalLimits();
        orderIsIdDerived();
        snapshotQuery();
        noSnapshotIsNull();
        System.out.println("FactionWideScanSelfCheck OK");
    }

    private static Entry hull(int id, double x, double y, double z, boolean canObserve) {
        return new Entry(id, Kind.HULL, x, y, z, null, null, canObserve, 0.0);
    }

    private static Entry living(int id, Kind kind, double x, double z) {
        return new Entry(id, kind, x, 64, z, null, null, false, 0.0);
    }

    private static PassStats run(List<Entry> observers, List<Entry> subjects, boolean close, boolean wide,
                                 List<String> calls, boolean hostile) {
        PassStats st = new PassStats();
        FactionWideScan.evaluate(observers, subjects, R, W, HALF_H, close, wide,
                (o, s) -> { calls.add(o.id + ">" + s.id); return hostile; },
                (o, s) -> true, st);
        return st;
    }

    private static void cadenceInvariant() {
        // PROXIMITY must outlive at least two wide cycles: clamp = PROXIMITY ttl / 2.
        int ttl = 200;
        int proximity = ContactBoard.Source.PROXIMITY.ttl(ttl);
        assert proximity == 120 : "PROXIMITY life at the default TTL";
        assert FactionWideScan.effectiveWideCadence(100, ttl) == 60 : "clamped to ttl/2";
        assert FactionWideScan.effectiveWideCadence(40, ttl) == 40 : "the shipped default (40) must NOT be reduced";
        assert 2 * FactionWideScan.effectiveWideCadence(100, ttl) <= proximity : "two cycles always fit";
        assert FactionWideScan.effectiveWideCadence(40, 40) == 12 : "a short TTL clamps hard";
        assert FactionWideScan.effectiveWideCadence(10, 40) >= 1 : "never zero";
    }

    private static void deadlines() {
        assert FactionWideScan.due(100, Long.MIN_VALUE, 40) : "unset is due";
        assert !FactionWideScan.due(100, 120, 40) : "not yet";
        assert FactionWideScan.due(120, 120, 40) : "reached";
        assert FactionWideScan.due(10, 100_000, 40) : "a deadline from a previous world (game time rewound) is due";
    }

    private static void cylinder() {
        assert FactionWideScan.inCylinder(64, 0, 64 + 63, HALF_H);
        assert !FactionWideScan.inCylinder(64, 0, 64 + 65, HALF_H) : "too high";
        assert !FactionWideScan.inCylinder(64, 0, 64 - 65, HALF_H) : "too low without slack";
        assert FactionWideScan.inCylinder(64, 100, 64 - 150, HALF_H) : "an airborne observer looks further down";
        assert !FactionWideScan.inCylinder(64, 100, 64 + 65, HALF_H) : "slack is downward only";
    }

    private static void pairsAreMutualAndEvaluateSameFaction() {
        List<Entry> hulls = List.of(hull(10, 0, 64, 0, true), hull(20, 150, 64, 0, true));
        List<String> calls = new ArrayList<>();
        PassStats st = run(hulls, hulls, true, true, calls, true);
        assert st.pairs == 1 : "one unordered pair, one distance";
        assert calls.equals(List.of("10>20", "20>10")) : "both directions, once: " + calls;
        assert st.published == 2;

        // No key skip: the pass has no notion of faction, so a same-faction (invasion, opposing team) pair
        // reaches the predicate. It is the predicate that says no.
        calls.clear();
        st = run(hulls, hulls, true, true, calls, false);
        assert calls.size() == 2 && st.published == 0 : "evaluated, gated per direction";
    }

    private static void nonObserverSubjectsGetOneDirection() {
        List<Entry> observers = List.of(hull(10, 0, 64, 0, true));
        List<Entry> subjects = List.of(hull(10, 0, 64, 0, true), hull(30, 120, 64, 0, false),
                living(40, Kind.MORTAR_CREW, 0, 130));
        List<String> calls = new ArrayList<>();
        PassStats st = run(observers, subjects, true, true, calls, true);
        assert calls.equals(List.of("10>30", "10>40")) : "own id skipped; player hull and mortar crew are one-way: " + calls;
        assert st.pairs == 2;
    }

    private static void tiersAreIndependent() {
        List<Entry> hulls = List.of(hull(10, 0, 64, 0, true), hull(20, 50, 64, 0, true), hull(30, 200, 64, 0, true));
        List<String> calls = new ArrayList<>();
        run(hulls, hulls, true, false, calls, true);
        assert calls.contains("10>20") && !calls.contains("10>30") : "close tier only: " + calls;
        calls.clear();
        run(hulls, hulls, false, true, calls, true);
        assert !calls.contains("10>20") && calls.contains("10>30") : "wide tier only: " + calls;
        calls.clear();
        run(hulls, hulls, false, false, calls, true);
        assert calls.isEmpty();
    }

    private static void rangeAndVerticalLimits() {
        List<Entry> far = List.of(hull(10, 0, 64, 0, true), hull(20, 300, 64, 0, true));
        List<String> calls = new ArrayList<>();
        run(far, far, true, true, calls, true);
        assert calls.isEmpty() : "beyond wideScanRadius nothing is evaluated";
        List<Entry> high = List.of(hull(10, 0, 64, 0, true), hull(20, 100, 64 + 200, 0, true));
        run(high, high, true, true, calls, true);
        assert calls.isEmpty() : "outside the vertical band";
    }

    private static void orderIsIdDerived() {
        List<Entry> hulls = CombatantIndex.sortedById(List.of(hull(30, 0, 64, 0, true), hull(10, 60, 64, 0, true),
                hull(20, 120, 64, 0, true)));
        List<String> calls = new ArrayList<>();
        run(hulls, hulls, true, true, calls, true);
        assert calls.equals(List.of("10>20", "20>10", "10>30", "30>10", "20>30", "30>20")) : "call order: " + calls;
    }

    private static void snapshotQuery() {
        List<Entry> living = List.of(living(9, Kind.UNIT, -70, -70), living(3, Kind.SOFT, 10, 10),
                living(5, Kind.UNIT, 500, 500), living(7, Kind.MORTAR_CREW, -10, 20));
        Snapshot snap = new Snapshot(0, living, List.of(hull(50, 0, 64, 0, true)));
        List<Entry> hit = snap.query(0, 0, 100, 0, 128);
        StringBuilder ids = new StringBuilder();
        for (Entry e : hit) ids.append(e.id).append(',');
        assert ids.toString().equals("3,7,9,") : "ascending by id, box-filtered, across the negative cell boundary: " + ids;
        assert snap.query(0, 0, 100, 100, 128).isEmpty() : "vertical filter";
        assert snap.hulls().size() == 1 && snap.subjects().size() == 2 : "subjects = hulls + mortar crews";
        assert snap.subjects().get(0).id == 7 && snap.subjects().get(1).id == 50 : "subjects are id-sorted";
        for (Entry e : hit) assert e.kind != Kind.HULL : "hulls are never returned by a living query";
    }

    private static void noSnapshotIsNull() {
        assert CombatantIndex.snapshot(null) == null : "no snapshot means nothing known, never a build";
    }

    private FactionWideScanSelfCheck() {}
}
