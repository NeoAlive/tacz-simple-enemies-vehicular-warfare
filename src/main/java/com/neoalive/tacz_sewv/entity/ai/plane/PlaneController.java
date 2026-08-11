package com.neoalive.tacz_sewv.entity.ai.plane;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.support.AirframeSupport;

/**
 * The only thing in the fixed-wing stack that writes an input. Everything above it decides;
 * this converts a decision — a bearing, an attitude, a throttle setting — into the sticks and
 * flags SBW reads, and nothing else.
 *
 * <p>Concentrating the writes here is not tidiness. SBW decays the mouse sticks by 0.95 every
 * tick, so an input is a thing you must keep saying, and a branch that "does nothing" is a branch
 * that releases the controls mid-flight. With one writer it is checkable that every path through a
 * tick issues a complete set; with the writes spread across a dozen methods it was not, and the
 * aircraft coasted whenever a branch forgot.
 *
 * <p>Sign conventions are SBW's and are shared with the helicopter goal on purpose: positive
 * {@code mouseMoveSpeedX} increases yaw, positive {@code xRot} is nose <b>down</b>. A climb is
 * therefore a negative commanded attitude. {@link VehicleTargeting#signedAngleTo} is signed the
 * other way round, hence the negation in {@link #steerYaw}.
 */
public final class PlaneController {

    // --- Steering gains (proportional, re-asserted every tick against the x0.95 decay) ---
    private static final double YAW_STICK_PER_DEG = 0.6;
    private static final float MAX_YAW_STICK = 25.0F;
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 28.0F;

    /** Degrees of pitch commanded per block of altitude error. */
    private static final double ALT_PITCH_PER_BLOCK = 2.0;
    /** Attitude bound for ordinary altitude holding — steeper is a deliberate manoeuvre. */
    public static final float MAX_CRUISE_PITCH_DEG = 20.0F;

    private final VehicleEntity vehicle;

    public PlaneController(VehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    /**
     * Engine on, everything else off. Lift on a fixed wing comes from airspeed, so this is the
     * normal state of affairs and the throttle is never cut except in the flare.
     */
    public void throttleUp() {
        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(false);
    }

    /**
     * Reduced power as a duty cycle, because SBW's throttle is a boolean and there is no partial
     * setting. Same trick the ship goal uses through a turn: pulsing settles at an equilibrium
     * against drag instead of running the speed down to a stall.
     *
     * @param onTicks engine-on ticks out of every {@code period}
     */
    public void throttleDuty(long gameTime, int period, int onTicks) {
        boolean on = Math.floorMod(gameTime, Math.max(period, 1)) < Math.max(onTicks, 1);
        this.vehicle.setForwardInputDown(on);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    }

    /** Air brake — {@code downInput} is what actually sheds speed on an SBW aircraft. */
    public void airbrake(boolean on) {
        this.vehicle.setDownInputDown(on);
    }

    /** Throttle closed and braking: only correct in the flare, where the wing is done working. */
    public void idleAndBrake() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(true);
    }

    public void gear(boolean up) {
        this.vehicle.setGearUp(up);
    }

    /** Yaw toward a horizontal bearing at full rate. */
    public void steerYaw(Vec3 aim) {
        steerYaw(aim, 1.0);
    }

    /**
     * Yaw toward a bearing. {@code rateScale} below 1 lowers both the gain and the saturation,
     * which widens the turn: a heavy airframe's momentum lags its nose, so a hard stick just skids
     * the hull sideways through the turn instead of taking it round.
     */
    public void steerYaw(Vec3 aim, double rateScale) {
        if (aim == null || aim.lengthSqr() <= 1.0E-8) {
            this.vehicle.setMouseMoveSpeedX(0.0F);
            return;
        }
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, aim));
        double maxStick = MAX_YAW_STICK * rateScale;
        this.vehicle.setMouseMoveSpeedX(
                (float) Mth.clamp(-YAW_STICK_PER_DEG * rateScale * yawErrDeg, -maxStick, maxStick));
    }

    /** Stop yawing — wings level. Still an instruction, still issued every tick. */
    public void holdHeading() {
        this.vehicle.setMouseMoveSpeedX(0.0F);
    }

    /** Drive the hull's pitch toward an attitude, closed against its live {@code xRot}. */
    public void commandPitch(float targetXRotDeg) {
        float err = targetXRotDeg - this.vehicle.getXRot();
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(err * PITCH_STICK_PER_DEG, -MAX_PITCH_STICK, MAX_PITCH_STICK));
    }

    /** Hold an altitude: below it, nose up (negative attitude); above it, nose down. */
    public void holdAltitude(double desiredY) {
        commandPitch(altitudePitch(desiredY));
    }

    /** The attitude an altitude error asks for, bounded to a gentle cruise angle. */
    public float altitudePitch(double desiredY) {
        return altitudePitch(desiredY, this.vehicle.getY());
    }

    /** Pure form, so the self-check can pin the sign: nose up when below the target level. */
    public static float altitudePitch(double desiredY, double currentY) {
        double altErr = desiredY - currentY;
        return (float) Mth.clamp(-altErr * ALT_PITCH_PER_BLOCK,
                -MAX_CRUISE_PITCH_DEG, MAX_CRUISE_PITCH_DEG);
    }

    /** Let go of everything — only correct when the aircraft is parked or already falling. */
    public void release() {
        AirframeSupport.releaseInputs(this.vehicle);
    }

    public VehicleEntity vehicle() {
        return this.vehicle;
    }
}
