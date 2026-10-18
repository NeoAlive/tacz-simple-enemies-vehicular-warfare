package com.neoalive.tacz_sewv.entity.ai.support;

import com.neoalive.tacz_sewv.entity.ai.support.DismountGeometry.Footprint;

/**
 * Headless check of the IFV dismount geometry: corner placement and rotation, the margin that
 * keeps a man off the hull, threat-side choice and the even/odd step count.
 * Run via {@code ./gradlew selfCheckDismount}.
 */
public final class DismountSelfCheck {

    private static final double EPS = 1.0E-9;

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        footprint();
        corners();
        outsideFootprint();
        sides();
        steps();
        System.out.println("DismountSelfCheck OK");
    }

    /** A 4 x 8 box (half 2 wide, 4 long) facing +Z. */
    private static double[] box(double halfW, double halfL) {
        return new double[] {halfW, halfL, -halfW, halfL, halfW, -halfL, -halfW, -halfL};
    }

    private static void footprint() {
        Footprint fp = DismountGeometry.footprint(box(2, 4), 4, 0, 1, 1.0);
        assert Math.abs(fp.rear() + 4) < EPS && Math.abs(fp.halfWidth() - 2) < EPS : fp;
        // Same hull facing +X: the 4-long axis is now X, so the frame must swap the extents.
        Footprint turned = DismountGeometry.footprint(new double[] {4, 2, 4, -2, -4, 2, -4, -2}, 4, 1, 0, 1.0);
        assert Math.abs(turned.rear() + 4) < EPS && Math.abs(turned.halfWidth() - 2) < EPS : turned;
        Footprint none = DismountGeometry.footprint(null, 0, 0, 1, 1.5);
        assert none.rear() == -1.5 && none.halfWidth() == 1.5 : "no OBBs falls back to the box";
        // A hull whose points all sit ahead of centre still has its rear at the centre, never ahead.
        Footprint ahead = DismountGeometry.footprint(new double[] {1, 1, -1, 3}, 2, 0, 1, 1.0);
        assert ahead.rear() <= 0.0 : ahead;
    }

    private static void corners() {
        Footprint fp = new Footprint(-4, 2);
        double m = DismountGeometry.MARGIN;
        // Facing +Z: right is -X, rear is -Z.
        double[] right = DismountGeometry.cornerOffset(fp, 1, 0, 1);
        assert Math.abs(right[0] + (2 + m)) < EPS && Math.abs(right[1] + (4 + m)) < EPS : "yaw 0 right";
        double[] left = DismountGeometry.cornerOffset(fp, -1, 0, 1);
        assert Math.abs(left[0] - (2 + m)) < EPS && Math.abs(left[1] + (4 + m)) < EPS : "yaw 0 left";
        // Facing -X (yaw 90, west): rear is +X, right hand is north (-Z).
        double[] r90 = DismountGeometry.cornerOffset(fp, 1, -1, 0);
        assert Math.abs(r90[0] - (4 + m)) < EPS && Math.abs(r90[1] + (2 + m)) < EPS : "yaw 90 right";
        // Facing -Z (yaw 180): the yaw-0 corners mirror through the centre.
        double[] r180 = DismountGeometry.cornerOffset(fp, 1, 0, -1);
        assert Math.abs(r180[0] + right[0]) < EPS && Math.abs(r180[1] + right[1]) < EPS : "yaw 180 right";
        // Outward diagonals point away from the hull and are unit length.
        for (int side : new int[] {1, -1}) {
            double[] out = DismountGeometry.outward(side, 0, 1);
            double[] c = DismountGeometry.cornerOffset(fp, side, 0, 1);
            assert Math.abs(Math.hypot(out[0], out[1]) - 1) < EPS;
            assert out[0] * c[0] + out[1] * c[1] > 0 : "outward leaves the hull";
        }
    }

    private static void outsideFootprint() {
        Footprint fp = new Footprint(-3.5, 1.75);
        double[][] frames = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {Math.sqrt(0.5), Math.sqrt(0.5)}};
        for (double[] f : frames) {
            for (int side : new int[] {1, -1}) {
                double[] c = DismountGeometry.cornerOffset(fp, side, f[0], f[1]);
                double fwd = c[0] * f[0] + c[1] * f[1];
                double lat = c[0] * -f[1] + c[1] * f[0];
                assert fwd <= fp.rear() - DismountGeometry.MARGIN + EPS : "behind the rear by MARGIN";
                assert Math.abs(lat) >= fp.halfWidth() + DismountGeometry.MARGIN - EPS : "beside the side by MARGIN";
                assert Math.signum(lat) == side : "side sign matches the frame";
            }
        }
    }

    private static void sides() {
        // Facing +Z, right is -X: a threat on the right sends the file out the left.
        assert DismountGeometry.awaySide(0, 1, -10, 5) == -1 : "threat right -> left corner";
        assert DismountGeometry.awaySide(0, 1, 10, 5) == 1 : "threat left -> right corner";
        assert DismountGeometry.awaySide(0, 1, 0, 30) == 0 : "dead ahead: no preference";
        assert DismountGeometry.awaySide(0, 1, 0, -30) == 0 : "dead astern: no preference";
        assert DismountGeometry.paritySide(2) == 1 && DismountGeometry.paritySide(3) == -1;
        assert DismountGeometry.paritySide(-3) == -1;
    }

    private static void steps() {
        assert DismountGeometry.steps(6) == 3 : "even squad leaves in pairs";
        assert DismountGeometry.steps(2) == 1;
        assert DismountGeometry.steps(7) == 7 : "odd squad leaves in single file";
        assert DismountGeometry.steps(1) == 1;
        assert DismountGeometry.EXIT_INTERVAL_TICKS == 10 : "half a second between steps";
    }
}
