package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * Headless check of the unit LOD tiers, strides and evaluation phasing.
 * Run via {@code ./gradlew selfCheckUnitLod}.
 */
public final class UnitLodPolicySelfCheck {

    public static void main(String[] args) {
        tiers();
        strides();
        oncePerWindowOnVanillaTicks();
        staggered();
        System.out.println("UnitLodPolicySelfCheck OK");
    }

    private static void tiers() {
        assert UnitLodPolicy.tierFor(127 * 127, 128, 256, false, true) == UnitLodPolicy.FULL;
        assert UnitLodPolicy.tierFor(128 * 128, 128, 256, false, true) == UnitLodPolicy.MID;
        assert UnitLodPolicy.tierFor(256 * 256, 128, 256, false, true) == UnitLodPolicy.FAR;
        assert UnitLodPolicy.tierFor(1e12, 128, 256, true, true) == UnitLodPolicy.FULL : "exempt is always full";
        assert UnitLodPolicy.tierFor(1e12, 128, 256, false, false) == UnitLodPolicy.FULL : "no player: full";
        assert UnitLodPolicy.tierFor(200 * 200, 300, 100, false, true) == UnitLodPolicy.FULL
                : "far below near is clamped up to near";
        assert UnitLodPolicy.tierFor(301 * 301, 300, 100, false, true) == UnitLodPolicy.FAR;
    }

    private static void strides() {
        for (int s = -3; s <= 40; s++) {
            int n = UnitLodPolicy.normaliseStride(s);
            assert n % 2 == 0 && n >= 2 && n <= UnitLodPolicy.MAX_STRIDE : s + " -> " + n;
        }
        assert UnitLodPolicy.normaliseStride(3) == 4;
        assert UnitLodPolicy.strideFor(UnitLodPolicy.FULL, 4, 8) == 2;
        assert UnitLodPolicy.strideFor(UnitLodPolicy.MID, 4, 8) == 4;
        assert UnitLodPolicy.strideFor(UnitLodPolicy.FAR, 4, 8) == 8;
        assert UnitLodPolicy.strideFor(UnitLodPolicy.FAR, 8, 4) == 8 : "far never finer than mid";
    }

    /** Each id evaluates exactly once per window, and only on ticks vanilla would evaluate it too. */
    private static void oncePerWindowOnVanillaTicks() {
        for (int stride : new int[] {2, 4, 8, 16}) {
            for (int id = 0; id < 64; id++) {
                int hits = 0;
                for (int t = 1000; t < 1000 + stride; t++) {
                    if (UnitLodPolicy.due(t, id, stride)) {
                        hits++;
                        assert Math.floorMod(t + id, 2) == 0 : "evaluation off vanilla's even branch";
                    }
                }
                assert hits == 1 : "stride " + stride + " id " + id + " hits " + hits;
            }
        }
    }

    /** 1000 consecutive ids at stride 8 spread evenly: no tick takes more than its share. */
    private static void staggered() {
        int n = 1000;
        int stride = 8;
        for (int t = 0; t < stride; t++) {
            int count = 0;
            for (int id = 0; id < n; id++) if (UnitLodPolicy.due(t, id, stride)) count++;
            assert count <= (n + stride - 1) / stride + 1 : "tick " + t + " evaluates " + count;
        }
    }
}
