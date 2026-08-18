package com.neoalive.tacz_sewv.entity.ai.navigation;

/**
 * Optimal Reciprocal Collision Avoidance (van den Berg / Guy / Lin / Manocha 2009) for one hull
 * vs another. Terrain stays on the context maps / point sampling; this is only the moving-peer
 * half, and it is medium-agnostic — ground and ship each drive it with their own time horizon.
 *
 * <p>Supersedes plain RVO's {@code 2 vCand − vA − vB} velocity-averaging heuristic with the
 * paper's actual half-plane construction (§4.2): the nearest point on the boundary of the
 * (τ-truncated) velocity obstacle to the pair's relative velocity, split into the vector
 * {@code u} (boundary point minus relative velocity) — the outward normal falls out for free as
 * {@code u/|u|}, since {@code u} is by construction the minimal perpendicular displacement to a
 * convex boundary, which is parallel to the normal there. A candidate velocity is permitted iff
 * {@code (vCand − vSelf)·u ≥ 0.5|u|²}, the algebraic reduction of the paper's
 * {@code (v − (vOptA + 0.5u))·n ≥ 0} that avoids a normalize/sqrt in the hot path.
 *
 * <p>The boundary of the τ-truncated velocity obstacle has three pieces — the cutoff arc (where
 * the truncation itself lives) and two tangent-line legs — and the nearest point to a given
 * relative velocity is found by computing the nearest point on each of the three (each correctly
 * range-restricted to its valid portion) and keeping whichever is closest. This is a more robust
 * shape than picking a single region via a sign test up front: verified numerically against a
 * brute-force reference across colliding, clear, and near-boundary configurations before this was
 * trusted in a live sensor.
 */
public final class VehicleOrca {

    /** Disc is a hair larger than the AABB half-widths so a corner clip still counts. */
    public static final double RADIUS_PAD = 1.1;

    /** Below this a vector is treated as zero for normalization purposes. */
    private static final double EPS = 1.0E-9;

    private VehicleOrca() {}

    /** Combined disc radius for two hulls. */
    public static double radius(double halfA, double halfB) {
        return (halfA + halfB) * RADIUS_PAD;
    }

    /** One hull to steer around: position, current velocity, half-width, entity id. The id is
     * carried only so a caller can break the exact-head-on tie deterministically and
     * reciprocally — see {@link #halfPlane}'s {@code sideBias} parameter. */
    public record Peer(int id, double x, double z, double vx, double vz, double half) {}

    /** The peer-only half of the ORCA construction: the vector from the pair's relative velocity
     * to the nearest point on the boundary of the velocity obstacle. Independent of the candidate
     * velocity being tested, so a caller may compute this once per peer per tick and reuse it
     * across several candidate headings. */
    public record HalfPlane(double ux, double uz) {
        public double lenSq() {
            return ux * ux + uz * uz;
        }
    }

    /**
     * Section 4.2 of "Reciprocal n-body Collision Avoidance" (van den Berg / Guy / Lin / Manocha
     * 2009). {@code px,pz} is the peer's position relative to self ({@code peerPos − selfPos});
     * {@code aVel}/{@code bVel} are current velocities — the paper's own recommended choice of
     * "optimization velocity" (§5.2), already what plain RVO used here. {@code radius} is the
     * combined disc (see {@link #radius}). {@code tau} is the caller's own time horizon — NOT a
     * shared constant, ground and ship need different values since a ship cannot react at the
     * last second the way a tank can. {@code sideBias} resolves the one real degeneracy in this
     * construction: a perfectly symmetric head-on approach makes the two tangent-leg candidates
     * exactly equidistant, with no basis to prefer one over the other. A caller passing
     * {@code TIE_EPS * Integer.signum(selfId − peerId)} gets a deterministic, reciprocal
     * (not identical) split between the two hulls — the same shape {@code StalemateBreaker}'s
     * entity-id-parity flank pick already uses elsewhere in this codebase, here pushed through a
     * pure-math seam instead.
     *
     * <p>Only handles the case where the pair is not already overlapping — see
     * {@link #overlappingAndClosing} for that edge case, deliberately kept separate and simple
     * (the paper's own densely-packed-conditions edge case, §5.3, not worth folding in here).
     */
    public static HalfPlane halfPlane(double px, double pz,
                                       double aVelX, double aVelZ,
                                       double bVelX, double bVelZ,
                                       double radius, double tau, double sideBias) {
        double relVelX = aVelX - bVelX;
        double relVelZ = aVelZ - bVelZ;
        double distSq = px * px + pz * pz;
        double radiusSq = radius * radius;
        double leg = Math.sqrt(Math.max(0.0, distSq - radiusSq));

        // The two tangent lines from the origin to the disc of `radius` centered at (px,pz),
        // as unit directions. Derived directly from the right-triangle (hypotenuse distSq,
        // legs `leg` and `radius`) formed by the origin, the tangent point and the disc center.
        double leftX = (px * leg - pz * radius) / distSq;
        double leftZ = (px * radius + pz * leg) / distSq;
        double rightX = (px * leg + pz * radius) / distSq;
        double rightZ = (pz * leg - px * radius) / distSq;
        // Where each leg meets the cutoff arc — the leg is only a boundary beyond this point.
        double junctionT = leg / tau;

        double leftT = Math.max(junctionT, relVelX * leftX + relVelZ * leftZ);
        double leftPX = leftX * leftT, leftPZ = leftZ * leftT;
        double leftD = Math.hypot(leftPX - relVelX, leftPZ - relVelZ) - sideBias;

        double rightT = Math.max(junctionT, relVelX * rightX + relVelZ * rightZ);
        double rightPX = rightX * rightT, rightPZ = rightZ * rightT;
        double rightD = Math.hypot(rightPX - relVelX, rightPZ - relVelZ) + sideBias;

        double bestX, bestZ, bestD;
        if (leftD <= rightD) {
            bestX = leftPX; bestZ = leftPZ; bestD = leftD;
        } else {
            bestX = rightPX; bestZ = rightPZ; bestD = rightD;
        }

        // The cutoff arc: nearest point on the full circle (center p/tau, radius/tau) to the
        // relative velocity, valid only on the near/origin-facing half (the far half of that
        // circle is interior to the velocity obstacle, not part of its boundary).
        double cx = px / tau, cz = pz / tau;
        double wx = relVelX - cx, wz = relVelZ - cz;
        double wLen = Math.hypot(wx, wz);
        if (wLen > EPS) {
            double scale = (radius / tau) / wLen;
            double cutX = cx + scale * wx, cutZ = cz + scale * wz;
            double originFacing = (cutX - cx) * -cx + (cutZ - cz) * -cz;
            if (originFacing >= 0.0) {
                double cutD = Math.hypot(cutX - relVelX, cutZ - relVelZ);
                if (cutD < bestD) {
                    bestX = cutX; bestZ = cutZ;
                }
            }
        }

        return new HalfPlane(bestX - relVelX, bestZ - relVelZ);
    }

    /** {@code (vCand − vSelf)·u ≥ 0.5|u|²} — the half-plane admissibility test. */
    public static boolean permitted(double vCandX, double vCandZ,
                                     double aVelX, double aVelZ, HalfPlane hp) {
        return margin(vCandX, vCandZ, aVelX, aVelZ, hp) >= 0.0;
    }

    /** Signed distance (blocks/tick) from the candidate to the half-plane boundary: non-negative
     * when permitted, otherwise the penetration depth into the forbidden region (negated). */
    public static double margin(double vCandX, double vCandZ,
                                 double aVelX, double aVelZ, HalfPlane hp) {
        double lenSq = hp.lenSq();
        if (lenSq < EPS) return Double.POSITIVE_INFINITY; // no restriction from this peer
        double dot = (vCandX - aVelX) * hp.ux() + (vCandZ - aVelZ) * hp.uz();
        return dot - 0.5 * lenSq;
    }

    /**
     * Already-overlapping edge case: true iff the pair is both overlapping and closing under the
     * candidate's implied RVO relative velocity ({@code 2 vCand − vA − vB}, plain RVO's own
     * heuristic — this branch is the paper's §5.3 densely-packed case, not the main construction,
     * and is deliberately not rebuilt with the half-plane geometry above).
     */
    public static boolean overlappingAndClosing(double px, double pz,
                                                 double vCandX, double vCandZ,
                                                 double aVelX, double aVelZ,
                                                 double bVelX, double bVelZ,
                                                 double radius) {
        if (px * px + pz * pz > radius * radius) return false;
        double vx = 2.0 * vCandX - aVelX - bVelX;
        double vz = 2.0 * vCandZ - aVelZ - bVelZ;
        return px * vx + pz * vz > 0.0;
    }
}
