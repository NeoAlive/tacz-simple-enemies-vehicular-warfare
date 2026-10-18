package com.neoalive.tacz_sewv.entity.ai.core;

/**
 * Headless check of the per-seat line-of-fire cache.
 * Run via {@code ./gradlew selfCheckLofCache}.
 */
public final class LineOfFireCacheSelfCheck {

    public static void main(String[] args) {
        emptyMisses();
        seatsDoNotEvict();
        ttl();
        identityKeys();
        outOfRange();
        System.out.println("LineOfFireCacheSelfCheck OK");
    }

    private static void emptyMisses() {
        LineOfFireCache c = new LineOfFireCache();
        assert c.lookup(0, 1, 2, 0, 8) == LineOfFireCache.MISS : "an unused cache never hits";
    }

    /** The regression: two gunners alternating on one hull must both hit. */
    private static void seatsDoNotEvict() {
        LineOfFireCache c = new LineOfFireCache();
        c.store(0, 10, 99, 100, false);
        c.store(1, 11, 99, 100, true);
        for (long t = 100; t < 108; t++) {
            assert c.lookup(0, 10, 99, t, 8) == LineOfFireCache.HIT_CLEAR : "seat 0 kept at " + t;
            assert c.lookup(1, 11, 99, t, 8) == LineOfFireCache.HIT_BLOCKED : "seat 1 kept at " + t;
        }
    }

    private static void ttl() {
        LineOfFireCache c = new LineOfFireCache();
        c.store(2, 10, 99, 100, true);
        assert c.lookup(2, 10, 99, 107, 8) == LineOfFireCache.HIT_BLOCKED;
        assert c.lookup(2, 10, 99, 108, 8) == LineOfFireCache.MISS : "expires at exactly ttl ticks";
        assert c.lookup(2, 10, 99, 100, 0) == LineOfFireCache.MISS : "ttl 0 disables the cache";
    }

    private static void identityKeys() {
        LineOfFireCache c = new LineOfFireCache();
        c.store(0, 10, 99, 100, true);
        assert c.lookup(0, 10, 98, 101, 8) == LineOfFireCache.MISS : "retarget must not inherit";
        assert c.lookup(0, 12, 99, 101, 8) == LineOfFireCache.MISS : "a new occupant must not inherit";
    }

    private static void outOfRange() {
        LineOfFireCache c = new LineOfFireCache();
        c.store(-1, 10, 99, 100, true);
        c.store(LineOfFireCache.SLOTS, 10, 99, 100, true);
        assert c.lookup(-1, 10, 99, 100, 8) == LineOfFireCache.MISS : "unseated shooter is uncached";
        assert c.lookup(LineOfFireCache.SLOTS, 10, 99, 100, 8) == LineOfFireCache.MISS;
    }
}
