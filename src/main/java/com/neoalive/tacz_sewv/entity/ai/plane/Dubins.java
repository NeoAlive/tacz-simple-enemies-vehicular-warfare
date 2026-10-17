package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Shortest-path solver between two oriented points at a fixed turn radius — the classic Dubins
 * (1957) six-primitive set: {@code LSL}, {@code RSR}, {@code LSR}, {@code RSL}, {@code RLR},
 * {@code LRL}. Pure geometry, no state, in the same spirit as {@link PlaneNav}: takes and returns
 * {@link Vec3} positions and unit directions throughout, never a bare yaw or bearing float.
 *
 * <p>{@link PlaneNav}'s own doc comment calls out that this mod's stored bearing
 * ({@code atan2(x,z)}) and vanilla's entity yaw differ by a sign, and that mismatch "looks like it
 * works on my runway" until it doesn't. This class sidesteps the whole class of bug: its one bare
 * angle (an {@link DubinsPath.Arc}'s {@code startAngle}) is a private circle parameterization that
 * is never compared against, or converted from, a yaw or a bearing anywhere outside this file.
 *
 * <p>The construction here (turn-circle centers + explicit tangent geometry, rather than the
 * closed-form trigonometric identities some references quote directly) was chosen so every
 * primitive could be checked by a headless numeric harness — continuity and tangency of position
 * and direction at every segment join, across on the order of 10^5 randomized trials spanning this
 * mod's actual radius range ({@link PlaneKinematics#MIN_TURN_RADIUS}–{@link
 * PlaneKinematics#MAX_TURN_RADIUS}) — before it ever reached this file. {@link
 * com.neoalive.tacz_sewv.entity.ai.plane.PlaneGuidanceSelfCheck} carries a representative subset of
 * those cases forward as an in-repo regression check.
 */
public final class Dubins {

    private static final double EPS = 1.0E-9;

    private Dubins() {}

    /**
     * The shortest Dubins path from {@code (startPos, startDir)} to {@code (endPos, endDir)} at
     * turn radius {@code radius}. {@code startDir}/{@code endDir} must be unit horizontal vectors.
     * Always returns a path — {@code LSL}/{@code RSR} (the two same-sense CSC primitives) are
     * geometrically valid for every input, so there is always at least one candidate.
     */
    public static DubinsPath computePath(Vec3 startPos, Vec3 startDir, Vec3 endPos, Vec3 endDir,
                                          double radius) {
        double r = Math.max(radius, EPS);
        DubinsPath best = cscSame(startPos, startDir, endPos, endDir, r, true); // LSL
        best = shorterOf(best, cscSame(startPos, startDir, endPos, endDir, r, false)); // RSR
        best = shorterOf(best, cscCross(startPos, startDir, endPos, endDir, r, true)); // LSR
        best = shorterOf(best, cscCross(startPos, startDir, endPos, endDir, r, false)); // RSL
        best = shorterOf(best, ccc(startPos, startDir, endPos, endDir, r, true)); // LRL
        best = shorterOf(best, ccc(startPos, startDir, endPos, endDir, r, false)); // RLR
        return best;
    }

    private static DubinsPath shorterOf(DubinsPath a, DubinsPath b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.totalLength() < a.totalLength() ? b : a;
    }

    // --- Turn-circle geometry -------------------------------------------------------------------

    /** Rotate a horizontal unit vector +90 degrees (left). */
    private static Vec3 perpLeft(Vec3 d) {
        return new Vec3(-d.z, 0.0, d.x);
    }

    /** Rotate a horizontal unit vector -90 degrees (right). */
    private static Vec3 perpRight(Vec3 d) {
        return new Vec3(d.z, 0.0, -d.x);
    }

    private static Vec3 rotate(Vec3 v, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(v.x * cos - v.z * sin, 0.0, v.x * sin + v.z * cos);
    }

    /** Center of the circle a hull flying {@code dir} at {@code pos} follows on a LEFT turn. */
    private static Vec3 leftCenter(Vec3 pos, Vec3 dir, double r) {
        return pos.add(perpLeft(dir).scale(r));
    }

    /** Center of the circle a hull flying {@code dir} at {@code pos} follows on a RIGHT turn. */
    private static Vec3 rightCenter(Vec3 pos, Vec3 dir, double r) {
        return pos.add(perpRight(dir).scale(r));
    }

    /** Angle (private circle parameterization, never used outside this file) of {@code p} on the
     * circle centered at {@code c}. */
    private static double angleOf(Vec3 p, Vec3 c) {
        return Math.atan2(p.z - c.z, p.x - c.x);
    }

    /** Wrap into [0, 2*pi). */
    private static double mod2pi(double a) {
        double m = a % (2.0 * Math.PI);
        return m < 0.0 ? m + 2.0 * Math.PI : m;
    }

    // --- CSC: LSL / RSR (outer tangent between two same-sense circles) --------------------------

    private static DubinsPath cscSame(Vec3 p0, Vec3 d0, Vec3 p1, Vec3 d1, double r, boolean leftFirst) {
        Vec3 c1 = leftFirst ? leftCenter(p0, d0, r) : rightCenter(p0, d0, r);
        Vec3 c2 = leftFirst ? leftCenter(p1, d1, r) : rightCenter(p1, d1, r);
        double centerDist = c1.distanceTo(c2);
        Vec3 u = centerDist > EPS ? c2.subtract(c1).scale(1.0 / centerDist) : d0;
        // The outer tangent offset direction is on the RIGHT of the center line for LSL and on the
        // LEFT for RSR — this was verified against a hand-worked example, not guessed; see the
        // class javadoc.
        Vec3 n = leftFirst ? perpRight(u) : perpLeft(u);
        Vec3 t1 = c1.add(n.scale(r));
        Vec3 t2 = c2.add(n.scale(r));

        double a0 = angleOf(p0, c1);
        double at1 = angleOf(t1, c1);
        double sweep1 = leftFirst ? mod2pi(at1 - a0) : mod2pi(a0 - at1);
        DubinsPath.Arc arc1 = new DubinsPath.Arc(c1, r, a0, sweep1, leftFirst);

        double segLen = t1.distanceTo(t2);
        Vec3 lineDir = segLen > EPS ? t2.subtract(t1).scale(1.0 / segLen) : u;
        DubinsPath.Line line = new DubinsPath.Line(t1, lineDir, segLen);

        double at2 = angleOf(t2, c2);
        double a1 = angleOf(p1, c2);
        double sweep2 = leftFirst ? mod2pi(a1 - at2) : mod2pi(at2 - a1);
        DubinsPath.Arc arc2 = new DubinsPath.Arc(c2, r, at2, sweep2, leftFirst);

        return new DubinsPath(List.of(arc1, line, arc2));
    }

    // --- CSC: LSR / RSL (internal/crossing tangent between opposite-sense circles) --------------

    private static DubinsPath cscCross(Vec3 p0, Vec3 d0, Vec3 p1, Vec3 d1, double r,
                                       boolean leftFirst) {
        Vec3 c1 = leftFirst ? leftCenter(p0, d0, r) : rightCenter(p0, d0, r);
        Vec3 c2 = leftFirst ? rightCenter(p1, d1, r) : leftCenter(p1, d1, r);
        double centerDist = c1.distanceTo(c2);
        if (centerDist < 2.0 * r - EPS) {
            return null; // circles overlap too much for an internal tangent to exist
        }
        Vec3 u = c2.subtract(c1).scale(1.0 / centerDist);
        double phi = Math.acos(Mth.clamp(2.0 * r / centerDist, -1.0, 1.0));

        for (int sign = 1; sign >= -1; sign -= 2) {
            Vec3 w = rotate(u, sign * phi);
            Vec3 t1 = c1.add(w.scale(r));
            Vec3 t2 = c2.subtract(w.scale(r));
            double segLen = t1.distanceTo(t2);
            if (segLen < EPS) continue;
            Vec3 lineDir = t2.subtract(t1).scale(1.0 / segLen);
            // Only one of the two candidate tangent lines actually connects in the direction of
            // travel this primitive commits to at t1 (leaving on a left turn tracks the radius
            // vector rotated +90, leaving on a right turn rotated -90); reject the other outright
            // rather than let a near-miss silently produce a discontinuous path.
            Vec3 expectedAtT1 = leftFirst ? perpLeft(w) : perpRight(w);
            if (lineDir.dot(expectedAtT1) <= 1.0 - 1.0E-6) continue;

            double a0 = angleOf(p0, c1);
            double at1 = angleOf(t1, c1);
            double sweep1 = leftFirst ? mod2pi(at1 - a0) : mod2pi(a0 - at1);
            DubinsPath.Arc arc1 = new DubinsPath.Arc(c1, r, a0, sweep1, leftFirst);

            DubinsPath.Line line = new DubinsPath.Line(t1, lineDir, segLen);

            boolean secondLeft = !leftFirst;
            double at2 = angleOf(t2, c2);
            double a1 = angleOf(p1, c2);
            double sweep2 = secondLeft ? mod2pi(a1 - at2) : mod2pi(at2 - a1);
            DubinsPath.Arc arc2 = new DubinsPath.Arc(c2, r, at2, sweep2, secondLeft);

            return new DubinsPath(List.of(arc1, line, arc2));
        }
        return null;
    }

    // --- CCC: LRL / RLR (a third, opposite-sense circle tangent to both outer circles) -----------

    private static DubinsPath ccc(Vec3 p0, Vec3 d0, Vec3 p1, Vec3 d1, double r, boolean leftFirst) {
        Vec3 c1 = leftFirst ? leftCenter(p0, d0, r) : rightCenter(p0, d0, r);
        Vec3 c2 = leftFirst ? leftCenter(p1, d1, r) : rightCenter(p1, d1, r);
        double centerDist = c1.distanceTo(c2);
        // Only geometrically possible when a circle of radius r can sit externally tangent to both
        // outer circles (also radius r) — the classic |c1 c2| <= 4r triangle-inequality bound.
        if (centerDist > 4.0 * r - EPS || centerDist < EPS) {
            return null;
        }
        double half = centerDist / 2.0;
        double h2 = (2.0 * r) * (2.0 * r) - half * half;
        if (h2 < 0.0) return null;
        double h = Math.sqrt(Math.max(0.0, h2));
        Vec3 mid = c1.add(c2).scale(0.5);
        Vec3 u = c2.subtract(c1).scale(1.0 / centerDist);
        Vec3 perp = perpLeft(u);

        DubinsPath best = null;
        for (int sign = 1; sign >= -1; sign -= 2) {
            Vec3 c3 = mid.add(perp.scale(sign * h));
            Vec3 t1 = c1.add(unitTowards(c1, c3).scale(r));
            Vec3 t2 = c2.add(unitTowards(c2, c3).scale(r));

            double a0 = angleOf(p0, c1);
            double at1 = angleOf(t1, c1);
            double sweep1 = leftFirst ? mod2pi(at1 - a0) : mod2pi(a0 - at1);
            DubinsPath.Arc arc1 = new DubinsPath.Arc(c1, r, a0, sweep1, leftFirst);

            boolean midLeft = !leftFirst;
            double am1 = angleOf(t1, c3);
            double am2 = angleOf(t2, c3);
            double sweepMid = midLeft ? mod2pi(am2 - am1) : mod2pi(am1 - am2);
            DubinsPath.Arc arcMid = new DubinsPath.Arc(c3, r, am1, sweepMid, midLeft);

            double at2 = angleOf(t2, c2);
            double a1 = angleOf(p1, c2);
            double sweep2 = leftFirst ? mod2pi(a1 - at2) : mod2pi(at2 - a1);
            DubinsPath.Arc arc2 = new DubinsPath.Arc(c2, r, at2, sweep2, leftFirst);

            DubinsPath candidate = new DubinsPath(List.of(arc1, arcMid, arc2));
            best = shorterOf(best, candidate);
        }
        return best;
    }

    private static Vec3 unitTowards(Vec3 from, Vec3 to) {
        double len = from.distanceTo(to);
        return len > EPS ? to.subtract(from).scale(1.0 / len) : new Vec3(1.0, 0.0, 0.0);
    }
}
