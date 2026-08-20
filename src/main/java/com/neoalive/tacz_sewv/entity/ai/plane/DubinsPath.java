package com.neoalive.tacz_sewv.entity.ai.plane;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A computed Dubins path: an ordered list of arc/line segments plus their total arc length. Pure
 * data plus pure sampling, in the same spirit as {@link PlaneNav} — no world, no entity, no state.
 *
 * <p>Segments carry {@link Vec3} positions and directions throughout, never a bare yaw or bearing —
 * see {@link Dubins} for why that matters here specifically.
 *
 * <p><b>Everything here is horizontal.</b> Every {@link Vec3} this class returns has {@code y = 0},
 * and any {@code y} on a position passed in (a real aircraft altitude, for instance) is ignored by
 * every distance/deviation computation — the same "cross-track is a purely horizontal concept" the
 * rest of this mod's approach geometry ({@code PlaneNav.crossTrack}) already assumes. Altitude is
 * always the caller's business (see {@code DrivePlaneGoal}'s own {@code transitY}/glideslope
 * handling); mixing it into a 3D distance here would silently report a plane thousands of blocks
 * "off" its own turn-in arc every time its altitude simply differs from an assumed reference.
 */
public final class DubinsPath {

    private static final double EPS = 1.0E-9;

    private static double horizDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** One piece of the path: a straight run or a constant-radius turn. */
    public sealed interface Segment permits Arc, Line {
        double length();

        /** Position at arc-length {@code s} into this segment, {@code s} clamped to [0, length()]. */
        Vec3 pointAt(double s);

        /** Unit direction of travel at arc-length {@code s} into this segment. */
        Vec3 dirAt(double s);

        /** Perpendicular distance from {@code pos} to the nearest point on this segment. */
        double distanceTo(Vec3 pos);
    }

    /** A straight run from {@code start} along unit {@code dir} for {@code length} blocks. */
    public record Line(Vec3 start, Vec3 dir, double length) implements Segment {
        @Override
        public Vec3 pointAt(double s) {
            double c = Mth.clamp(s, 0.0, this.length);
            return new Vec3(this.start.x + this.dir.x * c, 0.0, this.start.z + this.dir.z * c);
        }

        @Override
        public Vec3 dirAt(double s) {
            return this.dir;
        }

        @Override
        public double distanceTo(Vec3 pos) {
            double dx = pos.x - this.start.x;
            double dz = pos.z - this.start.z;
            double along = Mth.clamp(dx * this.dir.x + dz * this.dir.z, 0.0, this.length);
            return horizDist(pos, pointAt(along));
        }
    }

    /**
     * A constant-radius turn about {@code center}, starting at angle {@code startAngle} (standard
     * math convention: position = center + radius*(cos, 0, sin), purely internal — never compared
     * against a yaw or a bearing outside this class) and sweeping {@code sweepMag} radians (always
     * {@code >= 0}) in the direction given by {@code ccw}.
     *
     * <p>The turn sense is carried as an explicit flag rather than inferred from the sign of a
     * signed sweep on purpose: a legitimately zero-length arc (the two circles already tangent at
     * the start point) has no meaningful sign to infer from — {@code -0.0 >= 0.0} is {@code true} in
     * IEEE 754, so a sign-inferred sense silently picks the wrong rotational formula exactly on that
     * boundary. This was caught by a headless numeric prototype before it ever reached Java.
     */
    public record Arc(Vec3 center, double radius, double startAngle, double sweepMag, boolean ccw)
            implements Segment {
        @Override
        public double length() {
            return this.radius * this.sweepMag;
        }

        private double angleAt(double s) {
            double c = Mth.clamp(s, 0.0, length());
            double frac = length() < EPS ? 0.0 : c / this.radius;
            return this.startAngle + (this.ccw ? frac : -frac);
        }

        @Override
        public Vec3 pointAt(double s) {
            double a = angleAt(s);
            return new Vec3(this.center.x + this.radius * Math.cos(a), 0.0,
                    this.center.z + this.radius * Math.sin(a));
        }

        @Override
        public Vec3 dirAt(double s) {
            double a = angleAt(s);
            // Tangent to the circle: CCW travel direction is the radius vector rotated +90 degrees,
            // CW travel direction is the radius vector rotated -90 degrees.
            return this.ccw ? new Vec3(-Math.sin(a), 0.0, Math.cos(a))
                    : new Vec3(Math.sin(a), 0.0, -Math.cos(a));
        }

        @Override
        public double distanceTo(Vec3 pos) {
            double dx = pos.x - this.center.x;
            double dz = pos.z - this.center.z;
            double distToCenter = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dz, dx);
            double relDeg = Mth.wrapDegrees(Math.toDegrees(angle - this.startAngle));
            double sweepDeg = Math.toDegrees(this.sweepMag);
            boolean inSpan = this.ccw
                    ? relDeg >= -1.0E-6 && relDeg <= sweepDeg + 1.0E-6
                    : relDeg <= 1.0E-6 && relDeg >= -sweepDeg - 1.0E-6;
            if (inSpan) {
                return Math.abs(distToCenter - this.radius);
            }
            return Math.min(horizDist(pointAt(0.0), pos), horizDist(pointAt(length()), pos));
        }
    }

    private final List<Segment> segments;
    private final double totalLength;

    public DubinsPath(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        double sum = 0.0;
        for (Segment s : this.segments) sum += s.length();
        this.totalLength = sum;
    }

    public List<Segment> segments() {
        return this.segments;
    }

    public double totalLength() {
        return this.totalLength;
    }

    /** Position at arc-length {@code s} along the whole path, clamped to [0, totalLength()]. */
    public Vec3 pointAt(double s) {
        return walk(s).segment.pointAt(walk(s).local);
    }

    /** Unit direction of travel at arc-length {@code s} along the whole path. */
    public Vec3 dirAt(double s) {
        return walk(s).segment.dirAt(walk(s).local);
    }

    private record Cursor(Segment segment, double local) {}

    private Cursor walk(double s) {
        double remaining = Mth.clamp(s, 0.0, this.totalLength);
        for (int i = 0; i < this.segments.size(); i++) {
            Segment seg = this.segments.get(i);
            boolean last = i == this.segments.size() - 1;
            if (remaining <= seg.length() || last) {
                return new Cursor(seg, remaining);
            }
            remaining -= seg.length();
        }
        // Unreachable for a non-empty path (a Dubins path always has at least one segment).
        Segment first = this.segments.get(0);
        return new Cursor(first, 0.0);
    }

    /**
     * Minimum perpendicular distance from {@code pos} to any point on the path. Used only for the
     * drift check — progress along the path is tracked separately by arc-length accumulation, never
     * by re-projecting onto the path every tick.
     */
    public double deviation(Vec3 pos) {
        double min = Double.MAX_VALUE;
        for (Segment seg : this.segments) {
            min = Math.min(min, seg.distanceTo(pos));
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }
}
