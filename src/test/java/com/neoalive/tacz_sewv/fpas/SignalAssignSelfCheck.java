package com.neoalive.tacz_sewv.fpas;

import java.util.List;

/** Run with {@code ./gradlew selfCheckSignal}. Pure assignment checks, no world. */
public final class SignalAssignSelfCheck {

    private SignalAssignSelfCheck() {}

    private static double[][] pts(double... xz) {
        double[][] out = new double[xz.length / 2][];
        for (int i = 0; i < out.length; i++) out[i] = new double[] {xz[2 * i], xz[2 * i + 1]};
        return out;
    }

    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");

        // empty sides
        assert SignalAssign.assign(pts(), pts(0, 0)).isEmpty();
        List<List<Integer>> none = SignalAssign.assign(pts(0, 0), pts());
        assert none.size() == 1 && none.get(0).isEmpty();

        // 1v1
        assert SignalAssign.assign(pts(0, 0), pts(5, 0)).equals(List.of(List.of(0)));

        // 2v2 split by mutual distance, not by index
        List<List<Integer>> two = SignalAssign.assign(pts(0, 0, 100, 0), pts(110, 0, 10, 0));
        assert two.equals(List.of(List.of(1), List.of(0))) : two;

        // 1v3: one ally takes all, nearest first
        List<List<Integer>> one = SignalAssign.assign(pts(0, 0), pts(30, 0, 10, 0, 20, 0));
        assert one.equals(List.of(List.of(1, 2, 0))) : one;

        // 2v3: A gets A and C, B gets B
        List<List<Integer>> mix = SignalAssign.assign(pts(0, 0, 100, 0), pts(5, 0, 95, 0, 20, 0));
        assert mix.equals(List.of(List.of(0, 2), List.of(1))) : mix;

        // 3v1: everyone on the one
        List<List<Integer>> pile = SignalAssign.assign(pts(0, 0, 50, 0, -50, 0), pts(10, 0));
        assert pile.equals(List.of(List.of(0), List.of(0), List.of(0))) : pile;

        // every enemy assigned exactly once when allies <= enemies
        List<List<Integer>> big = SignalAssign.assign(pts(0, 0, 40, 40, -40, 10),
                pts(1, 1, 2, 2, 3, 3, 41, 41, -39, 9, 80, 80, 7, -7));
        boolean[] seen = new boolean[7];
        for (List<Integer> l : big) for (int e : l) {
            assert !seen[e] : "dup " + e;
            seen[e] = true;
        }
        for (boolean s : seen) assert s;

        // deterministic
        assert big.equals(SignalAssign.assign(pts(0, 0, 40, 40, -40, 10),
                pts(1, 1, 2, 2, 3, 3, 41, 41, -39, 9, 80, 80, 7, -7)));

        // capability mask: ally 0 (rifleman) cannot take enemy 0 (tank) even though it is nearest
        boolean[][] able = {{false, true}, {true, true}};
        List<List<Integer>> masked = SignalAssign.assign(pts(0, 0, 100, 0), pts(1, 0, 99, 0), able);
        assert masked.equals(List.of(List.of(1), List.of(0))) : masked;
        // an ally that can take nothing gets nothing, and does not double up
        boolean[][] none2 = {{false, false}, {true, true}};
        List<List<Integer>> idle = SignalAssign.assign(pts(0, 0, 100, 0), pts(1, 0, 99, 0), none2);
        assert idle.get(0).isEmpty() && idle.get(1).size() == 2 : idle;

        // standoff: a far unit stops on its own side, just inside range
        double[] west = SignalAssign.standoff(-100, 0, 0, 0, 40);
        assert Math.abs(west[0] + 40 * SignalAssign.STANDOFF_FRACTION) < 1e-9 && Math.abs(west[1]) < 1e-9
                : west[0] + "," + west[1];
        // opposite sides get opposite points
        double[] east = SignalAssign.standoff(100, 0, 0, 0, 40);
        assert Math.abs(east[0] + west[0]) < 1e-9;
        // already in range: stays put
        double[] near = SignalAssign.standoff(20, 5, 0, 0, 40);
        assert near[0] == 20 && near[1] == 5;
        // zero range (aircraft) flies onto the contact
        double[] onto = SignalAssign.standoff(-100, 0, 3, 4, 0);
        assert Math.abs(onto[0] - 3) < 1e-9 && Math.abs(onto[1] - 4) < 1e-9;

        System.out.println("SignalAssignSelfCheck OK");
    }
}
