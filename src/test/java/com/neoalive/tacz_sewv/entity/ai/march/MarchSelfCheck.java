package com.neoalive.tacz_sewv.entity.ai.march;

/**
 * Headless checks for the march primitive: the road, the slots laid on it, and the pacing. Run with
 * {@code ./gradlew selfCheckMarch}.
 */
public final class MarchSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        routeArcLength();
        routeExtrapolates();
        columnFollowsTheRoad();
        paceAndPause();
        pauseHysteresis();
        lineIsCentredAndDistinct();
        filesAreBalanced();
        fileSitsBehindItsHull();
        lagSign();
        deterministicSeedsAndHolds();

        System.out.println("march self-check: OK");
    }

    /** An L: 30 east, then 40 north. */
    private static MarchRoute ell() {
        MarchRoute r = new MarchRoute(0.0, 0.0, 1.0, 0.0);
        r.append(30.0, 0.0);
        r.append(30.0, -40.0);
        return r;
    }

    private static void routeArcLength() {
        MarchRoute r = ell();
        close(70.0, r.length(), "length");
        double[] p = r.pointAt(10.0);
        close(10.0, p[0], "s=10 x");
        close(0.0, p[1], "s=10 z");
        p = r.pointAt(50.0);
        close(30.0, p[0], "s=50 x");
        close(-20.0, p[1], "s=50 z");
        double[] t = r.tangentAt(50.0);
        close(0.0, t[0], "leg 2 tangent x");
        close(-1.0, t[1], "leg 2 tangent z");
        // A zero-length leg is refused, not stored as a NaN tangent.
        r.append(30.0, -40.0);
        close(70.0, r.length(), "zero leg ignored");
        t = r.tangentAt(r.length());
        assert Double.isFinite(t[0]) && Double.isFinite(t[1]) : "end tangent finite";
    }

    private static void routeExtrapolates() {
        MarchRoute r = ell();
        double[] before = r.pointAt(-12.0);
        close(-12.0, before[0], "before start x");
        close(0.0, before[1], "before start z");
        double[] after = r.pointAt(80.0);
        close(30.0, after[0], "past end x");
        close(-50.0, after[1], "past end z");
        MarchRoute bare = new MarchRoute(5.0, 5.0, 0.0, 2.0); // no leg yet: the starting heading, normalised
        double[] back = bare.pointAt(-3.0);
        close(5.0, back[0], "bare x");
        close(2.0, back[1], "bare z");
    }

    private static void columnFollowsTheRoad() {
        MarchRoute r = ell();
        double s = 60.0;
        double spacing = 12.0;
        for (int k = 0; k < 5; k++) {
            double[] slot = MarchGeometry.column(r, s, k, spacing);
            double[] onRoad = r.pointAt(s - k * spacing);
            close(onRoad[0], slot[0], "column k=" + k + " x on the road");
            close(onRoad[1], slot[1], "column k=" + k + " z on the road");
        }
        // Straight stretch: neighbours are exactly one spacing apart.
        double[] a = MarchGeometry.column(r, 25.0, 0, 8.0);
        double[] b = MarchGeometry.column(r, 25.0, 1, 8.0);
        close(8.0, Math.hypot(a[0] - b[0], a[1] - b[1]), "straight spacing");
    }

    private static void paceAndPause() {
        MarchRoute r = new MarchRoute(0.0, 0.0, 0.0, 1.0);
        r.append(0.0, 100.0);
        March m = new March(1, March.Kind.IDLE_TRAVEL, new int[] {1, 2}, new int[0], r, 0.0, 100.0, 10.0,
                0.14, 10.0, 0L);
        close(24.0, m.anchorAt(100L), "walks at pace");
        m.setPaused(true, 100L);
        close(24.0, m.anchorAt(500L), "a pause freezes the anchor");
        assert m.pausedFor(500L) == 400L : "paused-for counts";
        m.setPaused(false, 500L);
        assert m.pausedFor(600L) == 0L : "not paused";
        close(38.0, m.anchorAt(600L), "resumes from where it stopped");
        close(100.0, m.anchorAt(100_000L), "never past the road");
        assert !m.arrived(100_000L) : "an open road is never arrived at";
        m.routeClosed = true;
        assert m.arrived(100_000L) : "a closed road is arrived at";
    }

    private static void pauseHysteresis() {
        assert March.shouldPause(false, 1.2, 0.5) : "a straggler past the limit stops the march";
        assert !March.shouldPause(false, 0.9, 0.5) : "inside the limit it keeps going";
        assert March.shouldPause(true, 0.7, 0.5) : "waiting: still waits above the resume line";
        assert !March.shouldPause(true, 0.4, 0.5) : "waiting: goes again below the resume line";
    }

    private static void lineIsCentredAndDistinct() {
        for (int n = 1; n <= 6; n++) {
            double sum = 0.0;
            for (int k = 0; k < n; k++) {
                double lat = MarchGeometry.lineLateral(k, n, 16.0);
                sum += lat;
                if (k > 0) close(16.0, Math.abs(lat - MarchGeometry.lineLateral(k - 1, n, 16.0)), "line spacing n=" + n);
            }
            close(0.0, sum, "line centred n=" + n);
        }
    }

    private static void filesAreBalanced() {
        int hulls = 3;
        int[] count = new int[hulls];
        boolean[][] rank = new boolean[hulls][8];
        for (int i = 0; i < 7; i++) {
            int h = MarchGeometry.fileHull(i, hulls);
            int r = MarchGeometry.fileRank(i, hulls);
            assert !rank[h][r] : "two infantry share a place in hull " + h + "'s file";
            rank[h][r] = true;
            count[h]++;
        }
        assert count[0] == 3 && count[1] == 2 && count[2] == 2 : "files are dealt round-robin";
    }

    private static void fileSitsBehindItsHull() {
        MarchRoute r = new MarchRoute(0.0, 0.0, 0.0, 1.0);
        r.append(0.0, 200.0);
        double s = 100.0;
        for (int k = 0; k < 3; k++) {
            double[] hull = MarchGeometry.line(r, s, k, 3, 16.0);
            for (int rank = 0; rank < 3; rank++) {
                double[] f = MarchGeometry.file(r, s, k, 3, 16.0, rank);
                close(hull[0], f[0], "file k=" + k + " same lateral");
                close(hull[1] - (rank + 1) * MarchGeometry.FILE_GAP, f[1], "file k=" + k + " rank " + rank + " behind");
            }
        }
    }

    private static void lagSign() {
        double[] slot = {0.0, 10.0, 0.0, 1.0}; // at z=10, heading +Z
        close(10.0, MarchGeometry.lag(0.0, 0.0, slot), "behind is positive");
        close(-5.0, MarchGeometry.lag(3.0, 15.0, slot), "ahead is negative");
    }

    private static void deterministicSeedsAndHolds() {
        for (int id = 1; id < 200; id += 7) {
            double a = RouteScout.seedAngle(id);
            assert a >= 0.0 && a < Math.PI * 2.0 : "seed in range";
            assert a == RouteScout.seedAngle(id) : "seed deterministic";
            int h = IdleMarches.holdTicks(id);
            assert h == IdleMarches.holdTicks(id) : "hold deterministic";
        }
        assert RouteScout.seedAngle(1) != RouteScout.seedAngle(2) : "neighbouring ids set off differently";
    }

    private static void close(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1.0e-6 : what + ": expected " + expected + " but was " + actual;
    }

    private MarchSelfCheck() {}
}
