package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Autopilot for SuperbWarfare helicopters. Flight model, deliberately simple:
 *
 * <ul>
 * <li><b>Consistent flight level.</b> Takeoff (L) captures a flight Y from the
 *     takeoff origin's ground + the configured cruise altitude (clamped 30-50) and
 *     the aircraft holds THAT level everywhere — idle hover, transits, combat. No
 *     terrain-following; obstacles are handled by the whiskers instead.</li>
 * <li><b>Whisker avoidance.</b> Every lateral leg probes actual blocks at hull
 *     height along the desired bearing and fans out to alternate bearings when
 *     blocked (yaw avoidance, same pattern as DriveVehicleGoal's ground whiskers);
 *     a fully-blocked forward cone answers with a climb (vertical avoidance).</li>
 * <li><b>Combat = aim platform, no revolution.</b> Close to the engage radius,
 *     hold there, and pitch the nose down onto the target — SBW's AI fire loop
 *     shoots a fixed-gun seat only when the weapon bears within 4°, and pitching
 *     is what brings it to bear. Aim-pitching happens EXCLUSIVELY in combat;
 *     everywhere else the hull flies gentle, bounded attitudes.</li>
 * <li><b>FOLLOW_COMMANDER</b> parks the aircraft over the commander's X/Z at the
 *     nearest level above them (never below flight level, never closer than a
 *     fixed clearance over their head).</li>
 * <li><b>Landing (CTRL+L)</b> flies to the designated block, descends on top of
 *     it, and enters the sticky LANDED state until a new takeoff.</li>
 * </ul>
 *
 * <p>Control plumbing (from SBW's {@code helicopterEngine}): {@code forwardInput}
 * is the collective (climb), {@code downInput} descends, {@code mouseMoveSpeedY}
 * pitches (positive = nose down), {@code mouseMoveSpeedX} yaws (positive = yaw
 * increases). Analog sticks decay ×0.95/tick and are raw mouse-delta scale (tens,
 * not ±1), so every input is re-asserted every tick at realistic magnitudes.
 */
public class DriveHelicopterGoal extends Goal {

    // Below this fraction of max health the engine takes over with a crash-spin —
    // nothing the pilot inputs matters, so we stop fighting it.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;

    // --- Transit sticks (gentle, bounded — non-combat flight never dives) ---
    private static final double YAW_STICK_PER_DEG = 0.5;
    private static final float MAX_YAW_STICK = 20.0F;      // ≈2.2°/tick yaw at saturation
    private static final double ALIGN_THRESHOLD_DEG = 35.0;
    private static final double PITCH_DEG_PER_SPEED_ERR = 40.0;
    private static final float MAX_ATTITUDE_DEG = 20.0F;   // transit tilt ceiling
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 15.0F;
    // Velocity-error guidance: desired closing speed tapers with distance so the
    // hull brakes onto the point; error behind the nose is answered with nose-up
    // reverse thrust, which is what kills tangential drift (no circling).
    private static final double APPROACH_GAIN = 0.1;
    private static final double VEL_ERR_DEADBAND = 0.03;
    private static final double ERR_BEHIND_DEG = 100.0;

    // --- Collective (vertical) ---
    private static final double CLIMB_RATE_CAP = 0.22;
    private static final double DESCEND_RATE_CAP = 0.22;

    // --- Flight level ---
    // The takeoff-origin offset is the config value hard-clamped to this band.
    private static final double MIN_FLIGHT_ALT = 30.0;
    private static final double MAX_FLIGHT_ALT = 50.0;
    // Never fly a leg below destination + this (e.g. stay above the followed player).
    private static final double MIN_OVER_DEST = 12.0;

    // --- Whiskers (block avoidance at hull height, cf. DriveVehicleGoal) ---
    private static final double[] WHISKER_OFFSETS_DEG = {0.0, 25.0, -25.0, 50.0, -50.0, 75.0, -75.0};
    private static final double WHISKER_DISTANCE = 12.0;
    // Fully boxed in: pop up this far above the obstacle line and try again. The
    // avoidance floor decays ~1 block/s afterwards so surplus altitude is given
    // back gradually (and re-triggers cleanly if the obstacle is tall).
    private static final double AVOID_CLIMB_STEP = 4.0;
    private static final double AVOID_FLOOR_DECAY = 0.05;

    // --- Combat aim band ---
    private static final double ENGAGE_DEADBAND = 4.0;
    private static final double BREAK_RANGE = 14.0;
    // Aim attitude limits: deep dives allowed (target far below), slight nose-up
    // for targets above the flight level.
    private static final float MAX_COMBAT_DIVE_DEG = 60.0F;
    private static final float MAX_CLIMB_AIM_DEG = 15.0F;
    // Aiming runs under hover mode (drift damped, auto-level active), which scales
    // pitch authority to 0.2× — the aim sticks are proportionally stronger.
    private static final double AIM_STICK_PER_DEG = 2.0;
    private static final float MAX_AIM_PITCH_STICK = 90.0F;
    private static final float MAX_AIM_YAW_STICK = 40.0F;

    // --- Arrival ---
    private static final double ARRIVE_RADIUS = 4.0;
    private static final double LAND_OVER_RADIUS = 3.5;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    private VehicleEntity vehicle;
    // The consistent Y level flown after takeoff. NaN until a takeoff (or first
    // airborne need) establishes it; cleared on landing/dismount.
    private double flightY = Double.NaN;
    // Temporary extra floor raised by the vertical whisker; decays back down.
    private double avoidFloorY = Double.NaN;

    private VehicleEntity helicopterCacheVehicle;
    private boolean helicopterCacheValue;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) flies — same driver/commander model as the ground goal.
        if (v.getFirstPassenger() != this.unit) return false;
        this.vehicle = v;
        // Run whenever mounted in a helicopter, even with no destination: a helicopter
        // must be actively controlled to hold station, so "idle" means "hover", not "off".
        return isHelicopter();
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && isHelicopter();
    }

    @Override
    public void stop() {
        if (this.vehicle != null) releaseInputs();
        this.vehicle = null;
        this.flightY = Double.NaN;
        this.avoidFloorY = Double.NaN;
        this.allyAssist.clear();
    }

    @Override
    public void tick() {
        // Sub-10% health: the engine flies it into the ground on its own. Let go.
        float max = this.vehicle.getMaxHealth();
        if (max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION) {
            releaseInputs();
            return;
        }
        // No power to the rotor — inputs do nothing anyway; don't pretend to fly.
        if (this.vehicle.getEnergy() <= 0) {
            releaseInputs();
            return;
        }

        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;

        // LANDED is sticky: stay shut down on the ground — no hover, no order-driven
        // flying — until the player issues a new takeoff (L) or landing (CTRL+L).
        if (command == IHelicopterPilot.HELI_CMD_LANDED) {
            releaseInputs();
            this.vehicle.setHoverMode(false);
            this.flightY = Double.NaN;
            return;
        }

        // LANDING overrides everything, including a queued takeoff.
        if (command == IHelicopterPilot.HELI_CMD_LANDING) {
            BlockPos pad = pilot.sewv$getHeliLandPos();
            if (pad != null) {
                doLanding(pilot, pad);
                return;
            }
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE); // nothing to land on — drop the order
        }

        // TAKEOFF: capture the flight level from the takeoff origin, climb straight
        // up to it, then clear the order and fall through to normal duty. On the
        // ground the origin is the hull's own Y (correct even on rooftops); mid-air
        // re-anchor off the terrain below instead.
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
            if (Double.isNaN(this.flightY) || this.vehicle.onGround()) {
                double originY = this.vehicle.onGround() ? this.vehicle.getY() : surfaceBelow();
                this.flightY = originY + flightAltitude();
            }
            if (this.vehicle.getY() >= this.flightY - SewvConfig.HELI_ALT_DEADBAND.get()) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            } else {
                climbVertically(this.flightY);
                return;
            }
        }

        // Airborne with no established level (autonomous RU/US crews, world reload):
        // anchor the flight level off the ground currently below.
        if (Double.isNaN(this.flightY)) {
            this.flightY = surfaceBelow() + flightAltitude();
        }

        // Combat: no revolution — close to the engage ring and become an aim
        // platform. The ONLY place the nose is pitched onto something.
        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null) {
            combatTick(combatTarget);
            return;
        }

        // Order-driven movement (move-to, follow, formation, ally assist) or idle hover.
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (dest == null) {
            holdHover(flightLevel());
            return;
        }
        double dx = dest.getX() + 0.5 - this.vehicle.getX();
        double dz = dest.getZ() + 0.5 - this.vehicle.getZ();
        if (dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            // Arrived — hold overhead. For FOLLOW this is "the nearest level above
            // the commander": never below flight level, never on top of their head.
            holdHover(Math.max(flightLevel(), dest.getY() + MIN_OVER_DEST));
        } else {
            flyToward(dest.getX() + 0.5, dest.getZ() + 0.5,
                    Math.max(flightLevel(), dest.getY() + MIN_OVER_DEST));
        }
    }

    // Combat: outside the ring close in; inside the break range back out; in the
    // band, hold position and pitch the nose onto the target so the guns bear
    // (SBW's AI fire loop shoots once the weapon is within 4° of the target).
    private void combatTick(LivingEntity target) {
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double engage = SewvConfig.HELI_ENGAGE_RADIUS.get();

        if (horizDist > engage + ENGAGE_DEADBAND) {
            flyToward(target.getX(), target.getZ(),
                    Math.max(flightLevel(), target.getY() + MIN_OVER_DEST));
            return;
        }
        if (horizDist < BREAK_RANGE) {
            // Aim drift carried us too close — open back out to the ring.
            BlockPos out = VehicleTargeting.computeStandoffPoint(
                    this.vehicle, target.blockPosition(), engage);
            flyToward(out.getX() + 0.5, out.getZ() + 0.5, flightLevel());
            return;
        }
        aimAtTarget(target, horizDist);
    }

    // Stationary aim platform: hover mode damps the drift the aim tilt causes and
    // auto-levels roll, the collective holds the flight level, and the sticks put
    // the nose on the target — yaw to its bearing, pitch to its depression angle.
    private void aimAtTarget(LivingEntity target, double horizDist) {
        applyCollective(flightLevel());
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(true);

        Vec3 dir = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        if (dir.lengthSqr() > 1.0E-6) dir = dir.normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, dir));
        // Hover mode halves yaw authority — the aim yaw stick is stronger for it.
        this.vehicle.setMouseMoveSpeedX(
                (float) Mth.clamp(-YAW_STICK_PER_DEG * 2.0 * yawErrDeg, -MAX_AIM_YAW_STICK, MAX_AIM_YAW_STICK));

        // Depression to the target's center (positive = below us = nose down, the
        // same sign as xRot). Only pitch over once roughly on bearing.
        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(this.vehicle.getY() - targetCenterY, horizDist));
        float aimAttitude = Math.abs(yawErrDeg) < ALIGN_THRESHOLD_DEG
                ? (float) Mth.clamp(depressionDeg, -MAX_CLIMB_AIM_DEG, MAX_COMBAT_DIVE_DEG)
                : 0.0F;
        float attitudeErr = aimAttitude - this.vehicle.getXRot();
        // Hover mode scales pitch authority to 0.2× and auto-levels against us, so
        // the aim stick runs at mouse-flick magnitudes.
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(attitudeErr * AIM_STICK_PER_DEG, -MAX_AIM_PITCH_STICK, MAX_AIM_PITCH_STICK));
    }

    // Land on the chosen pad: fly over it at a safe level, then descend straight
    // down (hover mode damping the lateral drift) until settled → sticky LANDED.
    private void doLanding(IHelicopterPilot pilot, BlockPos pad) {
        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        double padTopY = pad.getY() + 1.0;

        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        if (dx * dx + dz * dz > LAND_OVER_RADIUS * LAND_OVER_RADIUS) {
            // Not over the pad yet — approach at the flight level (or a safe height
            // above the pad if it sits higher than the flight level).
            double approachY = Math.max(
                    Double.isNaN(this.flightY) ? padTopY + flightAltitude() : this.flightY,
                    padTopY + MIN_OVER_DEST);
            flyToward(px, pz, approachY);
            return;
        }

        // Over the pad. Touchdown → sticky LANDED; the hull stays down until a new
        // takeoff order rather than immediately resuming FOLLOW/MOVE orders.
        if (this.vehicle.onGround() || this.vehicle.getY() <= padTopY + 0.35) {
            releaseInputs();
            this.vehicle.setHoverMode(false);
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
            pilot.sewv$setHeliLandPos(null);
            this.flightY = Double.NaN;
            this.avoidFloorY = Double.NaN;
            return;
        }

        // Controlled vertical descent over the pad.
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setDownInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // The one lateral primitive: whisker-check the bearing, then fly velocity-error
    // guidance along the clear direction while the collective holds desiredY.
    // Desired velocity tapers with distance so the hull decelerates onto the point;
    // steering at the velocity ERROR (not the point) actively brakes sideways drift,
    // which is what prevents endless circling around a destination.
    private void flyToward(double steerX, double steerZ, double desiredY) {
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double dx = steerX - this.vehicle.getX();
        double dz = steerZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dirToDest = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : Vec3.ZERO;

        // Whiskers: fly the nearest clear bearing to the desired one. A fully
        // blocked cone means terrain taller than the flight level dead ahead —
        // answer vertically: hold and pop above it (the "pitch" whisker).
        Vec3 travelDir = chooseClearBearing(dirToDest);
        if (travelDir == null) {
            this.avoidFloorY = this.vehicle.getY() + AVOID_CLIMB_STEP;
            holdHover(this.avoidFloorY);
            return;
        }

        applyCollective(withAvoidFloor(desiredY));
        this.vehicle.setHoverMode(false); // full control authority while moving

        double desiredSpeed = Math.min(SewvConfig.HELI_CRUISE_SPEED.get(), dist * APPROACH_GAIN);
        Vec3 vel = this.vehicle.getDeltaMovement();
        double evx = travelDir.x * desiredSpeed - vel.x;
        double evz = travelDir.z * desiredSpeed - vel.z;
        double errMag = Math.sqrt(evx * evx + evz * evz);

        Vector3f forward = this.vehicle.getForwardDirection().normalize();

        // Flying the requested profile — keep the nose on the way ahead, level out.
        if (errMag < VEL_ERR_DEADBAND) {
            steerNose(forward, travelDir, 0.0F);
            return;
        }

        Vec3 errDir = new Vec3(evx / errMag, 0, evz / errMag);
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, errDir));
        float attitudeMag = (float) Mth.clamp(errMag * PITCH_DEG_PER_SPEED_ERR, 0.0, MAX_ATTITUDE_DEG);

        if (Math.abs(yawErrDeg) <= ERR_BEHIND_DEG) {
            // Error ahead: nose toward it, gentle pitch into it once roughly aligned.
            steerNose(forward, errDir,
                    Math.abs(yawErrDeg) < ALIGN_THRESHOLD_DEG ? attitudeMag : 0.0F);
        } else {
            // Error behind the hull (braking / killing sideways drift): keep the nose
            // where it is and pitch UP to thrust backward — no 180° pirouette.
            Vec3 back = new Vec3(-errDir.x, 0, -errDir.z);
            double backErrDeg = Math.toDegrees(getAngleBetween(forward, back));
            steerNose(forward, back,
                    Math.abs(backErrDeg) < ALIGN_THRESHOLD_DEG ? -attitudeMag : 0.0F);
        }
    }

    // Whisker fan (cf. DriveVehicleGoal.chooseClearBearing): prefer the goal bearing,
    // fan out to alternating flanks, null when the whole forward cone is blocked.
    private Vec3 chooseClearBearing(Vec3 desired) {
        if (desired.lengthSqr() < 1.0E-8) return desired;
        for (double offDeg : WHISKER_OFFSETS_DEG) {
            Vec3 cand = rotateY(desired, Math.toRadians(offDeg));
            if (headingClear(cand, WHISKER_DISTANCE)) return cand;
        }
        return null;
    }

    // True if flying `distance` blocks along `dir` keeps the hull slab (its height,
    // with a 1-block margin above and below) free of collidable blocks. This is the
    // airborne analogue of the ground goal's headingClear — actual block probes, not
    // heightmaps, so overhangs, arches and interiors are judged correctly.
    private boolean headingClear(Vec3 dir, double distance) {
        Level level = this.unit.level();
        double sx = this.vehicle.getX();
        double sz = this.vehicle.getZ();
        int yBottom = Mth.floor(this.vehicle.getY()) - 1;
        int yTop = Mth.floor(this.vehicle.getY() + this.vehicle.getBbHeight()) + 1;
        double half = this.vehicle.getBbWidth() / 2.0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (double d = half + 1.0; d <= half + distance; d += 1.0) {
            int px = Mth.floor(sx + dir.x * d);
            int pz = Mth.floor(sz + dir.z * d);
            for (int y = yBottom; y <= yTop; y++) {
                if (!level.getBlockState(pos.set(px, y, pz)).getCollisionShape(level, pos).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    // Rotate a horizontal (y=0) direction about the vertical axis.
    private static Vec3 rotateY(Vec3 dir, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
    }

    // Hold a stationary hover at targetY: hover mode auto-levels and damps drift,
    // the collective trims the height, sticks stay centered.
    private void holdHover(double targetY) {
        applyCollective(withAvoidFloor(targetY));
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Pure vertical climb (takeoff): collective only, hover mode keeping it level
    // and drift-free so it goes straight up from the origin.
    private void climbVertically(double desiredY) {
        applyCollective(desiredY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Collective: climb toward desiredY, descend away from it, coast within the
    // deadband. Rate caps stop the bang-bang inputs from hunting up and down.
    // forwardInputDown is the collective on a helicopter, NOT translation.
    private void applyCollective(double desiredY) {
        double dy = desiredY - this.vehicle.getY();
        double deadband = SewvConfig.HELI_ALT_DEADBAND.get();
        double vy = this.vehicle.getDeltaMovement().y;
        boolean climb = dy > deadband && vy < CLIMB_RATE_CAP;
        boolean descend = dy < -deadband && vy > -DESCEND_RATE_CAP;
        this.vehicle.setForwardInputDown(climb);
        this.vehicle.setDownInputDown(descend);
    }

    // Inner loops shared by every profile: yaw stick proportional to the heading
    // error onto `aim`, pitch stick closed against the hull's actual xRot toward
    // the commanded attitude (positive = nose down). Yaw sign note: positive
    // mouseMoveSpeedX INCREASES yaw and getAngleBetween is signed the other way,
    // hence the negation — verified against SBW's helicopterEngine yaw update.
    private void steerNose(Vector3f forward, Vec3 aim, float targetAttitudeDeg) {
        if (aim.lengthSqr() > 1.0E-8) {
            double yawErrDeg = Math.toDegrees(getAngleBetween(forward, aim));
            this.vehicle.setMouseMoveSpeedX(
                    (float) Mth.clamp(-YAW_STICK_PER_DEG * yawErrDeg, -MAX_YAW_STICK, MAX_YAW_STICK));
        } else {
            this.vehicle.setMouseMoveSpeedX(0.0F);
        }
        float attitudeErr = targetAttitudeDeg - this.vehicle.getXRot();
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(attitudeErr * PITCH_STICK_PER_DEG, -MAX_PITCH_STICK, MAX_PITCH_STICK));
    }

    private void releaseInputs() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setDownInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
    }

    // The established flight level (callers guarantee it was anchored in tick()).
    private double flightLevel() {
        return this.flightY;
    }

    // The active hold height including the whisker climb floor, which decays about
    // a block per second so surplus avoidance altitude is given back gently.
    private double withAvoidFloor(double desiredY) {
        if (Double.isNaN(this.avoidFloorY)) return desiredY;
        this.avoidFloorY -= AVOID_FLOOR_DECAY;
        if (this.avoidFloorY <= desiredY) {
            this.avoidFloorY = Double.NaN;
            return desiredY;
        }
        return this.avoidFloorY;
    }

    // Takeoff-origin offset: the configured cruise altitude, hard-clamped to the
    // 30-50 band the flight model is designed around.
    private double flightAltitude() {
        return Mth.clamp(SewvConfig.HELI_CRUISE_ALTITUDE.get(), MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }

    private int surfaceBelow() {
        return this.unit.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, this.vehicle.getBlockX(), this.vehicle.getBlockZ());
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`; same
    // convention as DriveVehicleGoal so the steering signs match across goals.
    private double getAngleBetween(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    private boolean isHelicopter() {
        if (this.vehicle != this.helicopterCacheVehicle) {
            this.helicopterCacheVehicle = this.vehicle;
            this.helicopterCacheValue = this.vehicle.getEngineInfo() instanceof EngineInfo.Helicopter;
        }
        return this.helicopterCacheValue;
    }
}
