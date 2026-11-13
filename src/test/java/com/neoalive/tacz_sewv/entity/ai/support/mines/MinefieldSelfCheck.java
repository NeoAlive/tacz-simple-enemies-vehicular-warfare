package com.neoalive.tacz_sewv.entity.ai.support.mines;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

/**
 * Headless check of the minefield planner. Run via {@code ./gradlew selfCheckMinefield}.
 */
public final class MinefieldSelfCheck {

    private static final Vec3 ANCHOR = new Vec3(100.5, 64.0, 200.5);
    private static final Vec3 NORTH = new Vec3(0.0, 0.0, -1.0);

    public static void main(String[] args) {
        boolean on = false;
        assert on = true;
        if (!on) throw new IllegalStateException("run with -ea");
        autoShapes();
        slotCounts();
        lineAcrossThreat();
        spacing();
        claymoresFaceThreat();
        ringFacesOut();
        echelonMirror();
        standoffNeverPastHalf();
        splitCoversCarried();
        splitIsContiguous();
        System.out.println("MinefieldSelfCheck OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static Vec3 threat(double dist) {
        return ANCHOR.add(0.0, 0.0, -dist);
    }

    private static void autoShapes() {
        check(MinefieldPlanner.autoShape(MineKinds.Placement.GROUND, false) == FormationShape.RING, "no threat rings");
        check(MinefieldPlanner.autoShape(MineKinds.Placement.GROUND, true) == FormationShape.LINE, "ground lines");
        check(MinefieldPlanner.autoShape(MineKinds.Placement.DIRECTIONAL, true) == FormationShape.WEDGE, "claymore wedge");
        check(MinefieldPlanner.autoShape(MineKinds.Placement.WALL, true) == FormationShape.LINE, "wall lines");
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, null, NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.AUTO, 6, 3.0, 8.0);
        check(p.shape() == FormationShape.RING, "empty threat gives ring");
        check(Math.abs(p.frame().x() - ANCHOR.x) < 1e-9 && Math.abs(p.frame().z() - ANCHOR.z) < 1e-9,
                "auto ring sits on the anchor");
    }

    private static void slotCounts() {
        for (MinefieldPlanner.ShapeChoice c : MinefieldPlanner.ShapeChoice.values()) {
            for (int n = 0; n <= 16; n++) {
                MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                        c, n, 3.0, 8.0);
                check(p.slots().length == n && p.yaws().length == n, "count " + c + " " + n);
            }
        }
    }

    private static void lineAcrossThreat() {
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.AUTO, 5, 4.0, 8.0);
        check(p.shape() == FormationShape.LINE, "line");
        for (Vec3 s : p.slots()) {
            check(Math.abs(s.z - (ANCHOR.z - 8.0)) < 1e-6, "single row lies across the approach at the standoff");
        }
        MinefieldPlanner.Plan two = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.LINE, 10, 4.0, 8.0);
        Set<Long> rows = new HashSet<>();
        for (Vec3 s : two.slots()) rows.add(Math.round(s.z * 1000));
        check(rows.size() == 2, "past six the line folds into two rows");
        Set<Long> cols = new HashSet<>();
        for (Vec3 s : two.slots()) cols.add(Math.round(s.x * 1000));
        check(cols.size() == 10, "second row is staggered, no shared lane");
    }

    private static void spacing() {
        for (MinefieldPlanner.ShapeChoice c : MinefieldPlanner.ShapeChoice.values()) {
            for (int n = 2; n <= 16; n++) {
                Vec3[] s = MinefieldPlanner.lay(ANCHOR, threat(60), NORTH, MineKinds.Placement.GROUND, c, n, 3.0, 8.0)
                        .slots();
                for (int i = 0; i < s.length; i++) {
                    for (int j = i + 1; j < s.length; j++) {
                        double d = Math.hypot(s[i].x - s[j].x, s[i].z - s[j].z);
                        check(d >= 1.5 - 1e-6, "slots too close " + c + " n=" + n + " d=" + d);
                    }
                }
            }
        }
    }

    private static void claymoresFaceThreat() {
        Vec3 t = threat(30).add(7.0, 0.0, 0.0);
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, t, NORTH, MineKinds.Placement.DIRECTIONAL,
                MinefieldPlanner.ShapeChoice.AUTO, 5, 3.0, 8.0);
        check(p.shape() == FormationShape.WEDGE, "claymores wedge");
        for (int i = 0; i < p.slots().length; i++) {
            Vec3 look = lookOf(p.yaws()[i]);
            Vec3 to = new Vec3(t.x - p.slots()[i].x, 0.0, t.z - p.slots()[i].z).normalize();
            check(look.dot(to) > 0.999, "claymore " + i + " faces the threat");
        }
        // Apex toward the threat: slot 0 is the closest to it.
        double d0 = p.slots()[0].distanceToSqr(t);
        for (int i = 1; i < p.slots().length; i++) check(p.slots()[i].distanceToSqr(t) > d0 - 1.0, "wedge apex forward");
    }

    private static void ringFacesOut() {
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, null, NORTH, MineKinds.Placement.DIRECTIONAL,
                MinefieldPlanner.ShapeChoice.AUTO, 8, 3.0, 8.0);
        for (int i = 0; i < p.slots().length; i++) {
            Vec3 out = new Vec3(p.slots()[i].x - ANCHOR.x, 0.0, p.slots()[i].z - ANCHOR.z).normalize();
            check(lookOf(p.yaws()[i]).dot(out) > 0.999, "ring mine " + i + " faces outward");
        }
    }

    private static void echelonMirror() {
        Vec3[] l = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.ECHELON_LEFT, 6, 3.0, 8.0).slots();
        Vec3[] r = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.ECHELON_RIGHT, 6, 3.0, 8.0).slots();
        for (int i = 0; i < l.length; i++) {
            check(Math.abs((l[i].x - ANCHOR.x) + (r[i].x - ANCHOR.x)) < 1e-6, "echelons mirror in x");
            check(Math.abs(l[i].z - r[i].z) < 1e-6, "echelons share depth");
        }
    }

    private static void standoffNeverPastHalf() {
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, threat(6), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.LINE, 1, 3.0, 8.0);
        check(Math.abs(p.frame().z() - (ANCHOR.z - 3.0)) < 1e-6, "a close threat stops the push at the midpoint");
    }

    private static void splitCoversCarried() {
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.LINE, 9, 3.0, 8.0);
        Vec3[] eng = {ANCHOR.add(-5, 0, 0), ANCHOR.add(5, 0, 0), ANCHOR};
        int[] carry = {2, 4, 5};
        List<List<Integer>> split = MinefieldPlanner.split(p.frame(), p.shape(), p.slots(), eng, carry);
        Set<Integer> seen = new HashSet<>();
        int total = 0;
        for (int i = 0; i < split.size(); i++) {
            check(split.get(i).size() <= carry[i], "never more than carried");
            for (int idx : split.get(i)) check(seen.add(idx), "each slot once");
            total += split.get(i).size();
        }
        check(total == 9, "every slot gets an engineer when enough is carried");
        List<List<Integer>> few = MinefieldPlanner.split(p.frame(), p.shape(), p.slots(), eng, new int[] {1, 1, 1});
        int n = 0;
        for (List<Integer> s : few) n += s.size();
        check(n == 3, "slots past what is carried are left out");
    }

    private static void splitIsContiguous() {
        MinefieldPlanner.Plan p = MinefieldPlanner.lay(ANCHOR, threat(40), NORTH, MineKinds.Placement.GROUND,
                MinefieldPlanner.ShapeChoice.LINE, 6, 3.0, 8.0);
        // Facing north, left is west: the western engineer takes the western end.
        Vec3[] eng = {ANCHOR.add(20, 0, 0), ANCHOR.add(-20, 0, 0)};
        List<List<Integer>> split = MinefieldPlanner.split(p.frame(), p.shape(), p.slots(), eng, new int[] {3, 3});
        double eastMin = Double.POSITIVE_INFINITY;
        double westMax = Double.NEGATIVE_INFINITY;
        for (int idx : split.get(0)) eastMin = Math.min(eastMin, p.slots()[idx].x);
        for (int idx : split.get(1)) westMax = Math.max(westMax, p.slots()[idx].x);
        check(split.get(0).size() == 3 && split.get(1).size() == 3, "even split");
        check(westMax < eastMin, "each engineer takes its own end, runs do not interleave");
    }

    private static Vec3 lookOf(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0.0, Math.cos(r));
    }
}
