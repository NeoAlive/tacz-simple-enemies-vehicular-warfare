package com.neoalive.tacz_sewv.entity.ai.plane;

import net.minecraft.world.phys.Vec3;

/**
 * Headless check for the fixed-wing guidance geometry. Run via {@code ./gradlew selfCheckPlane}.
 *
 * <p>Everything asserted here is a pure function of its arguments, which is the point: the flying
 * is only as good as this maths, and a mistake in it is invisible in-game — a plane that misses,
 * wanders or lands badly looks identical whether the geometry was wrong or the throttle was. The
 * properties pinned below are the ones whose violation produced an actual reported bug.
 */
public final class PlaneGuidanceSelfCheck {

    private static final double EPS = 1.0E-6;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        checkIntercept();
        checkGunLine();
        checkFireCone();
        checkBallisticLead();
        checkOrbit();
        checkApproachAxis();
        checkLandingPredicates();
        checkDiveProfile();
        checkLeash();
        checkKinematics();
        checkProbe();
        checkPitchSign();
        checkModes();

        System.out.println("plane guidance self-check: OK");
    }

    // --- Aiming ---------------------------------------------------------------------------------

    /**
     * The gun line, not the nose. Pinned because the error it removes is silent: an aircraft aimed
     * by hull geometry flies and looks exactly right and misses low on every single shot.
     */
    private static void checkGunLine() {
        // SBW pitch convention: positive is nose DOWN, so a direction with negative y is a
        // depression. Getting this backwards would command a climb at a ground target.
        assert PlaneNav.pitchOfDeg(new Vec3(0.0, -1.0, 0.0)) > 0.0
                : "straight down must be positive pitch";
        assert Math.abs(PlaneNav.pitchOfDeg(new Vec3(1.0, 0.0, 0.0))) < EPS
                : "level must be zero pitch";

        // The A-10's cannon fires 1.7 degrees below the hull axis. With the nose exactly on a level
        // target the gun is therefore already low, and the correction must ask for MORE nose up.
        Vec3 cantedGun = new Vec3(0.0, -0.03, 1.0);
        Vec3 levelTarget = new Vec3(0.0, 0.0, 100.0);
        double err = PlaneNav.gunPitchErrorDeg(cantedGun, levelTarget);
        assert err < 0.0 : "a low-canted gun must be corrected upward, got " + err;
        assert Math.abs(err + Math.toDegrees(Math.atan(0.03))) < 1.0E-6
                : "correction must equal the cant exactly, got " + err;

        // And the loop is a fixed point: apply the correction, and the error is gone. This is the
        // property that makes barrel geometry cancel rather than accumulate.
        Vec3 corrected = new Vec3(0.0, 0.0, 1.0);
        assert Math.abs(PlaneNav.gunPitchErrorDeg(corrected, levelTarget)) < 1.0E-9
                : "corrected gun line must have zero error";

        // Total error is unsigned and agrees with the pitch error when the offset is purely
        // vertical — the fire gate and the steering must be measuring the same thing.
        double total = PlaneNav.gunErrorDeg(cantedGun, levelTarget);
        assert Math.abs(total - Math.abs(err)) < 1.0E-6
                : "gun error and pitch error disagree: " + total + " vs " + err;
    }

    /**
     * The derived fire cone. Both directions of the old bug are pinned here: a cone that stays wide
     * at range is the "fires 50 blocks from the target" report, and one that stays tight up close
     * is the "engages but never shoots" one.
     */
    private static void checkFireCone() {
        double min = 3.0;
        double max = 12.0;

        // Far out, the tolerance tightens toward the geometric answer rather than staying at the
        // ceiling: a 4-block burst radius at 90 blocks is about 2.5 degrees, so the floor governs.
        double far = PlaneNav.fireConeDeg(4.0, 90.0, min, max);
        assert far <= max : "far cone must not exceed the ceiling: " + far;
        assert far >= min : "far cone must not go under the floor: " + far;

        // Up close the same weapon earns a much wider cone, because the same angle is a much
        // smaller miss. Monotonicity in range is the property, not the numbers.
        double near = PlaneNav.fireConeDeg(4.0, 20.0, min, max);
        assert near > far : "cone must open as the range closes: " + near + " vs " + far;

        // A bigger blast earns a bigger cone at the same range — a 22-block bomb does not need
        // cannon accuracy, and demanding it is why bombs never went.
        double bomb = PlaneNav.fireConeDeg(22.0, 60.0, min, max);
        double gun = PlaneNav.fireConeDeg(4.0, 60.0, min, max);
        assert bomb > gun : "blast radius must widen the cone: " + bomb + " vs " + gun;

        // The angle actually means what it claims: a shot at exactly the cone edge lands at about
        // the lethal radius. This is the whole justification for deriving it.
        double range = 60.0;
        double lethal = 5.0;
        double cone = PlaneNav.fireConeDeg(lethal, range, 0.1, 89.0);
        double miss = range * Math.tan(Math.toRadians(cone));
        assert Math.abs(miss - lethal) < 1.0E-6
                : "cone does not correspond to the lethal radius: miss=" + miss;

        // Degenerate inputs must not produce a cone of zero (never fires) or of 180 (fires at the
        // horizon): unreadable gun data has to degrade to the bounds.
        assert PlaneNav.fireConeDeg(0.0, 100.0, min, max) >= min : "zero blast must floor, not zero";
        assert PlaneNav.fireConeDeg(1000.0, 1.0, min, max) <= max : "huge blast must respect the cap";
    }

    /**
     * The bomb release distance. Pinned because it is the number that decides whether a bombing
     * run is <em>offered</em> a release at all: sizing the run off a fixed 96-block bubble while
     * the store needs 87 blocks of fall is not a mistimed drop, it is a drop that can never happen,
     * and it looks from the ground exactly like an aircraft that overflies and forgets to pickle.
     */
    private static void checkBallisticLead() {
        // A-10 numbers: 40 blocks up, Mk 82 at gravity 0.06. Roughly 36 ticks of fall, so the lead
        // is about 35 times the ground speed and is well outside the engage bubble at jet speed.
        double slow = PlaneNav.ballisticLead(40.0, 1.0, 0.06);
        double fast = PlaneNav.ballisticLead(40.0, 2.5, 0.06);
        assert Math.abs(slow - Math.sqrt(2.0 * 40.0 / 0.06)) < 1.0E-9 : "lead at unit speed is the fall time";
        assert fast > 80.0 : "a fast aircraft must release from well outside the bubble: " + fast;
        assert Math.abs(fast - slow * 2.5) < 1.0E-9 : "lead must be linear in speed";

        // Higher release, longer fall, longer lead — the property the run altitude depends on.
        assert PlaneNav.ballisticLead(64.0, 2.0, 0.06) > PlaneNav.ballisticLead(40.0, 2.0, 0.06)
                : "a higher release must carry further";

        // Degenerate inputs answer zero rather than NaN or infinity: a stationary or climbing
        // aircraft must simply not be offered a bombing envelope.
        assert PlaneNav.ballisticLead(0.0, 2.0, 0.06) == 0.0 : "no height, no lead";
        assert PlaneNav.ballisticLead(40.0, 0.0, 0.06) == 0.0 : "no speed, no lead";
        assert PlaneNav.ballisticLead(40.0, 2.0, 0.0) == 0.0 : "no gravity, no lead";
    }

    private static void checkIntercept() {
        // Stationary target: the intercept is the straight-line flight time and the aim point is
        // the target itself. Any lead here would be a systematic miss on every parked tank.
        Vec3 delta = new Vec3(60.0, 0.0, 0.0);
        double t = PlaneNav.interceptTime(delta, Vec3.ZERO, 12.0);
        assert Math.abs(t - 5.0) < 1.0E-9 : "stationary intercept time wrong: " + t;

        Vec3 aim = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0), Vec3.ZERO,
                12.0, 0.0);
        assert aim.distanceTo(new Vec3(60.0, 0.0, 0.0)) < EPS
                : "stationary target must be aimed at directly, got " + aim;

        // Crossing target: the aim point leads it, and it leads it the way it is going.
        Vec3 crossing = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 0.5), 12.0, 0.0);
        assert crossing.z > 0.0 : "lead must be in the direction of travel, got " + crossing;
        assert crossing.z < 5.0 : "lead is implausibly large: " + crossing;

        // The solved time must actually satisfy the intercept: the projectile and the target arrive
        // at the same place at the same moment. This is the property, not the formula.
        double it = PlaneNav.interceptTime(new Vec3(60.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.5), 12.0);
        Vec3 meet = new Vec3(60.0, 0.0, 0.5 * it);
        assert Math.abs(meet.length() - 12.0 * it) < 1.0E-6
                : "intercept does not close: |meet|=" + meet.length() + " vs " + (12.0 * it);

        // Gravity raises the aim point, and only for weapons that actually have drop. A guided
        // missile passes 0 here precisely because compensating a drop it does not have aims high.
        Vec3 withDrop = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0), Vec3.ZERO,
                12.0, 0.05);
        assert withDrop.y > 0.0 : "ballistic aim must be raised, got " + withDrop;
        Vec3 noDrop = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0), Vec3.ZERO,
                12.0, 0.0);
        assert Math.abs(noDrop.y) < EPS : "guided aim must be flat, got " + noDrop;

        // Unreadable gun data (velocity 0 on the placeholder slots addon packs ship) must degrade
        // to aiming at the target, never to swinging the nose somewhere arbitrary.
        Vec3 broken = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0),
                new Vec3(2.0, 0.0, 2.0), 0.0, 0.05);
        assert broken.distanceTo(new Vec3(60.0, 0.0, 0.0)) <= PlaneNav.MAX_AIM_OFFSET + EPS
                : "bad gun data must clamp the aim point, got " + broken;

        // A target faster than the projectile has no intercept at all; the answer must still be a
        // finite, forward-pointing aim rather than a NaN that poisons the steering.
        Vec3 unreachable = PlaneNav.interceptPoint(Vec3.ZERO, new Vec3(60.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, 50.0), 3.0, 0.0);
        assert !Double.isNaN(unreachable.x) && !Double.isNaN(unreachable.z)
                : "unreachable target produced NaN aim";
        assert unreachable.distanceTo(new Vec3(60.0, 0.0, 0.0)) <= PlaneNav.MAX_AIM_OFFSET + EPS
                : "unreachable target must clamp, got " + unreachable;
    }

    // --- Hold -----------------------------------------------------------------------------------

    private static void checkOrbit() {
        double radius = 80.0;

        // Exactly on the circle: pure tangent, no radial component. If this drifts, the hold spirals
        // and the aircraft slowly leaves — which is the whole "planes wander off" report.
        Vec3 onCircle = PlaneNav.orbitSteer(radius, 0.0, radius, false);
        double radialOn = onCircle.x; // radial unit here is (1,0,0)
        assert Math.abs(radialOn) < 1.0E-9 : "on-circle steer has radial component " + radialOn;
        assert Math.abs(onCircle.length() - 1.0) < 1.0E-9 : "steer must be a unit vector";

        // Outside the circle: some of the steer points back in.
        Vec3 outside = PlaneNav.orbitSteer(radius * 2.0, 0.0, radius, false);
        assert outside.x < 0.0 : "outside the hold must steer inward, got " + outside;

        // Inside: some of it points back out.
        Vec3 inside = PlaneNav.orbitSteer(radius * 0.5, 0.0, radius, false);
        assert inside.x > 0.0 : "inside the hold must steer outward, got " + inside;

        // Opposite senses are genuinely opposite, so two aircraft on one anchor separate instead of
        // converging on each other.
        Vec3 cw = PlaneNav.orbitSteer(radius, 0.0, radius, true);
        Vec3 ccw = PlaneNav.orbitSteer(radius, 0.0, radius, false);
        assert cw.dot(ccw) < -0.9 : "clockwise and anticlockwise must oppose, got " + cw.dot(ccw);

        // Dead over the anchor is the singular case and must still answer something flyable.
        Vec3 centre = PlaneNav.orbitSteer(0.0, 0.0, radius, false);
        assert Math.abs(centre.length() - 1.0) < 1.0E-9 : "centre case must return a unit bearing";
    }

    // --- Approach geometry ----------------------------------------------------------------------

    private static void checkApproachAxis() {
        Vec3 axis = new Vec3(1.0, 0.0, 0.0); // final approach flown toward +X
        double padX = 100.0;
        double padZ = 0.0;

        // Aircraft 60 blocks short, on the centreline.
        double dx = padX - 40.0;
        double dz = padZ - 0.0;
        assert Math.abs(PlaneNav.alongTrack(dx, dz, axis) - 60.0) < EPS : "along-track wrong";
        assert Math.abs(PlaneNav.crossTrack(dx, dz, axis)) < EPS : "on-centreline cross must be 0";

        // Past the pad: along-track goes negative, which is the overshoot signal.
        assert PlaneNav.alongTrack(padX - 120.0, 0.0, axis) < 0.0
                : "flying past the pad must read as negative along-track";

        // Off to one side: the cross-track is the offset magnitude.
        assert Math.abs(Math.abs(PlaneNav.crossTrack(60.0, 25.0, axis)) - 25.0) < EPS
                : "cross-track magnitude wrong";

        // The carrot sits on the axis, ahead of the aircraft (which is 60 out, at x = 40), and
        // never beyond the pad.
        Vec3 carrot = PlaneNav.approachCarrot(padX, padZ, axis, 60.0, 20.0);
        assert Math.abs(carrot.z) < EPS : "carrot must be on the axis";
        assert carrot.x > 40.0 && carrot.x < padX + EPS
                : "carrot must lead the aircraft toward the pad, got " + carrot;
        assert Math.abs(carrot.x - 60.0) < EPS
                : "carrot must sit exactly the lookahead ahead, got " + carrot;
        Vec3 shortFinal = PlaneNav.approachCarrot(padX, padZ, axis, 5.0, 20.0);
        assert Math.abs(shortFinal.x - padX) < EPS
                : "inside the lookahead the carrot is the pad itself, got " + shortFinal;

        // The fix is one leg back up the axis from the pad — the point the pattern flies to.
        Vec3 fix = PlaneNav.approachFix(padX, padZ, axis, 140.0);
        assert Math.abs(fix.x - (padX - 140.0)) < EPS : "fix placed wrong: " + fix;

        // Established needs all three: near the axis, short of the pad, pointing down it. Any one of
        // them alone was the old behaviour, which descended at the pad from wherever it was.
        assert PlaneNav.established(4.0, 60.0, 10.0, 24.0, 140.0, 40.0) : "should be established";
        assert !PlaneNav.established(40.0, 60.0, 10.0, 24.0, 140.0, 40.0) : "wide of the axis";
        assert !PlaneNav.established(4.0, -10.0, 10.0, 24.0, 140.0, 40.0) : "already past the pad";
        assert !PlaneNav.established(4.0, 60.0, 80.0, 24.0, 140.0, 40.0) : "pointing the wrong way";
        assert !PlaneNav.established(4.0, 400.0, 10.0, 24.0, 140.0, 40.0) : "too far out for final";

        // Heading error is symmetric and wraps.
        double err = PlaneNav.headingErrorDeg(new Vec3(1, 0, 0), new Vec3(0, 0, 1));
        assert Math.abs(err - 90.0) < 1.0E-4 : "heading error wrong: " + err;
        assert PlaneNav.headingErrorDeg(new Vec3(1, 0, 0), new Vec3(-1, 0, 0)) > 179.0
                : "reciprocal heading must read as ~180";
    }

    private static void checkLandingPredicates() {
        // Flare needs BOTH gates. Height alone is what made the aircraft cut power over whatever it
        // was crossing and sink into it.
        assert PlaneNav.flareReady(6.0, 20.0, 8.0, 24.0) : "low and near should flare";
        assert !PlaneNav.flareReady(6.0, 200.0, 8.0, 24.0) : "low but far must NOT flare";
        assert !PlaneNav.flareReady(40.0, 10.0, 8.0, 24.0) : "near but high must NOT flare";

        // Touchdown on the pad is a landing, and so is a touchdown short of it that then rolls to a
        // stop. The second case is the one that was wrong: a plane cannot arrive already stationary
        // on the numbers, so treating "on the ground but not there yet" as a failure meant every
        // textbook landing was answered with a go-around, from the runway, forever.
        assert PlaneNav.settled(true, 4.0, 8.0, 0.6, 0.05) : "on the pad is landed";
        assert PlaneNav.settled(true, 60.0, 8.0, 0.01, 0.05) : "rolled to a stop is landed";
        assert !PlaneNav.settled(true, 60.0, 8.0, 0.6, 0.05) : "still rolling out is not finished";
        assert !PlaneNav.settled(false, 1.0, 8.0, 0.0, 0.05) : "still flying is not landed";

        // A go-around is only ever available while the wheels are up.
        assert PlaneNav.overshot(-40.0, 12.0) : "flown past the pad is a miss";
        assert !PlaneNav.overshot(60.0, 12.0) : "normal final is not a miss";
        assert !PlaneNav.overshot(-6.0, 12.0) : "a few blocks past is within the margin";
    }

    /**
     * The dive profile, which is where "the plane never attacks" came from: the run has to be
     * planned down to the altitude the aircraft levels at, not to the target's own, or the final
     * sample of every ground attack sits on the deck and the gate refuses all of them.
     */
    private static void checkDiveProfile() {
        double startY = 120.0;
        double endY = 42.0;
        double run = 100.0;

        assert Math.abs(PlaneTerrain.diveAltitudeAt(startY, endY, run, 0.0) - startY) < EPS
                : "the dive starts where the aircraft is";
        assert Math.abs(PlaneTerrain.diveAltitudeAt(startY, endY, run, run) - endY) < EPS
                : "the dive ends at the levelling altitude, NOT at the target";
        double mid = PlaneTerrain.diveAltitudeAt(startY, endY, run, run / 2.0);
        assert Math.abs(mid - 81.0) < EPS : "midpoint of a straight descent: " + mid;

        // Monotone, and clamped outside the run rather than extrapolating into the ground.
        assert PlaneTerrain.diveAltitudeAt(startY, endY, run, 10.0)
                > PlaneTerrain.diveAltitudeAt(startY, endY, run, 90.0) : "a dive descends";
        assert Math.abs(PlaneTerrain.diveAltitudeAt(startY, endY, run, run * 3.0) - endY) < EPS
                : "past the end of the run the profile holds, it does not keep descending";

        // A zero-length run is the degenerate case a target directly underneath produces.
        assert Math.abs(PlaneTerrain.diveAltitudeAt(startY, endY, 0.0, 5.0) - endY) < EPS
                : "a zero-length run answers its own end altitude";
    }

    // --- Leash ----------------------------------------------------------------------------------

    private static void checkLeash() {
        double soft = 256.0;
        double hard = soft * PlaneLeash.HARD_MULTIPLIER;

        assert PlaneLeash.evaluate(100.0, soft, PlaneLeash.State.FREE) == PlaneLeash.State.FREE;
        assert PlaneLeash.evaluate(300.0, soft, PlaneLeash.State.FREE) == PlaneLeash.State.RECALL
                : "past the soft ring must recall";
        assert PlaneLeash.evaluate(hard + 1.0, soft, PlaneLeash.State.FREE)
                == PlaneLeash.State.RETURN : "past the hard ring must abandon combat";

        // Hysteresis: coming back inside the soft ring is not enough to be free again, or the state
        // flips every time the fight drifts across the line and the aircraft never resolves it.
        assert PlaneLeash.evaluate(soft - 1.0, soft, PlaneLeash.State.RETURN)
                == PlaneLeash.State.RETURN : "must keep returning until well inside";
        assert PlaneLeash.evaluate(soft * PlaneLeash.RECOVER_FRACTION - 1.0, soft,
                PlaneLeash.State.RETURN) == PlaneLeash.State.FREE : "well inside releases the leash";
        assert PlaneLeash.evaluate(soft * PlaneLeash.RECOVER_FRACTION - 1.0, soft,
                PlaneLeash.State.RECALL) == PlaneLeash.State.FREE : "recall releases the same way";

        // The hard ring outranks whatever we were doing.
        assert PlaneLeash.evaluate(hard + 50.0, soft, PlaneLeash.State.RECALL)
                == PlaneLeash.State.RETURN;

        // Monotone: further away is never a weaker response.
        PlaneLeash.State prev = PlaneLeash.State.FREE;
        int lastRank = -1;
        for (double d = 0.0; d <= hard * 1.2; d += 8.0) {
            PlaneLeash.State s = PlaneLeash.evaluate(d, soft, prev);
            int rank = s.ordinal();
            assert rank >= lastRank : "leash weakened as distance grew, at " + d;
            lastRank = rank;
            prev = s;
        }
    }

    // --- Kinematics / terrain -------------------------------------------------------------------

    private static void checkKinematics() {
        // r = v / omega. Faster or lazier means wider; both are clamped to a flyable band.
        double slow = PlaneKinematics.turnRadius(1.0, 2.0);
        double fast = PlaneKinematics.turnRadius(3.0, 2.0);
        assert fast > slow : "faster must need a wider turn: " + fast + " vs " + slow;
        double agile = PlaneKinematics.turnRadius(2.0, 4.0);
        double sluggish = PlaneKinematics.turnRadius(2.0, 1.0);
        assert sluggish > agile : "a lazier hull must need a wider turn";

        assert PlaneKinematics.turnRadius(0.0, 2.0) >= PlaneKinematics.MIN_TURN_RADIUS
                : "a stopped hull must not imply a zero-radius turn";
        assert PlaneKinematics.turnRadius(100.0, 0.0) <= PlaneKinematics.MAX_TURN_RADIUS
                : "a straight-flying hull must not imply an infinite turn";

        // Sanity against the real geometry: 2 blocks/tick at 2 deg/tick is ~57 blocks of radius,
        // which is why a helicopter-sized 32-block orbit is not something a jet can fly.
        double r = PlaneKinematics.turnRadius(2.0, 2.0);
        assert r > 50.0 && r < 65.0 : "turn radius off the expected scale: " + r;
    }

    private static void checkProbe() {
        double slow = PlaneTerrain.probeDistance(0.1);
        double cruise = PlaneTerrain.probeDistance(2.0);
        double fast = PlaneTerrain.probeDistance(10.0);
        assert cruise > slow : "faster must look further ahead";
        assert slow >= 32.0 && fast <= 96.0 : "probe must stay in its band: " + slow + ".." + fast;
        // The whole point: at cruise the probe covers more than the old flat 48 blocks, which a jet
        // crossed in well under a second.
        assert cruise >= 48.0 : "cruise probe is shorter than the constant it replaced: " + cruise;
    }

    private static void checkPitchSign() {
        // Positive xRot is nose-DOWN in SBW, so climbing to a higher hold is a negative command.
        // Getting this backwards flies the aircraft into the ground while "holding altitude".
        assert PlaneController.altitudePitch(100.0, 80.0) < 0.0 : "below the hold must pitch up";
        assert PlaneController.altitudePitch(80.0, 100.0) > 0.0 : "above the hold must pitch down";
        assert Math.abs(PlaneController.altitudePitch(100.0, 100.0)) < EPS : "on altitude is level";
        assert PlaneController.altitudePitch(10000.0, 0.0)
                >= -PlaneController.MAX_CRUISE_PITCH_DEG : "cruise pitch must stay bounded";
    }

    private static void checkModes() {
        // The dispatch splits on needsTarget(); a mode that needs one must never be a committed
        // mode, because the committed modes (takeoff, landing, parked) clear the target outright.
        for (PlaneMode m : PlaneMode.values()) {
            assert !(m.needsTarget() && m.isCommitted())
                    : m + " both requires a target and commits the aircraft";
        }
        assert PlaneMode.INGRESS.needsTarget() && PlaneMode.ATTACK.needsTarget()
                && PlaneMode.BREAK.needsTarget() : "the combat set must require a target";
        assert !PlaneMode.HOLD.needsTarget() && !PlaneMode.CRUISE.needsTarget()
                && !PlaneMode.RTB.needsTarget() : "the out-of-contact set must not";
        assert PlaneMode.LAND_PATTERN.isLanding() && PlaneMode.LAND_FINAL.isLanding()
                : "both halves of the approach must read as landing";
        assert !PlaneMode.LANDED.isLanding() : "already down is not an approach";
    }
}
