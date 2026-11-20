package com.neoalive.tacz_sewv.util;

/** Pins the smoke cloud model to the live particle measurements. Run with -ea. */
public final class SmokeCloudSelfCheck {

    private SmokeCloudSelfCheck() {}

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea, or this checks nothing");

        decoyGrowthTracksMeasurement();
        m18GrowsSlowerThanDecoy();
        nothingBeforeIgnition();
        settlesAtConfiguredRadius();
        ellipsoidIsLowerThanWide();
        segmentTest();

        System.out.println("smoke cloud self-check: OK");
    }

    /** Measured visible edge of a decoy burst: 2.6 at 1 s, 3.6 at 2 s, 4.2 at 3 s, 4.9 at 5 s. */
    private static void decoyGrowthTracksMeasurement() {
        double[][] seen = {{20, 2.6}, {40, 3.6}, {60, 4.2}, {100, 4.9}, {200, 5.4}};
        for (double[] s : seen) {
            double r = SmokeCloud.radius(6.0, s[0], SmokeCloud.DECOY_TAU);
            assert Math.abs(r - s[1]) < 1.0 : "decoy at " + s[0] + " ticks: model " + r + " vs seen " + s[1];
        }
    }

    /** Measured M18 trail edge: 2.3 at 1 s, 3.8 at 5 s, 4.9 at 10 s. */
    private static void m18GrowsSlowerThanDecoy() {
        double[][] seen = {{20, 2.3}, {100, 3.8}, {200, 4.9}};
        for (double[] s : seen) {
            double r = SmokeCloud.radius(6.0, s[0], SmokeCloud.M18_TAU);
            assert Math.abs(r - s[1]) < 0.8 : "m18 at " + s[0] + " ticks: model " + r + " vs seen " + s[1];
        }
        assert SmokeCloud.radius(6.0, 40, SmokeCloud.M18_TAU) < SmokeCloud.radius(6.0, 40, SmokeCloud.DECOY_TAU);
    }

    private static void nothingBeforeIgnition() {
        assert SmokeCloud.radius(6.0, -1, SmokeCloud.DECOY_TAU) == 0.0 : "a canister in flight is not smoke";
        assert SmokeCloud.radius(6.0, 0, SmokeCloud.DECOY_TAU) == SmokeCloud.QUAD : "ignition starts at one puff";
    }

    private static void settlesAtConfiguredRadius() {
        double r = SmokeCloud.radius(6.0, 400, SmokeCloud.DECOY_TAU);
        assert Math.abs(r - 6.0) < 0.01 : "settled cloud must reach the configured radius, got " + r;
        assert SmokeCloud.radius(1.0, 0, SmokeCloud.DECOY_TAU) == 1.0 : "radius under one puff is used as is";
    }

    private static void ellipsoidIsLowerThanWide() {
        // Horizontal line 5.5 out passes; vertical reach is 0.78 of that, so 5.5 up misses.
        assert SmokeCloud.segmentHits(-20, 0, 5.5, 20, 0, 5.5, 0, 0, 0, 6.0) : "5.5 blocks to the side is inside";
        assert !SmokeCloud.segmentHits(-20, 5.5, 0, 20, 5.5, 0, 0, 0, 0, 6.0) : "5.5 blocks above is over the top";
        assert SmokeCloud.segmentHits(-20, 4.0, 0, 20, 4.0, 0, 0, 0, 0, 6.0) : "4 blocks above is inside";
        // An AABB inflate of 6 blocked its horizontal corner at 8.4 out; the cloud does not.
        assert !SmokeCloud.segmentHits(-20, 0, 7.5, 20, 0, 7.5, 0, 0, 0, 6.0) : "7.5 out is clear";
    }

    private static void segmentTest() {
        assert SmokeCloud.segmentHits(0, 0, 0, 30, 0, 0, 0, 0, 0, 6.0) : "muzzle inside its own smoke is blocked";
        assert SmokeCloud.segmentHits(-30, 0, 0, 0, 0, 0, 0, 0, 0, 6.0) : "target inside smoke is blocked";
        assert !SmokeCloud.segmentHits(10, 0, 0, 30, 0, 0, 0, 0, 0, 6.0) : "segment ending short of the cloud is clear";
        assert !SmokeCloud.segmentHits(-30, 0, 0, 30, 0, 0, 0, 0, 0, 0.0) : "zero radius blocks nothing";
    }
}
