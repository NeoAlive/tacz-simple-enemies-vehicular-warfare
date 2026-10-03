package com.neoalive.tacz_sewv.heli.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Attitude quaternion &lt;-&gt; the Euler angles SBW stores and renders a hull with.
 *
 * <p>The convention is read off SBW's own render transform
 * ({@code VehicleVecUtils.getVehicleYOffsetTransform}: rotate Y by {@code -yRot}, then X by
 * {@code xRot}, then Z by {@code roll}), so {@code R(q) = R_Y(-yaw) R_X(pitch) R_Z(roll)}.
 * Body frame: +Z nose, +Y mast, +X port. Positive pitch is nose down (vanilla xRot), positive
 * roll puts the starboard side down, and yaw is vanilla yRot (increasing turns the nose to
 * starboard, so yaw 90 faces west). Angles are degrees on the entity and wrapped to
 * (-180, 180], the same wrap SBW's {@code baseTick} applies.
 *
 * <p>Pure: no Minecraft types, {@link StrictMath} for every transcendental so results are
 * bit-reproducible. JOML is used for algebra only.
 */
public final class McPose {

    /** Entity Euler angles in degrees. */
    public record Euler(double yawDeg, double pitchDeg, double rollDeg) {}

    /** Past this |nose.y| the nose is vertical and yaw/roll are not separable. */
    private static final double GIMBAL = 1.0 - 1.0E-9;

    private McPose() {}

    /** Body-to-world rotation for the given entity angles. */
    public static Quaterniond toQuat(double yawDeg, double pitchDeg, double rollDeg) {
        Quaterniond q = axis(1, -StrictMath.toRadians(yawDeg));
        q.mul(axis(0, StrictMath.toRadians(pitchDeg)));
        q.mul(axis(2, StrictMath.toRadians(rollDeg)));
        return q;
    }

    /**
     * Entity angles for {@code q}. {@code prev} supplies yaw and roll when the nose is vertical
     * (gimbal lock), and may be null there, in which case they become 0. Unreachable in flight,
     * since the controller's tilt limit is far below 90 degrees.
     */
    public static Euler toEuler(Quaterniond q, Euler prev) {
        Vector3d f = q.transform(new Vector3d(0.0, 0.0, 1.0));
        double pitch = StrictMath.toDegrees(StrictMath.asin(Math.max(-1.0, Math.min(1.0, -f.y))));
        if (Math.abs(f.y) > GIMBAL) {
            return new Euler(prev == null ? 0.0 : prev.yawDeg(), pitch, prev == null ? 0.0 : prev.rollDeg());
        }
        Vector3d l = q.transform(new Vector3d(1.0, 0.0, 0.0));
        Vector3d u = q.transform(new Vector3d(0.0, 1.0, 0.0));
        double yaw = StrictMath.toDegrees(StrictMath.atan2(-f.x, f.z));
        double roll = StrictMath.toDegrees(StrictMath.atan2(l.y, u.y));
        return new Euler(wrapDeg(yaw), pitch, wrapDeg(roll));
    }

    /** Wrap into (-180, 180]. */
    public static double wrapDeg(double deg) {
        double a = deg % 360.0;
        if (a > 180.0) a -= 360.0;
        else if (a <= -180.0) a += 360.0;
        return a;
    }

    /** Unit rotation about a body axis (0 = X, 1 = Y, 2 = Z) by {@code rad}, right-hand rule. */
    private static Quaterniond axis(int axis, double rad) {
        double s = StrictMath.sin(rad * 0.5);
        double c = StrictMath.cos(rad * 0.5);
        return new Quaterniond(axis == 0 ? s : 0.0, axis == 1 ? s : 0.0, axis == 2 ? s : 0.0, c);
    }
}
