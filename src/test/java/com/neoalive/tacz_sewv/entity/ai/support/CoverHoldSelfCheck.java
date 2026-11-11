package com.neoalive.tacz_sewv.entity.ai.support;

/** {@code ./gradlew selfCheckCoverHold}: the pure half of Cover and Hold. */
public final class CoverHoldSelfCheck {

    private static final double EPS = 1.0E-9;

    public static void main(String[] args) {
        bearingAndThreat();
        spread();
        deadline();
        System.out.println("cover hold self-check: OK");
    }

    private static void bearingAndThreat() {
        double[] b = CoverHoldGeometry.bearing(0, 0, 10, 0, 0f);
        assert near(b[0], 1) && near(b[1], 0) : "east of the issuer reads east";
        double[] t = CoverHoldGeometry.threatPoint(10, 64, 0, b);
        assert near(t[0], 10 + CoverHoldGeometry.THREAT_RANGE) && near(t[2], 0) : "threat sits downrange";
        assert near(t[1], 64 + CoverHoldGeometry.THREAT_EYE) : "threat at eye height";
        // Standing on the block: the look yaw decides. Mojmap yaw 0 looks +Z.
        double[] own = CoverHoldGeometry.bearing(5, 5, 5, 5, 0f);
        assert near(own[0], 0) && near(own[1], 1) : "zero bearing falls back to the look yaw";
        double[] diag = CoverHoldGeometry.bearing(0, 0, 3, 4, 0f);
        assert near(diag[0] * diag[0] + diag[1] * diag[1], 1) : "bearing is a unit vector";
    }

    private static void spread() {
        double[] b = {1, 0};
        double[] solo = CoverHoldGeometry.spreadOrigin(0, 0, b, 0, 1);
        assert near(solo[0], 0) && near(solo[1], 0) : "a lone unit searches from the anchor";
        double[] first = CoverHoldGeometry.spreadOrigin(0, 0, b, 0, 3);
        double[] mid = CoverHoldGeometry.spreadOrigin(0, 0, b, 1, 3);
        double[] last = CoverHoldGeometry.spreadOrigin(0, 0, b, 2, 3);
        assert near(mid[0], 0) && near(mid[1], 0) : "odd squad centred on the anchor";
        assert near(first[1], -last[1]) && near(first[0], 0) && near(last[0], 0) : "spread is across the bearing, symmetric";
        assert near(Math.abs(last[1] - mid[1]), CoverHoldGeometry.SPREAD) : "origins one spread apart";
        double[] a = CoverHoldGeometry.spreadOrigin(0, 0, b, 0, 2);
        double[] c = CoverHoldGeometry.spreadOrigin(0, 0, b, 1, 2);
        assert near(a[1] + c[1], 0) : "even squad straddles the anchor";
    }

    private static void deadline() {
        assert CoverHoldGeometry.deadline(100, 0) == Long.MAX_VALUE : "0 is forever";
        assert CoverHoldGeometry.deadline(100, 200) == 300 : "timed hold ends after its ticks";
        assert CoverHoldOrders.MAX_HOLD_SECONDS % CoverHoldOrders.HOLD_STEP_SECONDS == 0 : "max is a whole step";
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < EPS;
    }
}
