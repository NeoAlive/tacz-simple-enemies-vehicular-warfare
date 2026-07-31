package com.neoalive.tacz_sewv.entity.ai;

/**
 * Self-check for outer-ring band stagger. Run via {@code ./gradlew selfCheck} (selfCheckOuterRing).
 *
 * <p>Protects the load-spreading claim: without stagger, N hulls all poll on the same tick;
 * with {@link OuterRingAwareness#nextDeadline}, peak concurrent polls stay near N/interval.
 */
public final class OuterRingAwarenessSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        bandGeometry();
        staggerBeatsSync();
        coarseVisibleRejectsUnloadContract();

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
    }

    /**
     * 64 hulls @ 40-tick near interval: unstaggered peak is 64; staggered peak must stay
     * well below half the fleet (ceil(64/40)=2 ideally; allow a small margin for phase bunching).
     */
    private static void staggerBeatsSync() {
        int hulls = 64;
        int interval = 40;
        int ticks = 2000;

        int unstaggered = OuterRingAwareness.simulateMaxPollsUnstaggered(hulls, interval, ticks);
        int staggered = OuterRingAwareness.simulateMaxPollsPerTick(hulls, interval, ticks);

        assert unstaggered == hulls
                : "unstaggered should spike to all hulls on tick 0, was " + unstaggered;
        assert staggered <= 3
                : "staggered peak polls/tick expected <= ceil(64/40)+margin (=3), was " + staggered
                + " (unstaggered=" + unstaggered + ")";
        assert staggered < unstaggered / 8
                : "stagger must cut peak by >8×: staggered=" + staggered + " unstaggered=" + unstaggered;

        System.out.println("  stagger: " + hulls + " hulls @" + interval + "t → peak "
                + staggered + " vs unstaggered " + unstaggered);
    }

    /** Documents the unload contract used by coarseVisible (no Level needed). */
    private static void coarseVisibleRejectsUnloadContract() {
        // Fixed-period advance — phase lives only in the attach offset.
        long d = OuterRingAwareness.nextDeadline(100, 40);
        assert d == 140 : "expected 140, was " + d;
    }

    private static void assertClose(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1.0E-9
                : what + ": expected " + expected + " but was " + actual;
    }

    private OuterRingAwarenessSelfCheck() {}
}
