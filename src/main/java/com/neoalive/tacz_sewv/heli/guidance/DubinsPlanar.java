package com.neoalive.tacz_sewv.heli.guidance;

import java.util.List;

/**
 * Shortest Dubins path between two oriented points in the horizontal (x, z) plane at a fixed
 * turn radius: the six words LSL, RSR, LSR, RSL, RLR, LRL. A pure, StrictMath port of the
 * construction in {@code entity.ai.plane.Dubins} (turn-circle centres plus explicit tangents),
 * restated in Minecraft's handedness.
 *
 * <p><b>Handedness.</b> Seen from above, Minecraft's x-east / z-south plane is mirrored against
 * the usual maths picture. Here "left" means a positive rotation about +Y (the side
 * {@code n_L(d) = (d.z, -d.x)} points to: east when heading south), which is the side the hull's
 * port (+X body) axis faces. The plane code's {@code perpLeft} is this file's right. Its paths
 * equal this file's for mirrored (x -> -x) inputs, which is how the self-check cross-validates
 * the port.
 *
 * <p>Arcs are parameterised as {@code centre + r (sin a, cos a)}: increasing {@code a} is a left
 * turn. A turn's sense is an explicit flag, never inferred from the sign of a sweep: a zero-length
 * arc has no meaningful sign.
 */
public final class DubinsPlanar {

    private static final double EPS = 1.0E-9;
    private static final double TWO_PI = 2.0 * Math.PI;

    /** One piece of a path. {@code sample} writes x, z, dir.x, dir.z, signed curvature (left positive). */
    public sealed interface Segment permits Arc, Line {
        double length();

        void sample(double s, double[] out);
    }

    /** Straight run from (x, z) along unit (dx, dz). */
    public record Line(double x, double z, double dx, double dz, double length) implements Segment {
        @Override
        public void sample(double s, double[] out) {
            double c = Math.max(0.0, Math.min(length, s));
            out[0] = x + dx * c;
            out[1] = z + dz * c;
            out[2] = dx;
            out[3] = dz;
            out[4] = 0.0;
        }
    }

    /** Constant-radius turn about (cx, cz) from angle {@code a0}, sweeping {@code sweep >= 0} radians. */
    public record Arc(double cx, double cz, double r, double a0, double sweep, boolean left) implements Segment {
        @Override
        public double length() {
            return r * sweep;
        }

        @Override
        public void sample(double s, double[] out) {
            double c = Math.max(0.0, Math.min(length(), s));
            double a = left ? a0 + c / r : a0 - c / r;
            double sin = StrictMath.sin(a), cos = StrictMath.cos(a);
            out[0] = cx + r * sin;
            out[1] = cz + r * cos;
            out[2] = left ? cos : -cos;
            out[3] = left ? -sin : sin;
            out[4] = left ? 1.0 / r : -1.0 / r;
        }
    }

    /** A computed path: its segments, total length and word (e.g. "LSR"). */
    public record Path(List<Segment> segments, double length, String word) {
        /** x, z, dir.x, dir.z, curvature at arc length {@code s} (clamped). */
        public void sample(double s, double[] out) {
            double rem = Math.max(0.0, Math.min(length, s));
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                if (rem <= seg.length() || i == segments.size() - 1) {
                    seg.sample(rem, out);
                    return;
                }
                rem -= seg.length();
            }
        }
    }

    private DubinsPlanar() {}

    /**
     * Shortest path from (x0, z0) heading (dx0, dz0) to (x1, z1) heading (dx1, dz1), unit
     * horizontal headings, turn radius {@code r}. Always returns a path (LSL and RSR exist for
     * every input). Ties keep the first word in LSL, RSR, LSR, RSL, RLR, LRL order.
     */
    public static Path shortest(double x0, double z0, double dx0, double dz0,
                                double x1, double z1, double dx1, double dz1, double r) {
        r = Math.max(r, EPS);
        Path best = csc(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, true);
        best = shorter(best, csc(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, false));
        best = shorter(best, cross(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, true));
        best = shorter(best, cross(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, false));
        best = shorter(best, ccc(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, false));
        best = shorter(best, ccc(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r, true));
        return best;
    }

    private static Path shorter(Path a, Path b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.length() < a.length() ? b : a;
    }

    // --- Geometry helpers -------------------------------------------------------------------------

    private static double angleOf(double px, double pz, double cx, double cz) {
        return StrictMath.atan2(px - cx, pz - cz);
    }

    private static double mod2pi(double a) {
        double m = a % TWO_PI;
        return m < 0.0 ? m + TWO_PI : m;
    }

    /** Arc from point (sx, sz) to point (ex, ez), both on the circle about (cx, cz). */
    private static Arc arc(double cx, double cz, double r, double sx, double sz, double ex, double ez, boolean left) {
        double a0 = angleOf(sx, sz, cx, cz), a1 = angleOf(ex, ez, cx, cz);
        return new Arc(cx, cz, r, a0, left ? mod2pi(a1 - a0) : mod2pi(a0 - a1), left);
    }

    private static Line line(double x0, double z0, double x1, double z1, double fallbackDx, double fallbackDz) {
        double dx = x1 - x0, dz = z1 - z0, len = StrictMath.sqrt(dx * dx + dz * dz);
        return len > EPS ? new Line(x0, z0, dx / len, dz / len, len) : new Line(x0, z0, fallbackDx, fallbackDz, 0.0);
    }

    private static Path path(String word, Segment a, Segment b, Segment c) {
        return new Path(List.of(a, b, c), a.length() + b.length() + c.length(), word);
    }

    // --- CSC, same sense: LSL / RSR (outer tangent) ---------------------------------------------

    private static Path csc(double x0, double z0, double dx0, double dz0,
                            double x1, double z1, double dx1, double dz1, double r, boolean left) {
        double s = left ? 1.0 : -1.0;
        double c1x = x0 + s * r * dz0, c1z = z0 - s * r * dx0; // centre = p + s r n_L(d)
        double c2x = x1 + s * r * dz1, c2z = z1 - s * r * dx1;
        double ux = c2x - c1x, uz = c2z - c1z, d = StrictMath.sqrt(ux * ux + uz * uz);
        if (d > EPS) {
            ux /= d;
            uz /= d;
        } else {
            ux = dx0;
            uz = dz0;
        }
        // Departure radial whose travel direction equals u: n_R(u) for a left turn, n_L(u) for a right.
        double radX = left ? -uz : uz, radZ = left ? ux : -ux;
        double t1x = c1x + r * radX, t1z = c1z + r * radZ, t2x = c2x + r * radX, t2z = c2z + r * radZ;
        return path(left ? "LSL" : "RSR",
                arc(c1x, c1z, r, x0, z0, t1x, t1z, left),
                line(t1x, t1z, t2x, t2z, ux, uz),
                arc(c2x, c2z, r, t2x, t2z, x1, z1, left));
    }

    // --- CSC, opposite sense: LSR / RSL (crossing tangent) --------------------------------------

    private static Path cross(double x0, double z0, double dx0, double dz0,
                              double x1, double z1, double dx1, double dz1, double r, boolean leftFirst) {
        double s1 = leftFirst ? 1.0 : -1.0;
        double c1x = x0 + s1 * r * dz0, c1z = z0 - s1 * r * dx0;
        double c2x = x1 - s1 * r * dz1, c2z = z1 + s1 * r * dx1;
        double ux = c2x - c1x, uz = c2z - c1z, d = StrictMath.sqrt(ux * ux + uz * uz);
        if (d < 2.0 * r - EPS) return null; // circles overlap: no crossing tangent
        ux /= d;
        uz /= d;
        double phi = StrictMath.acos(Math.min(1.0, 2.0 * r / d));
        for (int sign = 1; sign >= -1; sign -= 2) {
            double c = StrictMath.cos(sign * phi), sn = StrictMath.sin(sign * phi);
            double wx = ux * c + uz * sn, wz = -ux * sn + uz * c; // u rotated about +Y
            double t1x = c1x + r * wx, t1z = c1z + r * wz, t2x = c2x - r * wx, t2z = c2z - r * wz;
            Line ln = line(t1x, t1z, t2x, t2z, ux, uz);
            if (ln.length() < EPS) continue;
            // Leaving a left turn travels along n_L(radial); a right turn along -n_L(radial).
            double ex = s1 * wz, ez = -s1 * wx;
            if (ln.dx() * ex + ln.dz() * ez <= 1.0 - 1.0E-6) continue;
            return path(leftFirst ? "LSR" : "RSL",
                    arc(c1x, c1z, r, x0, z0, t1x, t1z, leftFirst), ln,
                    arc(c2x, c2z, r, t2x, t2z, x1, z1, !leftFirst));
        }
        return null;
    }

    // --- CCC: LRL / RLR ----------------------------------------------------------------------------

    private static Path ccc(double x0, double z0, double dx0, double dz0,
                            double x1, double z1, double dx1, double dz1, double r, boolean leftOuter) {
        double s = leftOuter ? 1.0 : -1.0;
        double c1x = x0 + s * r * dz0, c1z = z0 - s * r * dx0;
        double c2x = x1 + s * r * dz1, c2z = z1 - s * r * dx1;
        double ux = c2x - c1x, uz = c2z - c1z, d = StrictMath.sqrt(ux * ux + uz * uz);
        if (d > 4.0 * r - EPS || d < EPS) return null;
        ux /= d;
        uz /= d;
        double h = StrictMath.sqrt(Math.max(0.0, 4.0 * r * r - d * d / 4.0));
        double mx = (c1x + c2x) / 2.0, mz = (c1z + c2z) / 2.0;
        Path best = null;
        for (int sign = 1; sign >= -1; sign -= 2) {
            double c3x = mx + sign * h * uz, c3z = mz - sign * h * ux; // mid + sign h n_L(u)
            double a1x = c3x - c1x, a1z = c3z - c1z, a1 = StrictMath.sqrt(a1x * a1x + a1z * a1z);
            double a2x = c3x - c2x, a2z = c3z - c2z, a2 = StrictMath.sqrt(a2x * a2x + a2z * a2z);
            double t1x = c1x + r * a1x / a1, t1z = c1z + r * a1z / a1;
            double t2x = c2x + r * a2x / a2, t2z = c2z + r * a2z / a2;
            Path p = path(leftOuter ? "LRL" : "RLR",
                    arc(c1x, c1z, r, x0, z0, t1x, t1z, leftOuter),
                    arc(c3x, c3z, r, t1x, t1z, t2x, t2z, !leftOuter),
                    arc(c2x, c2z, r, t2x, t2z, x1, z1, leftOuter));
            best = shorter(best, p);
        }
        return best;
    }
}
