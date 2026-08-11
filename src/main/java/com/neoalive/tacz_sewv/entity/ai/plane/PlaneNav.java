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
     * Rotate a horizontal unit vector about the vertical axis. Local copy of the shared helper so
     * this class stays pure and self-checkable without dragging in the targeting core.
     */
    public static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    /** Smallest absolute angle (degrees) between two horizontal directions. */
    public static double headingErrorDeg(Vec3 from, Vec3 to) {
        double a = Math.atan2(from.x, from.z);
        double b = Math.atan2(to.x, to.z);
        return Math.abs(Mth.degreesDifference((float) Math.toDegrees(a), (float) Math.toDegrees(b)));
    }
}
