package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.List;
import java.util.Map;

import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Entry;
import com.neoalive.tacz_sewv.entity.ai.sensor.CombatantIndex.Kind;

/**
 * Self-check for the outer ring. Run via {@code ./gradlew selfCheck} (selfCheckOuterRing).
 *
 * <p>Band geometry/strength, and the snapshot pair pass: mutual from one distance, annulus only.
 */
public final class OuterRingAwarenessSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        bandGeometry();
        pairPassIsMutualAndAnnular();

        System.out.println("outer-ring awareness self-check: OK");
    }

    private static void bandGeometry() {
        double inner = 96.0;
        double outer = 192.0;
        assertClose(96.0, OuterRingAwareness.bandLo(inner, 0), "near lo");
        assertClose(128.0, OuterRingAwareness.bandHi(inner, outer, 0), "near hi");
        assertClose(128.0, OuterRingAwareness.bandLo(inner, 1), "mid lo");
        assertClose(160.0, OuterRingAwareness.bandHi(inner, outer, 1), "mid hi");
        assertClose(160.0, OuterRingAwareness.bandLo(inner, 2), "far lo");
        assertClose(192.0, OuterRingAwareness.bandHi(inner, outer, 2), "far hi");
        assertClose(192.0, OuterRingAwareness.bandLo(inner, 3), "edge lo");
        assertClose(192.0, OuterRingAwareness.bandHi(inner, outer, 3), "edge hi");
        assertClose(1.0, OuterRingAwareness.strengthAt(inner, outer, 100), "near strength");
        assertClose(0.5, OuterRingAwareness.strengthAt(inner, outer, 170), "far strength");
    }

    /** a(0) skips c(30), inside its scan radius, and spots b(150); b and c (120 apart) spot each other. */
    private static void pairPassIsMutualAndAnnular() {
        Entry a = hull(1, 0);
        Entry b = hull(2, 150);
        Entry c = hull(3, 30);
        List<Entry> all = List.of(a, b, c);
        Map<Integer, OuterRingAwareness.Best> best =
                OuterRingAwareness.nearestPairs(all, all, 96, 192, 8, 8, (o, s) -> true);
        assert best.get(1) != null && best.get(1).subject().id == 2 : "a should spot b, got " + best.get(1);
        assert best.get(2) != null && best.get(2).subject().id == 3 : "b should spot c (120, nearer than a)";
        assert best.get(3) != null && best.get(3).subject().id == 2 : "c should spot b at 120";
        assertClose(150.0 * 150.0, best.get(1).distSq(), "a-b distSq");
    }

    private static Entry hull(int id, double x) {
        return new Entry(id, Kind.HULL, x, 64, 0, null, null, true, 0.0);
    }

    private static void assertClose(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1.0E-9
                : what + ": expected " + expected + " but was " + actual;
    }

    private OuterRingAwarenessSelfCheck() {}
}
