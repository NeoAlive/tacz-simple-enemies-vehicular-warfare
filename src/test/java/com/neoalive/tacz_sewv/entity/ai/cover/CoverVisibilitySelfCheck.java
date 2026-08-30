package com.neoalive.tacz_sewv.entity.ai.cover;

/**
 * Headless checks for cover compass indexing and exposure/keyhole math over a synthetic grid.
 * Run via {@code ./gradlew selfCheckCover}.
 */
public final class CoverVisibilitySelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea");

        compassCardinals();
        exposureFromSynthetic();
        keyholeNeedsAdjacentOpen();
        chunkKeyRoundTrip();

        System.out.println("cover visibility self-check: OK");
    }

    private static void compassCardinals() {
        assert CoverVisibilityCache.compass8(0, 1) == 0 : "N";
        assert CoverVisibilityCache.compass8(1, 1) == 1 : "NE";
        assert CoverVisibilityCache.compass8(1, 0) == 2 : "E";
        assert CoverVisibilityCache.compass8(1, -1) == 3 : "SE";
        assert CoverVisibilityCache.compass8(0, -1) == 4 : "S";
        assert CoverVisibilityCache.compass8(-1, -1) == 5 : "SW";
        assert CoverVisibilityCache.compass8(-1, 0) == 6 : "W";
        assert CoverVisibilityCache.compass8(-1, 1) == 7 : "NW";
    }

    /**
     * exposure = clamp(dOcc / D). Occluder at 10, threat at 40 → 0.25 covered-ish;
     * clear to MAX → 1.0 exposed.
     */
    private static void exposureFromSynthetic() {
        double D = 40.0;
        int dOcc = 10;
        double exp = Math.min(1.0, Math.max(0.0, dOcc / D));
        assertNear(0.25, exp, "partial cover exposure");
        assertNear(1.0, Math.min(1.0, CoverVisibilityCache.MAX_RANGE / D), "clear is exposed");
        assertNear(0.0, Math.min(1.0, 0 / D), "immediate occluder is masked");
    }

    private static void keyholeNeedsAdjacentOpen() {
        // Covered toward threat (low dOcc/D) * open adjacent (high dAdj/MAX).
        double cover = 1.0 - 10.0 / 40.0; // 0.75
        double open = 40.0 / CoverVisibilityCache.MAX_RANGE; // ~0.83
        double q = Math.min(1.0, Math.max(0.0, cover * open));
        assert q > 0.5 : "keyhole should score when covered + adjacent open, got " + q;
        double noCover = 1.0 - 40.0 / 40.0; // 0
        assert noCover * open == 0.0 : "no cover toward threat → no keyhole";
    }

    private static void chunkKeyRoundTrip() {
        long k = CoverVisibilityCache.ChunkPosKey.of(-3, 12);
        assert CoverVisibilityCache.ChunkPosKey.x(k) == -3;
        assert CoverVisibilityCache.ChunkPosKey.z(k) == 12;
        long cell = CoverVisibilityCache.cellKey(5, 7); // >>1 → 2, 3
        assert cell == net.minecraft.core.BlockPos.asLong(2, 0, 3);
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0E-6) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
