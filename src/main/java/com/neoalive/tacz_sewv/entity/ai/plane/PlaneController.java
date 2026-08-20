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

    /**
     * Brake ticks out of every {@link #BRAKE_PERIOD}, for {@link #airbrakeDuty}.
     *
     * <p>The two numbers are read straight off the arithmetic below. With the throttle open every
     * tick and the brake held on one tick in {@code P}, the power setting settles where
     * {@code p = (p + P * 0.006 * increment) * 0.97}, i.e. at {@code 0.194 * P * increment}. So
     * {@code P = 1} (the brake simply held) pins an aircraft at <b>19%</b> of its own throttle,
     * {@code P = 3} at 58%, and {@code P = 4} at <b>78%</b>. Four is the value here because the
     * failure it is guarding against is an aircraft that cannot hold altitude, and losing a fifth
     * of the thrust is a margin an unknown airframe can absorb where losing four fifths is not.
     */
    private static final int BRAKE_PERIOD = 4;

    private static final int BRAKE_ON_TICKS = 1;

    /**
     * Air brake, held. Two things happen while {@code downInput} is down, and only the first is
     * the one this was wanted for: the engine reads {@code resistance * 1.5} instead of
     * {@code resistance}, and it decays {@code power *= 0.97} <b>every tick</b>.
     *
     * <p>Those two are not the same order of magnitude, and the second is the one that bites. Drag
     * is {@code 0.96 - 0.0017 * resistance * speed^2}, so at the 1-3 blocks/tick these aircraft
     * actually fly the extra half of a default resistance of 1 is worth a few thousandths per tick
     * — nothing. The power decay is geometric and the throttle only answers it linearly
     * ({@code +0.006 * increment}), so holding the brake does not slow an aircraft so much as
     * <b>strangle its engine</b>, and where it settles is a property of SBW's constants rather
     * than of the airframe. An A-10 flies on a fifth of its thrust; a jet with a smaller wing
     * mushes, sinks, and is pitched up by the altitude loop into sinking faster.
     *
     * <p>So this form is for the cases that genuinely want the engine held down — the final
     * approach and the flare, where the wing is done working and arriving fast is the failure.
     * Anything still flying wants {@link #airbrakeDuty}.
     *
     * <p>Latched, like every SBW input: {@link #throttleUp()} is what releases it again.
     */
    public void airbrake(boolean on) {
        this.vehicle.setDownInputDown(on);
    }

    /**
     * Air brake as a duty cycle, for an aircraft that still has to fly afterwards.
     *
     * <p>Same trick as {@link #throttleDuty} and the ship goal's turn duty, and for the same
     * reason: SBW's brake is a boolean, so "less brake" can only be spelled as less often. Pulsing
     * it keeps the parts that were wanted — the resistance boost lands on the ticks it is held,
     * and {@code PLANE_BREAK} (the flaps) decays at 0.8/tick from a +10 step, so a quarter duty
     * settles them partly deployed rather than at the 60 cap — while leaving the power setting
     * near enough to open that the aircraft can still hold its altitude. See {@link #BRAKE_PERIOD}
     * for the arithmetic.
     */
    public void airbrakeDuty(long gameTime, boolean want) {
        this.vehicle.setDownInputDown(
                want && Math.floorMod(gameTime, BRAKE_PERIOD) < BRAKE_ON_TICKS);
    }

    /** Throttle closed and braking: only correct in the flare, where the wing is done working. */
    public void idleAndBrake() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(true);
    }

    /**
     * The one control surface with a physics consequence, not just a visual one: SBW treats a hard
     * ground contact with the gear even partway retracted as a strike impact (damage + bounce)
     * rather than a landing, and separately forces it back down ({@code gearUp = false}) every tick
     * the hull is already {@code onGround()} regardless of what is commanded here. So
     * {@code gear(false)} while still airborne on approach is what actually matters — the
     * ground-contact case is belt and braces.
     */
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

    /**
     * Point the <b>weapon</b> at a world point, rather than the nose at one.
     *
     * <p>The distinction is the difference between hitting and not. A gun sits forward of the hull
     * origin and is usually canted a degree or two off the hull axis, so an attitude computed from
     * where the hull is and where the target is leaves a constant error on the shot — invisible in
     * the flight model, fatal inside a fire gate measured in single degrees. Here the error between
     * the live gun line and the line to the aim point is measured directly and driven to zero, so
     * whatever the barrel geometry is, it cancels.
     *
     * @param shootDir the weapon's firing direction in world space, from SBW
     * @param toAim muzzle to aim point, in world space
     * @param minPitch commanded attitude floor (negative is nose up)
     * @param maxPitch commanded attitude ceiling (positive is nose down)
     */
    public void trackGunLine(Vec3 shootDir, Vec3 toAim, float minPitch, float maxPitch) {
        steerYaw(new Vec3(toAim.x, 0.0, toAim.z));
        double err = PlaneNav.gunPitchErrorDeg(shootDir, toAim);
        commandPitch((float) Mth.clamp(this.vehicle.getXRot() + err, minPitch, maxPitch));
    }

    /** Hold an altitude: below it, nose up (negative attitude); above it, nose down. */
    public void holdAltitude(double desiredY) {
        commandPitch(altitudePitch(desiredY));
    }

    /**
     * Hold an altitude, but allow the descent to be steeper than the cruise bound.
     *
     * <p>{@link #MAX_CRUISE_PITCH_DEG} is the right ceiling for transiting between two heights and
     * the wrong one for arriving somewhere: an aircraft coming down onto a roll-in height at the
     * gentle cruise angle is still stepping down when it gets there, and the nose then has to go
     * over at the last moment. Raising only the nose-<b>down</b> half leaves climbs alone, which
     * matters because a climb is limited by thrust and a descent is not.
     */
    public void holdAltitude(double desiredY, float maxNoseDownDeg) {
        double altErr = desiredY - this.vehicle.getY();
        commandPitch((float) Mth.clamp(-altErr * ALT_PITCH_PER_BLOCK,
                -MAX_CRUISE_PITCH_DEG, maxNoseDownDeg));
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
