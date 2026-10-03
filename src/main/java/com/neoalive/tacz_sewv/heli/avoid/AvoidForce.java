package com.neoalive.tacz_sewv.heli.avoid;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Reactive barrier (plan section 4.9): a world-frame repulsive force at the centre of gravity,
 * added to the physics force accumulator every sub-step. Never resolved into the body frame;
 * produces no moment.
 *
 * <p>Per obstacle j, with d its clearance, n its unit normal (obstacle to hull) and v_close the
 * closing speed: F_j = m s(clamp((r_b - d)/r_b)) (a_rep + c_r v_close) n, then |sum| <= m a_rep.
 * The smoothstep averages 1/2 over the band, so stopping a hull the controller pushes in at a_c
 * needs r_b (a_rep/2 - a_c) >= v^2/2, i.e. r_b(v) = v^2/(a_rep - 2 a_c) + v dt + r0. That is finite
 * only if a_rep > 2 a_c; we use a_rep = 4 a_c. r0 = half-width + 1.
 *
 * <p><b>Band rule</b> (amends review item R2; see the phase-1 report). Each obstacle within its
 * detect radius r_detect = r_b(v_max) carries a band, updated at 20 Hz:
 * <ul>
 * <li><b>Outside the band</b> (d >= r_b): the band follows r_b(v_close) both ways. The force is
 *     zero there, so this changes nothing discontinuously; a hull flying parallel to a wall or
 *     level over the ground has v_close = 0 and a band of r0, so the barrier stays silent.</li>
 * <li><b>Inside the band</b> (d < r_b): the band only ratchets up, never down, so the barrier
 *     never weakens while it is acting. A hull entering the band edge at speed v therefore meets
 *     at least the band the energy argument sized for v, whatever the controller does next.</li>
 * <li>Dropped beyond r_detect + margin.</li>
 * </ul>
 * R2 as first written fixed the band from the closing speed at arm time and released at
 * r_b + margin, with a cooling rule against re-arming. The phase-1 self-check showed that a slow
 * arm released on the very next update (r_b(v) + margin < r_detect whenever v < v_max), the
 * cooling rule then forbade re-arming, and the controller accelerated the hull through the wall.
 * The force is C1 at the band edge (smoothstep), so there is no chatter for a cooling rule to
 * suppress; the ratchet removes the oscillation R2 guarded against (a band shrinking under the
 * barrier's own braking).
 *
 * <p>The ground has its own band (vertical closing speed, r_detect from the maximum descent),
 * independent of lateral obstacles.
 *
 * <p><b>Not airtight, by design.</b> The guarantee covers closing speeds up to the speed the
 * detect radius was sized for and controller pushes up to a_c. A faster closure (a falling hull,
 * an obstacle moving at the hull) can penetrate. The tactical bias is the primary avoidance; this
 * barrier is the fallback for when the bias has already failed. Do not remove or weaken the bias
 * on the assumption that the barrier is a complete guarantee.
 *
 * <p>The band map is only looked up, never iterated to compute forces, so its order cannot affect
 * results.
 */
public final class AvoidForce {

    private final double aRep, aC, damping, r0, margin, tickSeconds;
    private final double halfWidth, height;
    private final double rbMaxLateral, rbMaxGround;
    private final Map<Long, Double> bands = new HashMap<>();

    /**
     * @param aC       the controller's maximum acceleration authority
     * @param vMaxH    maximum horizontal speed (sizes the lateral detect radius)
     * @param vMaxDown maximum descent speed (sizes the ground detect radius)
     * @param damping  c_r, 1/s
     */
    public AvoidForce(double aC, double vMaxH, double vMaxDown, double damping, double halfWidth, double height,
                      double margin, double tickSeconds) {
        this.aC = aC;
        this.aRep = 4.0 * aC;
        this.damping = damping;
        this.halfWidth = halfWidth;
        this.height = height;
        this.r0 = halfWidth + 1.0;
        this.margin = margin;
        this.tickSeconds = tickSeconds;
        this.rbMaxLateral = band(vMaxH, Double.POSITIVE_INFINITY);
        this.rbMaxGround = band(vMaxDown, Double.POSITIVE_INFINITY);
    }

    public double maxAcceleration() {
        return aRep;
    }

    /** Lateral detect radius: the band at the speed limit. Obstacles beyond it are ignored. */
    public double detectRadius() {
        return rbMaxLateral;
    }

    /** r_b(v) = v^2/(a_rep - 2 a_c) + v dt + r0, at most {@code cap}. */
    public double band(double v, double cap) {
        double v0 = Math.max(0.0, v);
        return Math.min(cap, v0 * v0 / (aRep - 2.0 * aC) + v0 * tickSeconds + r0);
    }

    /** Current band for {@code key}, or NaN when the obstacle is not tracked. */
    public double bandOf(long key) {
        Double rb = bands.get(key);
        return rb == null ? Double.NaN : rb;
    }

    /** Keys currently tracked, for the world probe: these must always stay in the obstacle set. */
    public Set<Long> trackedKeys() {
        return Set.copyOf(bands.keySet());
    }

    /** 20 Hz: update every obstacle's band against the new obstacle set. */
    public void update(ObstacleSet set, Vector3dc p, Vector3dc v) {
        Set<Long> seen = new HashSet<>();
        for (ObstacleSet.Box b : set.boxes()) {
            seen.add(b.key());
            double[] g = gap(b, p);
            track(b.key(), g[0], closing(g, v, b.vx(), b.vy(), b.vz()), rbMaxLateral);
        }
        if (!Double.isNaN(set.groundY())) {
            seen.add(ObstacleSet.GROUND);
            track(ObstacleSet.GROUND, p.y() - set.groundY(), Math.max(0.0, -v.y()), rbMaxGround);
        }
        bands.keySet().removeIf(k -> !seen.contains(k));
    }

    private void track(long key, double d, double vClose, double rDetect) {
        Double prev = bands.get(key);
        if (d > rDetect + margin) {
            bands.remove(key);
            return;
        }
        if (prev == null && d > rDetect) return;
        double want = band(vClose, rDetect);
        bands.put(key, prev == null || d >= prev ? want : Math.max(prev, want));
    }

    /** Per sub-step: the barrier force on a hull of mass {@code m} at {@code p} moving at {@code v}. */
    public Vector3d force(ObstacleSet set, Vector3dc p, Vector3dc v, double m, Vector3d out) {
        out.zero();
        if (bands.isEmpty()) return out;
        for (ObstacleSet.Box b : set.boxes()) {
            Double rb = bands.get(b.key());
            if (rb == null) continue;
            double[] g = gap(b, p);
            push(out, g[0], rb, closing(g, v, b.vx(), b.vy(), b.vz()), g[1], g[2], g[3], m);
        }
        Double rbg = bands.get(ObstacleSet.GROUND);
        if (rbg != null && !Double.isNaN(set.groundY())) {
            push(out, p.y() - set.groundY(), rbg, Math.max(0.0, -v.y()), 0.0, 1.0, 0.0, m);
        }
        double len = out.length(), cap = m * aRep;
        if (len > cap) out.mul(cap / len);
        return out;
    }

    private void push(Vector3d out, double d, double rb, double vClose, double nx, double ny, double nz, double m) {
        double depth = (rb - d) / rb;
        if (depth <= 0.0) return;
        double x = Math.min(1.0, depth);
        double s = x * x * (3.0 - 2.0 * x);
        double mag = m * s * (aRep + damping * vClose);
        out.add(nx * mag, ny * mag, nz * mag);
    }

    /** Clearance between the hull box at {@code p} and {@code b}: {d, nx, ny, nz}, n from obstacle to hull. */
    private double[] gap(ObstacleSet.Box b, Vector3dc p) {
        double hx0 = p.x() - halfWidth, hx1 = p.x() + halfWidth;
        double hy0 = p.y(), hy1 = p.y() + height;
        double hz0 = p.z() - halfWidth, hz1 = p.z() + halfWidth;
        double gx = hx0 > b.maxX() ? hx0 - b.maxX() : (hx1 < b.minX() ? hx1 - b.minX() : 0.0);
        double gy = hy0 > b.maxY() ? hy0 - b.maxY() : (hy1 < b.minY() ? hy1 - b.minY() : 0.0);
        double gz = hz0 > b.maxZ() ? hz0 - b.maxZ() : (hz1 < b.minZ() ? hz1 - b.minZ() : 0.0);
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (d > 1.0E-9) return new double[] {d, gx / d, gy / d, gz / d};
        // Overlapping: push out from the obstacle's centre.
        double cx = p.x() - (b.minX() + b.maxX()) / 2.0;
        double cy = p.y() + height / 2.0 - (b.minY() + b.maxY()) / 2.0;
        double cz = p.z() - (b.minZ() + b.maxZ()) / 2.0;
        double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
        return cl > 1.0E-9 ? new double[] {0.0, cx / cl, cy / cl, cz / cl} : new double[] {0.0, 0.0, 1.0, 0.0};
    }

    private static double closing(double[] g, Vector3dc v, double ox, double oy, double oz) {
        return Math.max(0.0, -((v.x() - ox) * g[1] + (v.y() - oy) * g[2] + (v.z() - oz) * g[3]));
    }
}
