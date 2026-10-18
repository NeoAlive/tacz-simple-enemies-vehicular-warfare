package com.neoalive.tacz_sewv.entity.ai.march;

import net.minecraft.world.phys.Vec3;

import com.neoalive.tacz_sewv.entity.ai.formation.FormationFrame;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationGeometry;
import com.neoalive.tacz_sewv.entity.ai.formation.FormationSpec;
import com.neoalive.tacz_sewv.entity.ai.support.FormationShape;

/**
 * The only march slot maths. Every slot is answered as {@code {x, z, fx, fz}} — where to be, and which way
 * the march is going there.
 *
 * <ul>
 *   <li><b>Column</b> (hulls on the road): hull k at arc length {@code s - k * spacing}. Measured along the
 *       route, not along a straight bar, so the column follows the road through a bend.</li>
 *   <li><b>Trail</b> (infantry squads behind a column): a point some distance back down the route, offset
 *       sideways. Squads alternate sides so two do not walk in each other.</li>
 *   <li><b>Line</b> (hulls abreast): the kernel's {@code LINE}, centred on the anchor, facing the route.</li>
 *   <li><b>File</b> (infantry behind a line hull): straight back down the route from that hull's slot, at the
 *       hull's own lateral offset, so the file stays in the hull's lee through a bend.</li>
 * </ul>
 *
 * <p>Pure: headless-testable.
 */
public final class MarchGeometry {

    /** Gap between infantry in a file behind a hull, and between a hull and the first of its file. */
    public static final double FILE_GAP = 3.0;

    private MarchGeometry() {}

    /** Hull {@code k} of a column whose head is at arc length {@code s}. */
    public static double[] column(MarchRoute route, double s, int k, double spacing) {
        return at(route, s - k * spacing, 0.0);
    }

    /** {@code behind} back down the route from {@code s}, {@code lateral} to the left of it. */
    public static double[] trail(MarchRoute route, double s, double behind, double lateral) {
        return at(route, s - behind, lateral);
    }

    /** Squads alternate sides of the road: k even to the left, odd to the right. */
    public static double alternate(int k, double offset) {
        return (k % 2 == 0 ? 1.0 : -1.0) * offset;
    }

    /** Lateral offset (to the left) of hull {@code k} of an {@code n}-hull line, centred. */
    public static double lineLateral(int k, int n, double spacing) {
        FormationSpec line = new FormationSpec(FormationShape.LINE, spacing, 1.0, 1.0, Math.max(1, n),
                FormationSpec.Placement.CENTRED);
        // A frame at the origin facing +Z: its left is +X, so the slot's X is the lateral to the left.
        Vec3 p = FormationGeometry.slot(new FormationFrame(0.0, 0.0, 0.0, 0.0, 1.0), line, k, n);
        return p.x;
    }

    /** Hull {@code k} of an {@code n}-hull line whose centre is at arc length {@code s}. */
    public static double[] line(MarchRoute route, double s, int k, int n, double spacing) {
        return at(route, s, lineLateral(k, n, spacing));
    }

    /** Rank {@code rank} of the file behind hull {@code k} of an {@code n}-hull line centred at {@code s}. */
    public static double[] file(MarchRoute route, double s, int k, int n, double spacing, int rank) {
        return at(route, s - (rank + 1) * FILE_GAP, lineLateral(k, n, spacing));
    }

    /** Which hull infantry member {@code i} files behind: shared round-robin, so the files stay even. */
    public static int fileHull(int i, int hulls) {
        return i % Math.max(1, hulls);
    }

    /** Member {@code i}'s rank in its hull's file. */
    public static int fileRank(int i, int hulls) {
        return i / Math.max(1, hulls);
    }

    /** How far {@code (x, z)} is behind {@code slot} along the slot's heading; negative when ahead of it. */
    public static double lag(double x, double z, double[] slot) {
        return (slot[0] - x) * slot[2] + (slot[1] - z) * slot[3];
    }

    private static double[] at(MarchRoute route, double s, double lateral) {
        double[] p = route.pointAt(s);
        double[] t = route.tangentAt(s);
        double lx = FormationFrame.leftX(t[0], t[1]);
        double lz = FormationFrame.leftZ(t[0], t[1]);
        return new double[] {p[0] + lx * lateral, p[1] + lz * lateral, t[0], t[1]};
    }
}
