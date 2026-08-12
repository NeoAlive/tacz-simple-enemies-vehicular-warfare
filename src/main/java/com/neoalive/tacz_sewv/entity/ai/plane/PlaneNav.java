package com.neoalive.tacz_sewv.entity.ai.plane;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The spatial mathematics a fixed-wing crew flies by: where a shot has to be aimed to arrive on a
 * moving target, how to hold a circle you cannot hover over, and where the approach axis is. Every
 * method here is a pure function of its arguments — no world, no entity, no state — because this is
 * the layer whose mistakes are invisible in-game (a plane that misses looks the same whether the
 * geometry or the throttle was wrong) and therefore the layer that has to be checked headlessly.
 *
 * <p>It produces geometry and nothing else: it never picks a mode, never writes an input.
 */
public final class PlaneNav {

    /** Below this the quadratic is numerically meaningless and the direct line is the answer. */
    private static final double EPS = 1.0E-6;
    /**
     * Hard cap on how far the aim point may be displaced from the target. Intercept and drop are
     * derived from datapack numbers that a pack author can set to anything; a bad velocity must
     * degrade to "aims at the target" rather than to "aims at the horizon".
     */
    public static final double MAX_AIM_OFFSET = 8.0;
    /** No solution is trusted past this flight time — beyond it the target has simply moved on. */
    private static final double MAX_INTERCEPT_TICKS = 60.0;

    private PlaneNav() {}

    /**
     * Time of flight for a projectile of speed {@code projSpeed} to meet a target that is
     * {@code delta} away and moving at {@code targetVel}, both relative to the muzzle.
     *
     * <p>Solves |delta + v t| = s t, i.e. {@code (v.v - s^2) t^2 + 2 (delta.v) t + delta.delta = 0},
     * taking the smallest positive root. When the target outruns the projectile (or the projectile
     * speed is unreadable, which SBW reports as 0 for placeholder slots) there is no root and the
     * answer falls back to the straight-line time, which is exactly aiming at where it is now.
     *
     * @return flight time in ticks, never negative
     */
    public static double interceptTime(Vec3 delta, Vec3 targetVel, double projSpeed) {
        double dist = delta.length();
        if (projSpeed <= EPS) return 0.0;
        double a = targetVel.lengthSqr() - projSpeed * projSpeed;
        double b = 2.0 * delta.dot(targetVel);
        double c = delta.lengthSqr();

        double t;
        if (Math.abs(a) < EPS) {
            // Target closing at exactly projectile speed: the quadratic degenerates to a line.
            t = Math.abs(b) < EPS ? dist / projSpeed : -c / b;
        } else {
            double disc = b * b - 4.0 * a * c;
            if (disc < 0.0) return Math.min(dist / projSpeed, MAX_INTERCEPT_TICKS);
            double root = Math.sqrt(disc);
            double t1 = (-b - root) / (2.0 * a);
            double t2 = (-b + root) / (2.0 * a);
            t = smallestPositive(t1, t2);
        }
        if (!(t > 0.0) || Double.isNaN(t)) t = dist / projSpeed;
        return Mth.clamp(t, 0.0, MAX_INTERCEPT_TICKS);
    }

    private static double smallestPositive(double t1, double t2) {
        double lo = Math.min(t1, t2);
        double hi = Math.max(t1, t2);
        if (lo > 0.0) return lo;
        return hi > 0.0 ? hi : 0.0;
    }

    /**
     * Where to point so the shot arrives: the target's position after the flight time, raised by
     * the ballistic drop over that same time. Displacement from {@code targetPos} is clamped to
     * {@link #MAX_AIM_OFFSET} so unreadable gun data cannot swing the nose off the target entirely.
     *
     * <p>Gravity is the projectile's own, which for a guided missile is 0 — pass 0 there rather
     * than SBW's schema default of 0.05, which is a lie for anything that steers itself.
     */
    public static Vec3 interceptPoint(Vec3 muzzle, Vec3 targetPos, Vec3 targetVel,
                                      double projSpeed, double gravity) {
        Vec3 delta = targetPos.subtract(muzzle);
        double t = interceptTime(delta, targetVel, projSpeed);
        if (t <= 0.0) return targetPos;
        double drop = 0.5 * gravity * t * t;
        Vec3 aim = targetPos.add(targetVel.scale(t)).add(0.0, drop, 0.0);
        Vec3 offset = aim.subtract(targetPos);
        double len = offset.length();
        if (len > MAX_AIM_OFFSET) {
            aim = targetPos.add(offset.scale(MAX_AIM_OFFSET / len));
        }
        return aim;
    }

    /**
     * Vector-field circular hold: the horizontal direction to fly to converge onto, and then stay
     * on, a circle of {@code radius} about an anchor. {@code (ex, ez)} is the offset from the
     * anchor to the aircraft.
     *
     * <p>This is what replaces holding a constant yaw stick. A constant stick is an open loop: it
     * turns at whatever rate the airspeed happens to give and the circle drifts wherever the wind
     * of accumulated error takes it, which is the whole "planes wander off for no reason" report.
     * Here the tangential term flies the circle and the radial term corrects the error in it, so
     * the hold is closed against the anchor every tick and cannot drift.
     *
     * @param clockwise which way round, so two aircraft on the same anchor can be given
     *                  opposite senses instead of converging on each other
     * @return unit horizontal direction; never null
     */
    public static Vec3 orbitSteer(double ex, double ez, double radius, boolean clockwise) {
        double d = Math.sqrt(ex * ex + ez * ez);
        double r = Math.max(radius, 1.0);
        if (d < EPS) {
            // Dead over the anchor: any bearing leaves the singularity, and the radial term will
            // pick the circle up from there.
            return new Vec3(1.0, 0.0, 0.0);
        }
        double rx = ex / d;
        double rz = ez / d;
        double tx = clockwise ? rz : -rz;
        double tz = clockwise ? -rx : rx;
        // Positive when inside the circle (fly out), negative when outside (fly in).
        double correction = Mth.clamp((r - d) / r, -1.0, 1.0);
        double vx = tx + rx * correction;
        double vz = tz + rz * correction;
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len < EPS) return new Vec3(tx, 0.0, tz);
        return new Vec3(vx / len, 0.0, vz / len);
    }

    /**
     * Distance out along the approach, measured from the pad backwards up the axis. Positive means
     * still short of the pad, negative means past it — which is the overshoot test.
     *
     * @param axis unit horizontal direction the aircraft will be travelling on final
     */
    public static double alongTrack(double dx, double dz, Vec3 axis) {
        // dx/dz point from the aircraft to the pad; the axis points the same way on a good
        // approach, so the dot product is the remaining distance.
        return dx * axis.x + dz * axis.z;
    }

    /** Signed lateral offset from the approach axis. Sign is only used for symmetry, not doctrine. */
    public static double crossTrack(double dx, double dz, Vec3 axis) {
        return dx * axis.z - dz * axis.x;
    }

    /**
     * How far a box of these half-extents reaches along a horizontal unit direction — its support
     * function, {@code |dx| hx + |dz| hz}.
     *
     * <p>This is what lets a bomb release window be measured against the target's <b>hitbox</b>
     * rather than its position, at a run bearing that has nothing to do with the world axes. A
     * bomb that lands on the far end of a hull has hit it, and on a large vehicle that end is
     * several blocks away — comparable to the whole release window, so ignoring it is not a
     * rounding error. Exact for an AABB, and it degrades to the half-width for an axis-aligned run
     * rather than to something optimistic.
     */
    public static double boxExtent(double dirX, double dirZ, double halfX, double halfZ) {
        return Math.abs(dirX) * halfX + Math.abs(dirZ) * halfZ;
    }

    /**
     * Pure-pursuit carrot on the approach axis: a point {@code lookahead} blocks further down the
     * axis than the aircraft's own along-track position. Steering at a moving point on the line
     * converges onto the line without the oscillation that steering straight at the pad gives,
     * because the correction shrinks as the cross-track error does.
     *
     * @param padX pad centre
     * @param axis unit direction of travel on final
     * @param along the aircraft's current along-track distance from the pad
     */
    public static Vec3 approachCarrot(double padX, double padZ, Vec3 axis, double along,
                                      double lookahead) {
        double remaining = Math.max(0.0, along - lookahead);
        // The axis points aircraft → pad, so the carrot sits back up the axis from the pad.
        return new Vec3(padX - axis.x * remaining, 0.0, padZ - axis.z * remaining);
    }

    /**
     * Initial approach fix: the point one final-leg out from the pad, on the approach axis. Flying
     * to this before turning onto the axis is what gives the aircraft a straight, aligned final
     * instead of a diving spiral onto the numbers.
     */
    public static Vec3 approachFix(double padX, double padZ, Vec3 axis, double legLength) {
        return new Vec3(padX - axis.x * legLength, 0.0, padZ - axis.z * legLength);
    }

    /**
     * Established on final: close enough to the axis, still short of the pad, and pointing roughly
     * down it. All three matter — the old code had none of them and simply descended at the pad
     * from wherever it was.
     */
    public static boolean established(double crossTrack, double along, double headingErrDeg,
                                      double corridorHalfWidth, double maxLegLength,
                                      double maxHeadingErrDeg) {
        return Math.abs(crossTrack) <= corridorHalfWidth
                && along > 0.0
                && along <= maxLegLength
                && Math.abs(headingErrDeg) <= maxHeadingErrDeg;
    }

    /**
     * Flare gate: low <b>and</b> near the pad. Height alone was the old rule, so a fast approach
     * that was still crossing a field flared over the field, cut the throttle and sank into it.
     */
    public static boolean flareReady(double agl, double distToPad, double flareAgl,
                                     double flareRadius) {
        return agl <= flareAgl && distToPad <= flareRadius;
    }

    /**
     * The landing is over: the aircraft is on its wheels and either parked on the pad or has run
     * out of speed wherever it ended up.
     *
     * <p><b>Rolling out is part of landing, not a failed one.</b> The first version declared a
     * landing only for a touchdown already within the settle radius and treated every other ground
     * contact as a missed approach — but a fixed-wing hull touches down short and rolls, so a
     * textbook arrival was classified as a miss, the aircraft firewalled the throttle from the
     * runway, went round, and did it again. Nothing that has its wheels down goes around here: a
     * hull with no airspeed has no lift to go around <em>with</em>.
     */
    public static boolean settled(boolean onGround, double distToPad, double settleRadius,
                                  double speed, double stopSpeed) {
        return onGround && (distToPad <= settleRadius || speed <= stopSpeed);
    }

    /**
     * Missed approach: flown past the pad down the axis with the wheels still up. Ground contact is
     * deliberately <b>not</b> a miss — see {@link #settled}.
     */
    public static boolean overshot(double along, double overshootMargin) {
        return along < -overshootMargin;
    }

    /**
     * Elevation of a direction expressed as an SBW pitch, i.e. <b>positive is nose down</b>. Used to
     * convert a measured gun line into the same units the pitch stick is commanded in.
     */
    public static double pitchOfDeg(Vec3 dir) {
        double len = dir.length();
        if (len < EPS) return 0.0;
        return -Math.toDegrees(Math.asin(Mth.clamp(dir.y / len, -1.0, 1.0)));
    }

    /**
     * How far the <b>gun line</b> is off the aim point, in degrees of pitch — positive meaning the
     * shot needs to come down.
     *
     * <p>This exists because pointing the hull's nose at a target is not the same as pointing its
     * weapon at one, and the difference is not small enough to ignore. Every SBW gun declares a
     * {@code ShootDirectionForHud} relative to the hull (the A-10's cannon is {@code [0,-0.03,1]},
     * about 1.7 degrees below the nose) and fires from a {@code ShootPos} metres forward of the
     * hull origin. Commanding an attitude computed from hull-origin-to-target geometry therefore
     * leaves a fixed depression error on every shot at every range — which inside a few-degree fire
     * gate is a large fraction of the whole budget, and always in the same direction.
     *
     * <p>Closing the loop on the measured gun line instead makes the error self-cancelling: it does
     * not matter where the barrel sits or which way it is canted, because what is driven to zero is
     * the thing that actually has to be zero.
     *
     * @param shootDir the weapon's current firing direction in world space
     * @param toAim muzzle to aim point, in world space
     */
    public static double gunPitchErrorDeg(Vec3 shootDir, Vec3 toAim) {
        if (shootDir.lengthSqr() < EPS || toAim.lengthSqr() < EPS) return 0.0;
        return pitchOfDeg(toAim) - pitchOfDeg(shootDir);
    }

    /**
     * Total angle (degrees) between the gun line and the aim point — the quantity the fire gate is
     * a threshold on, and the honest measure of how well the autopilot is pointing.
     */
    public static double gunErrorDeg(Vec3 shootDir, Vec3 toAim) {
        if (shootDir.lengthSqr() < EPS || toAim.lengthSqr() < EPS) return 180.0;
        double cos = shootDir.normalize().dot(toAim.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
    }

    /**
     * The angular tolerance within which a shot still lands inside {@code lethalRadius} of the aim
     * point at {@code range}: {@code atan(lethalRadius / range)}.
     *
     * <p>A fixed cone is the wrong shape for this and was the original miss. A miss lands about
     * {@code range x tan(angle)} wide, so one angle is simultaneously far too loose far out (six
     * degrees at 96 blocks is a ten-block miss, which a cannon's four-block burst radius does not
     * cover) and needlessly strict up close (the same six degrees at twenty blocks is two blocks,
     * refusing shots a bomb with a twenty-block radius would obliterate). Deriving it from the
     * weapon's own blast radius makes one rule serve every weapon: the aircraft holds fire until
     * the shot would <em>land on</em> the target, whatever "on" means for what it is carrying.
     *
     * @param lethalRadius blocks — the weapon's explosion radius, or its accuracy requirement
     * @param minDeg never tighter than this, or a heavy airframe never qualifies at all
     * @param maxDeg never looser than this, whatever the blast radius claims
     */
    public static double fireConeDeg(double lethalRadius, double range, double minDeg,
                                     double maxDeg) {
        double r = Math.max(range, 1.0);
        double cone = Math.toDegrees(Math.atan(Math.max(lethalRadius, 0.0) / r));
        return Mth.clamp(cone, Math.min(minDeg, maxDeg), maxDeg);
    }

    /**
     * How far downrange a free-fall store carries between release and impact — the distance a
     * bombing run has to be <b>started</b> from, as opposed to the distance it is released at.
     *
     * <p>This is the number the whole bomb envelope has to be sized off, and the reason is that it
     * is large and it scales with airspeed. An A-10 releasing from 40 blocks up takes ~36 ticks to
     * bring a Mk 82 down, so at 1.5 blocks/tick the bomb travels ~52 blocks forward and at 2.5 it
     * travels ~87. A run that may only begin inside 96 blocks therefore hands the release solution
     * a window a few ticks wide at best, and none at all once the aircraft is fast — which is not
     * a release that is mistimed, it is a release that is never offered.
     *
     * <p>Continuous form, {@code t = sqrt(2h/g)}, against the tick-stepped integration the actual
     * release decision uses. The small disagreement does not matter here: this sizes the approach
     * so the release point falls comfortably inside the run, and the exact instant is still chosen
     * by simulating the drop.
     *
     * @param releaseHeight blocks between the bomb bay and the impact altitude
     * @param groundSpeed the store's initial horizontal speed, blocks/tick — for SBW's
     *                    {@code AddShooterDeltaMovement} stores that is the hull's own speed
     *                    scaled by the weapon's {@code Velocity}, not the hull's speed raw
     * @return blocks; 0 when any input makes the question meaningless
     */
    public static double ballisticLead(double releaseHeight, double groundSpeed, double gravity) {
        if (!(releaseHeight > 0.0) || !(groundSpeed > 0.0) || !(gravity > 0.0)) return 0.0;
        return groundSpeed * Math.sqrt(2.0 * releaseHeight / gravity);
    }

    /** Where a released store comes down, and how many ticks it takes to get there. */
    public record Impact(int ticks, double x, double z) {}

    /**
     * Step a free-fall store from release to the altitude it is aimed at.
     *
     * <p>Stepped rather than solved in closed form, and in exactly the game's own order — advance
     * by the current velocity, <em>then</em> apply gravity — because that is what SBW's projectile
     * tick does and the two disagree by half a tick of travel, which is a block or three at jet
     * speed. Drag is deliberately absent: {@code FastThrowableProjectile} adds gravity back, undoes
     * vanilla's 0.99 friction by scaling by its reciprocal, then re-applies gravity, so the net
     * per-tick effect is pure gravity and a drag term here would be modelling a force the store
     * does not feel.
     *
     * @param releaseVel the store's initial velocity — for an SBW delta-movement store, the hull's
     *                   own velocity scaled by the weapon's {@code Velocity}
     * @return null when the store does not arrive within {@code maxTicks}, which is the honest
     *         answer for a release off a climb: it does come down, but not anywhere worth aiming
     */
    public static Impact freefallImpact(Vec3 releasePos, Vec3 releaseVel, double gravity,
                                        double impactY, int maxTicks) {
        double x = releasePos.x;
        double y = releasePos.y;
        double z = releasePos.z;
        double vy = releaseVel.y;
        for (int t = 1; t <= maxTicks; t++) {
            x += releaseVel.x;
            y += vy;
            z += releaseVel.z;
            vy -= gravity;
            if (y <= impactY) return new Impact(t, x, z);
        }
        return null;
    }

    /**
     * Where something moving at {@code vel} will be in {@code ticks} — the aim point a store with
     * a flight time has to be thrown at, rather than where the target is at release.
     *
     * <p>Only the horizontal components are carried. A bomb is aimed at a place on the ground and
     * arrives when it reaches that ground's altitude; projecting the target's vertical velocity
     * would move the aim point up or down a slope it is already following.
     */
    public static Vec3 leadPoint(Vec3 targetPos, Vec3 targetVel, double ticks) {
        if (!(ticks > 0.0)) return targetPos;
        return new Vec3(targetPos.x + targetVel.x * ticks, targetPos.y,
                targetPos.z + targetVel.z * ticks);
    }

    /**
     * Rotate a horizontal unit vector about the vertical axis. Local copy of the shared helper so
     * this class stays pure and self-checkable without dragging in the targeting core.
     */
    public static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    /**
     * The entity yaw that points a hull along a horizontal direction.
     *
     * <p><b>Yaw is not a bearing, and the two differ by a sign.</b> SBW's
     * {@code getForwardDirection()} is {@code (-sin(yRot), 0, cos(yRot))}, vanilla's convention, so
     * a hull facing +X sits at yaw -90 — while the compass bearing this codebase stores in
     * {@code TAG_APPROACH_YAW} and in an airport's heading is {@code atan2(x, z)}, which is +90 for
     * the same direction. The two agree for a north-south strip and are exactly reversed for an
     * east-west one, which is the sort of bug that looks like "it works on my runway".
     */
    public static float yawFromDirection(Vec3 dir) {
        if (dir == null || dir.lengthSqr() < EPS) return 0.0F;
        return (float) Mth.wrapDegrees(-Math.toDegrees(Math.atan2(dir.x, dir.z)));
    }

    /** The entity yaw for a stored compass bearing. See {@link #yawFromDirection}. */
    public static float yawFromBearingDeg(double bearingDeg) {
        return (float) Mth.wrapDegrees(-bearingDeg);
    }

    /** Unit horizontal direction for a stored compass bearing. */
    public static Vec3 directionFromBearingDeg(double bearingDeg) {
        double rad = Math.toRadians(bearingDeg);
        return new Vec3(Math.sin(rad), 0.0, Math.cos(rad));
    }

    /** Smallest absolute angle (degrees) between two horizontal directions. */
    public static double headingErrorDeg(Vec3 from, Vec3 to) {
        double a = Math.atan2(from.x, from.z);
        double b = Math.atan2(to.x, to.z);
        return Math.abs(Mth.degreesDifference((float) Math.toDegrees(a), (float) Math.toDegrees(b)));
    }
}
