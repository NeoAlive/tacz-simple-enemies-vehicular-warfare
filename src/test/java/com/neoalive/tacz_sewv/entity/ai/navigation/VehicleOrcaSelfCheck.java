package com.neoalive.tacz_sewv.entity.ai.navigation;

/**
 * Self-check for the ORCA half-plane and time-to-collision geometry. Run via
 * {@code ./gradlew selfCheck} (selfCheckOrca).
 *
 * <p>The three-region tangent/cutoff-circle construction in {@link VehicleOrca#halfPlane} was
 * verified against an independent 2D-grid brute-force reference (not shipped — a throwaway
 * scratch check) before being trusted here; these cases pin down the properties that matter for
 * this codebase's actual use (a discrete candidate-heading fan, not a continuous LP), not just
 * that the geometry is internally self-consistent.
 *
 * <p>{@link VehicleOrca#margin}/{@link #wellSeparatedParallelIsOnlyMildlyRestricted} and
 * {@link VehicleOrca#imminent}/{@link #imminentDivergesUnlikeMargin} exist for DIFFERENT jobs and
 * must not be confused: margin measures half-plane penetration, a quantity that stays bounded
 * (roughly ±0.3 blocks/tick even at near-contact range) rather than diverging as impact
 * approaches — verified directly that a stationary wreck 6 blocks away (barely more than a tank
 * pair's ~5-block combined radius) never crossed a 0.2 hard-margin threshold at all. Only
 * time-to-collision diverges toward zero as contact nears, which is why it — not margin — is the
 * sole hard veto in both {@code GroundTerrainSensor} and {@code ShipTerrainSensor}; margin is
 * graded PREFERENCE only (ground's skirt), never a hard block.
 */
public final class VehicleOrcaSelfCheck {

    private static final double R = VehicleOrca.radius(2.0, 2.0); // 4.4
    private static final double TAU = 30.0;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        radiusUnchanged();
        overlapClosingIsHard();
        overlapSeparatingIsClear();
        headOnContinuingIsNotPermitted();
        headOnBoundaryIsExactlyPermitted();
        headOnEvasiveSideIsPermitted();
        headOnWrongSideIsNotPermitted();
        reciprocityHoldsUnderRoleSwap();
        sideBiasBreaksExactTieReciprocally();
        wellSeparatedParallelIsOnlyMildlyRestricted();
        zeroHalfPlaneMeansNoRestriction();
        imminentFiresForCloseStationaryWreck();
        imminentStaysClearForMundaneParallelTraffic();
        imminentDivergesUnlikeMargin();

        System.out.println("ORCA geometry self-check: OK");
    }

    private static void radiusUnchanged() {
        assertClose(4.4, VehicleOrca.radius(2.0, 2.0), "combined radius");
    }

    /** Bodies already touching and still closing under the candidate's implied RVO velocity. */
    private static void overlapClosingIsHard() {
        boolean closing = VehicleOrca.overlappingAndClosing(0, 2, 0, 0.4, 0, 0.4, 0, -0.4, R);
        assert closing : "overlapping and closing must be hard";
    }

    private static void overlapSeparatingIsClear() {
        boolean separating = VehicleOrca.overlappingAndClosing(0, 2, 0, -0.4, 0, -0.4, 0, 0.4, R);
        assert !separating : "overlapping but separating must not be hard";
    }

    /**
     * Structural property of the construction, not scenario-specific: continuing at exactly the
     * current (optimization) velocity is on the "must still contribute your half" side whenever a
     * peer imposes any restriction at all — {@code margin(vOptA,...) = -0.5|u|^2 < 0} for any
     * nonzero {@code u}. This is WHY neither sensor uses raw {@code permitted()} (margin>=0) as a
     * hard gate — see the class doc.
     */
    private static void headOnContinuingIsNotPermitted() {
        VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, 0.0);
        assert !VehicleOrca.permitted(0, 0.4, 0, 0.4, hp) : "continuing straight into a closing peer must not be permitted";
    }

    /** margin(v) is exactly linear in v, so the boundary point vOptA+0.5u sits exactly at
     * margin=0 (permitted, inclusive) by construction — pins down the flip point precisely. */
    private static void headOnBoundaryIsExactlyPermitted() {
        VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, 0.0);
        double bx = 0.5 * hp.ux();
        double bz = 0.4 + 0.5 * hp.uz();
        assertClose(0.0, VehicleOrca.margin(bx, bz, 0, 0.4, hp), "boundary margin");
        assert VehicleOrca.permitted(bx, bz, 0, 0.4, hp) : "boundary point must be permitted (inclusive)";
        double justShortX = 0.49 * hp.ux();
        double justShortZ = 0.4 + 0.49 * hp.uz();
        assert !VehicleOrca.permitted(justShortX, justShortZ, 0, 0.4, hp) : "just short of the boundary must not be permitted";
    }

    private static void headOnEvasiveSideIsPermitted() {
        VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, 0.0);
        // u points toward -x here; sidestepping that way is the correct evasive move.
        assert VehicleOrca.permitted(-0.6, 0.4, 0, 0.4, hp) : "sidestepping toward u's side must be permitted";
    }

    private static void headOnWrongSideIsNotPermitted() {
        VehicleOrca.HalfPlane hp = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, 0.0);
        assert !VehicleOrca.permitted(0.6, 0.4, 0, 0.4, hp) : "sidestepping the wrong way must not be permitted";
    }

    /** ORCA's reciprocity: A's u for B must be exactly B's u for A, negated (both take
     * complementary, not identical, halves of the avoidance). */
    private static void reciprocityHoldsUnderRoleSwap() {
        double px = 3, pz = 12, avx = -0.1, avz = 0.4, bvx = 0.2, bvz = -0.35;
        VehicleOrca.HalfPlane hpA = VehicleOrca.halfPlane(px, pz, avx, avz, bvx, bvz, R, TAU, 0.0);
        VehicleOrca.HalfPlane hpB = VehicleOrca.halfPlane(-px, -pz, bvx, bvz, avx, avz, R, TAU, 0.0);
        assertClose(0.0, hpA.ux() + hpB.ux(), "reciprocal u.x");
        assertClose(0.0, hpA.uz() + hpB.uz(), "reciprocal u.z");
    }

    /**
     * A perfectly symmetric head-on approach (zero lateral offset) makes the two tangent-leg
     * candidates exactly equidistant — no basis to prefer one over the other. sideBias must break
     * this deterministically, with a real (non-degenerate) lateral push, and opposite bias signs
     * must resolve to the mirror-image leg — the mechanism {@code Peer.id}-derived bias relies on
     * to keep two hulls from picking the same side.
     */
    private static void sideBiasBreaksExactTieReciprocally() {
        double eps = 1.0E-6;
        VehicleOrca.HalfPlane left = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, eps);
        VehicleOrca.HalfPlane right = VehicleOrca.halfPlane(0, 10, 0, 0.4, 0, -0.4, R, TAU, -eps);
        assert Math.abs(left.ux()) > 0.1 : "biased tie must produce a real lateral push, got " + left.ux();
        assertClose(left.ux(), -right.ux(), "opposite bias must mirror the x component");
        assertClose(left.uz(), right.uz(), "opposite bias must not change the z component");
    }

    /**
     * A genuinely safe, well separated, near-parallel pair gets only a MILD margin violation for
     * continuing at current velocity — nowhere near the severity of an actual close head-on
     * approach. Ground's skirt is what this feeds: a mild graded nudge, not a stop.
     */
    private static void wellSeparatedParallelIsOnlyMildlyRestricted() {
        VehicleOrca.HalfPlane safe = VehicleOrca.halfPlane(12, 5, 0, 0.4, 0, 0.4, R, TAU, 0.0);
        double safeMargin = VehicleOrca.margin(0, 0.4, 0, 0.4, safe);
        VehicleOrca.HalfPlane danger = VehicleOrca.halfPlane(0, 12, 0, 0.4, 0, -0.4, R, TAU, 0.0);
        double dangerMargin = VehicleOrca.margin(0, 0.4, 0, 0.4, danger);
        assert safeMargin > -0.1 : "well-separated parallel pair must be only mildly restricted, got " + safeMargin;
        assert dangerMargin < safeMargin : "a real close head-on approach must be more restricted than a safe parallel pair";
    }

    /** No peer at all (or a degenerate zero half-plane) must impose no restriction whatsoever. */
    private static void zeroHalfPlaneMeansNoRestriction() {
        VehicleOrca.HalfPlane zero = new VehicleOrca.HalfPlane(0.0, 0.0);
        assert VehicleOrca.permitted(50.0, -50.0, 0.0, 0.0, zero) : "zero half-plane must permit anything";
        assertClose(Double.POSITIVE_INFINITY, VehicleOrca.margin(50.0, -50.0, 0.0, 0.0, zero), "zero half-plane margin");
    }

    /**
     * The actual regression this restores: continuing straight at a STATIONARY wreck close enough
     * that only ~1 real hull's clearance remains (6 blocks against a ~5-block combined tank
     * radius) must be an imminent hard stop. Confirmed live-tested behavior before this test
     * existed — a smoke test showed peers/wrecks not being avoided at all under margin-only
     * hard-blocking; this is the concrete case that was silently passing through.
     */
    private static void imminentFiresForCloseStationaryWreck() {
        boolean imminent = VehicleOrca.imminent(0, 6, 0, 0.6, 0, 0.6, 0, 0, R, 8.0);
        assert imminent : "continuing straight at a near-touching stationary wreck must be imminent";
    }

    /** Mundane, non-threatening traffic (near-identical heading and speed) must never read as
     * imminent, however close, because the reciprocal relative velocity is ~zero — there is
     * nothing to converge on. */
    private static void imminentStaysClearForMundaneParallelTraffic() {
        double s = 0.6;
        double selfHead = Math.toRadians(3.0), peerHead = Math.toRadians(-1.0);
        double avx = s * Math.sin(selfHead), avz = s * Math.cos(selfHead);
        double bvx = s * Math.sin(peerHead), bvz = s * Math.cos(peerHead);
        boolean imminent = VehicleOrca.imminent(-3, 8, avx, avz, avx, avz, bvx, bvz, R, 8.0);
        assert !imminent : "near-identical parallel headings must not read as imminent";
    }

    /**
     * The exact false positive found and fixed during live testing: a margin-based hard threshold
     * rejected a wide-angle turn away from a peer 40+ blocks distant, because half-plane
     * penetration saturates rather than diverging with range. Time-to-collision must not repeat
     * that mistake — at long range, a heading that clearly diverges from the peer must never be
     * imminent, regardless of margin.
     */
    private static void imminentDivergesUnlikeMargin() {
        double s = 0.6;
        double candX = s * Math.sin(Math.toRadians(75.0)), candZ = s * Math.cos(Math.toRadians(75.0));
        boolean imminent = VehicleOrca.imminent(0, 40, candX, candZ, 0, s, 0, 0, R, 8.0);
        assert !imminent : "a sharp turn away from a distant wreck must not be imminent, whatever margin says";
    }

    private static void assertClose(double expected, double actual, String label) {
        if (Double.isInfinite(expected) && Double.isInfinite(actual) && Math.signum(expected) == Math.signum(actual)) {
            return;
        }
        if (Math.abs(expected - actual) > 1.0E-4) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }
}
