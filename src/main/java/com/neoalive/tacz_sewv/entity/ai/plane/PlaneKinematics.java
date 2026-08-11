package com.neoalive.tacz_sewv.entity.ai.plane;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.entity.ai.support.AirframeSupport;

/**
 * What the airframe is actually doing this tick — speed, climb rate, height above ground, and the
 * turn rate it has been managing. Reads only; it decides nothing and steers nothing.
 *
 * <p>The turn rate is <b>measured, not assumed</b>. SBW's {@code aircraftEngine} derives yaw from
 * the stick, the airspeed and the hull's own agility stats, so no constant in this mod can predict
 * the radius a given plane will actually fly. Every geometry that has to fit inside a turn — the
 * hold orbit, the approach pattern, when to break off a run — sizes itself off
 * {@link #turnRadius()}, so a nimble jet flies a tight circuit and a heavy bomber a wide one with
 * no per-hull tuning.
 */
public final class PlaneKinematics {

    /**
     * Yaw rate floor (deg/tick). A plane flying dead straight measures ~0 and would imply an
     * infinite turn radius, which then sizes an orbit larger than the world. This is the "it can at
     * least manage this much" backstop, not a model of any hull.
     */
    private static final double MIN_YAW_RATE_DEG = 0.6;
    /** Seed before anything has been observed — a mild, safe turn rate. */
    private static final double INITIAL_YAW_RATE_DEG = 1.6;
    /** The peak decays this fast (deg/tick per tick) so a one-off spike does not define the hull. */
    private static final double PEAK_DECAY_DEG = 0.01;
    /** Radius band. Below the floor the geometry degenerates; above the ceiling it is unflyable. */
    public static final double MIN_TURN_RADIUS = 24.0;
    public static final double MAX_TURN_RADIUS = 400.0;

    private double speed;
    private double climbRate;
    private double altitudeAgl;
    private Vec3 forwardFlat = new Vec3(0, 0, 1);
    private double yawDeg;
    private double peakYawRateDeg = INITIAL_YAW_RATE_DEG;
    private long lastTick = Long.MIN_VALUE;
    private int surfaceBelow;

    /** Forget the hull's measured agility — a different airframe tells us nothing about this one. */
    public void reset() {
        this.peakYawRateDeg = INITIAL_YAW_RATE_DEG;
        this.lastTick = Long.MIN_VALUE;
        this.speed = 0.0;
        this.climbRate = 0.0;
    }

    public void sample(VehicleEntity vehicle, long gameTime) {
        Vec3 vel = vehicle.getDeltaMovement();
        this.speed = vel.horizontalDistance();
        this.climbRate = vel.y;
        this.surfaceBelow = AirframeSupport.surfaceBelow(vehicle);
        this.altitudeAgl = vehicle.getY() - this.surfaceBelow;

        Vector3f f = vehicle.getForwardDirection();
        Vec3 flat = new Vec3(f.x(), 0, f.z());
        this.forwardFlat = flat.lengthSqr() > 1.0E-8 ? flat.normalize() : new Vec3(0, 0, 1);

        double yaw = vehicle.getYRot();
        if (this.lastTick != Long.MIN_VALUE && gameTime > this.lastTick) {
            // Goals can miss ticks (chunk edges, goal rebuilds); divide by the real elapsed time
            // rather than assuming one, or a resumed goal reads a huge phantom turn rate.
            double dt = Math.min(gameTime - this.lastTick, 20L);
            double rate = Math.abs(Mth.degreesDifference((float) this.yawDeg, (float) yaw)) / dt;
            this.peakYawRateDeg = Math.max(rate, this.peakYawRateDeg - PEAK_DECAY_DEG * dt);
        }
        this.yawDeg = yaw;
        this.lastTick = gameTime;
    }

    /** Horizontal speed, blocks/tick. */
    public double speed() {
        return this.speed;
    }

    /** Vertical speed, blocks/tick; negative is a descent. */
    public double climbRate() {
        return this.climbRate;
    }

    /** Descent rate as a positive number, 0 while level or climbing. */
    public double sinkRate() {
        return Math.max(0.0, -this.climbRate);
    }

    /** Height above the surface directly below. */
    public double agl() {
        return this.altitudeAgl;
    }

    public int surfaceBelow() {
        return this.surfaceBelow;
    }

    /** Unit horizontal nose direction. */
    public Vec3 forwardFlat() {
        return this.forwardFlat;
    }

    /** Best turn rate (deg/tick) seen recently — the hull's demonstrated agility. */
    public double peakYawRateDeg() {
        return this.peakYawRateDeg;
    }

    /** Radius of the tightest circle this hull is currently flying, in blocks. */
    public double turnRadius() {
        return turnRadius(this.speed, this.peakYawRateDeg);
    }

    /**
     * r = v / omega. Pure so the self-check can pin it: at 2 blocks/tick and 2 deg/tick a plane
     * needs ~57 blocks of radius, which is why a 32-block helicopter orbit is not a thing a jet
     * can fly and why the old fixed loiter stick drifted instead of holding.
     */
    public static double turnRadius(double speed, double yawRateDegPerTick) {
        double omega = Math.toRadians(Math.max(yawRateDegPerTick, MIN_YAW_RATE_DEG));
        return Mth.clamp(Math.max(speed, 0.0) / omega, MIN_TURN_RADIUS, MAX_TURN_RADIUS);
    }
}
