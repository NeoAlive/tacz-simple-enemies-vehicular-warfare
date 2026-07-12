package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Autopilot for SuperbWarfare helicopters (and, incidentally, fixed-wing — both
 * report {@link EngineInfo.Helicopter}). It takes off, flies to a destination in
 * 3D with active braking so it settles instead of overshooting, orbits a combat
 * target at standoff, lands on command, and hovers in place when idle. The
 * companion {@link DriveVehicleGoal} declines helicopters, so exactly one of the
 * two drives any given hull.
 *
 * <p><b>Control model (from SBW's {@code helicopterEngine}).</b> A helicopter's
 * {@code forwardInputDown} is the <i>collective</i> — it builds rotor power to
 * climb; {@code downInputDown} descends. Horizontal translation comes from
 * <i>pitch</i>: {@code mouseMoveSpeedY} noses the hull over so its lift vector
 * tilts and drags it that way (positive = nose-down = forward). Heading comes from
 * {@code mouseMoveSpeedX} (yaw). The analog inputs decay ×0.95 each tick and every
 * boolean input is force-cleared while there is no controlling passenger, so
 * <b>every input is re-asserted every tick.</b> The physics only responds while
 * airborne, with energy, and above ~10% health.
 *
 * <p><b>Command modes</b> (player-issued via the L / CTRL+L keybinds, carried on
 * {@link IHelicopterPilot}): LANDING overrides everything and sets the hull down on
 * the chosen block; TAKEOFF climbs to cruise altitude then clears itself; NONE
 * follows the SEM order queue (transit / orbit / hover).
 */
public class DriveHelicopterGoal extends Goal {

    // Below this fraction of max health the engine takes over with a crash-spin —
    // nothing the pilot inputs matters, so we stop fighting it.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;

    // Stick magnitudes. SBW's mouseMoveSpeedX/Y are raw mouse deltas — a player's
    // flick is tens of units, and the physics scales them way down (yaw rate =
    // 2.0 * mouseX * propellerRot(≈0.055) deg/tick; pitch rate = 1.5 * mouseY * prop).
    // Sticks clamped to ±1 (the first attempt) give ~0.1°/tick — the hull can't
    // meaningfully steer and momentum carries it off ("strays away"). These gains
    // produce a few degrees per tick at saturation, matching brisk player input.
    private static final double YAW_STICK_PER_DEG = 0.5;  // mouseX per degree of heading error
    private static final float MAX_YAW_STICK = 20.0F;     // ≈2.2°/tick yaw at saturation
    // Only pitch over toward the steer point once roughly pointed at it; while the
    // nose is still swinging, hold the hull level so it doesn't accelerate off-line.
    private static final double ALIGN_THRESHOLD_DEG = 35.0;
    // Outer loop: commanded nose-down attitude per (block/tick) of closing-speed
    // error, capped. Inner loop: pitch stick per degree of attitude error. Driving
    // ATTITUDE rather than raw stick self-limits the tilt and gives real braking
    // (nose-UP attitude when closing too fast).
    private static final double PITCH_DEG_PER_SPEED_ERR = 40.0;
    private static final float MAX_ATTITUDE_DEG = 20.0F;
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 15.0F;
    // Approach: desired closing speed = min(cruise, distance * APPROACH_GAIN), so the
    // hull eases to a stop over the last several blocks instead of barrelling through.
    private static final double APPROACH_GAIN = 0.1;
    // Below this velocity error (blocks/tick) the hull is flying the profile we asked
    // for — hold level and just keep the nose on the destination.
    private static final double VEL_ERR_DEADBAND = 0.03;
    // When the velocity error points more than this far behind the nose, don't swing
    // the hull around — pitch nose-UP and thrust backward instead. This is what kills
    // tangential drift (and with it the endless circling around a destination).
    private static final double ERR_BEHIND_DEG = 100.0;

    // Vertical-rate caps that stop the bang-bang collective from hunting up and down:
    // don't add climb while already rising this fast, nor descend while already sinking.
    private static final double CLIMB_RATE_CAP = 0.22;
    private static final double DESCEND_RATE_CAP = 0.22;

    // Look-ahead distances (blocks along the velocity vector) for terrain sampling:
    // cruise altitude tracks the highest ground AHEAD, not just under the hull, so a
    // hill is climbed before the hull reaches it instead of being flown into.
    private static final double[] TERRAIN_LOOKAHEAD = {6.0, 12.0, 18.0};

    // Orbit (turreted airframes): steer toward a point this far ahead (radians) along
    // the standoff circle so the yaw+thrust loop traces a smooth circle, flown at a
    // target-relative height so the turret's depression stays within its limits.
    private static final double ORBIT_LEAD_RAD = Math.toRadians(25.0);
    private static final double ORBIT_HEIGHT_ABOVE_TARGET = 12.0;

    // Attack runs (hull-fixed guns): SBW's AI fire loop only shoots when the weapon
    // bears within 4° of the target, and fixed guns shoot along the NOSE — so combat
    // dives the nose onto the target until the break range, then extends back out and
    // turns in again. The dive is aim-only; the collective still enforces a floor.
    private static final double ATTACK_BREAK_RANGE = 16.0;
    private static final double ATTACK_REENGAGE_RANGE = 48.0;
    private static final double ATTACK_HEIGHT_ABOVE_TARGET = 10.0;
    private static final float MAX_DIVE_DEG = 35.0F;
    private static final float MAX_CLIMB_AIM_DEG = 10.0F;

    // Minimum height above terrain in combat profiles, whatever the target-relative
    // height works out to.
    private static final double MIN_COMBAT_AGL = 8.0;

    // Horizontal distance at which a plain move is "arrived" and switches to a hover.
    private static final double ARRIVE_RADIUS = 4.0;
    // Landing: within this horizontal distance of the pad we're over it and descend.
    private static final double LAND_OVER_RADIUS = 3.5;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    // Fixed per-goal orbit direction so a flight of helicopters doesn't all circle the
    // same way and pile up on one arc.
    private final int orbitDir;

    private VehicleEntity vehicle;
    // Altitude to hold while hovering, captured on entry so an idle helicopter neither
    // sinks nor spontaneously climbs. NaN = not currently hovering.
    private double hoverTargetY = Double.NaN;
    // Attack-run phase: true while breaking off and extending back out to re-engage range.
    private boolean extending = false;

    private VehicleEntity helicopterCacheVehicle;
    private boolean helicopterCacheValue;

    // Cached like isHelicopter: whether the hull has a turret decides the combat
    // profile (orbit lets the turret aim; fixed guns need nose-on attack runs).
    private VehicleEntity turretCacheVehicle;
    private boolean turretCacheValue;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this.unit = unit;
        this.orbitDir = unit.getRandom().nextBoolean() ? 1 : -1;
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
        this.hoverTargetY = Double.NaN;
        this.extending = false;
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
        // Without this, a still-active FOLLOW/MOVE order would lift the hull right
        // back off the instant the landing sequence finished.
        if (command == IHelicopterPilot.HELI_CMD_LANDED) {
            releaseInputs();
            this.vehicle.setHoverMode(false);
            this.hoverTargetY = Double.NaN;
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

        // TAKEOFF: climb to cruise altitude, then clear the order and fall through to
        // normal behaviour so the crew is immediately ready for orders.
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
            double cruiseY = cruiseAltitude();
            if (this.vehicle.getY() >= cruiseY - SewvConfig.HELI_ALT_DEADBAND.get()) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            } else {
                climbVertically(cruiseY);
                return;
            }
        }

        // Combat: profile depends on where the guns are. A turreted airframe circles
        // the target and lets the turret do the aiming; hull-fixed guns only bear
        // where the nose points, so those airframes fly nose-on attack runs.
        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null) {
            if (hasTurret()) {
                orbit(combatTarget);
            } else {
                attackRun(combatTarget);
            }
            return;
        }
        this.extending = false; // out of combat — next fight starts with a fresh run-in

        // Order-driven movement (move-to, follow, formation, ally assist) or idle hover.
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (dest == null) {
            holdHover();
            return;
        }
        double horizDistSq = horizDistSqTo(dest.getX() + 0.5, dest.getZ() + 0.5);
        if (horizDistSq <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            holdHover(); // arrived — loiter here
        } else {
            this.hoverTargetY = Double.NaN;
            flyToward(dest.getX() + 0.5, dest.getZ() + 0.5, cruiseAltitude());
        }
    }

    // Circle the target: aim at a point a fixed angle ahead of the hull's current
    // bearing around it, on the standoff ring. Steering toward a point always slightly
    // ahead traces a smooth orbit using the same yaw+thrust loop as a straight leg, so
    // the hull continuously yaws around the target while holding range. Flown at a
    // height relative to the TARGET (not cruise) to keep the turret's depression
    // angle shallow enough to actually bear on it.
    private void orbit(LivingEntity target) {
        this.hoverTargetY = Double.NaN;
        double cx = target.getX();
        double cz = target.getZ();
        double bearing = Math.atan2(this.vehicle.getZ() - cz, this.vehicle.getX() - cx);
        double lead = bearing + this.orbitDir * ORBIT_LEAD_RAD;
        double radius = SewvConfig.HELI_ORBIT_RADIUS.get();
        double steerX = cx + Math.cos(lead) * radius;
        double steerZ = cz + Math.sin(lead) * radius;
        flyToward(steerX, steerZ, combatAltitude(target.getY() + ORBIT_HEIGHT_ABOVE_TARGET));
    }

    // Attack run for hull-fixed guns: dive the nose onto the target (which is also
    // what makes SBW's 4°-bearing fire gate open) until the break range, then extend
    // back out beyond re-engage range and turn in again — a racetrack pattern. The
    // dive attitude is aim-only; the collective keeps the hull at its combat floor.
    private void attackRun(LivingEntity target) {
        this.hoverTargetY = Double.NaN;
        double tx = target.getX();
        double tz = target.getZ();
        double dx = tx - this.vehicle.getX();
        double dz = tz - this.vehicle.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        if (this.extending) {
            if (horizDist >= ATTACK_REENGAGE_RANGE) {
                this.extending = false; // far enough out — wheel around for another pass
            } else {
                // Fly the break-off leg away from the target through our own position.
                BlockPos out = VehicleTargeting.computeStandoffPoint(
                        this.vehicle, target.blockPosition(), ATTACK_REENGAGE_RANGE + 8.0);
                flyToward(out.getX() + 0.5, out.getZ() + 0.5,
                        combatAltitude(target.getY() + ATTACK_HEIGHT_ABOVE_TARGET));
                return;
            }
        }
        if (horizDist < ATTACK_BREAK_RANGE) {
            this.extending = true;
            return; // next tick flies the break-off leg
        }

        // Run-in: hold the combat floor with the collective, yaw the nose onto the
        // target's bearing, and command the dive attitude that points the nose at it.
        applyCollective(combatAltitude(target.getY() + ATTACK_HEIGHT_ABOVE_TARGET));
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(false);

        Vec3 dir = new Vec3(dx / horizDist, 0, dz / horizDist);
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, dir));

        // Depression angle from hull to the target's center (positive = target below
        // = nose down, matching positive xRot). Only dive once roughly on bearing.
        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(this.vehicle.getY() - targetCenterY, horizDist));
        float targetAttitudeDeg = Math.abs(yawErrDeg) < ALIGN_THRESHOLD_DEG
                ? (float) Mth.clamp(depressionDeg, -MAX_CLIMB_AIM_DEG, MAX_DIVE_DEG)
                : 0.0F;
        steerNose(forward, dir, targetAttitudeDeg);
    }

    // Combat altitude: the requested target-relative height, but never below the
    // combat floor above the terrain ahead (hills don't care about the fight).
    private double combatAltitude(double desiredY) {
        return Math.max(desiredY, terrainAhead() + MIN_COMBAT_AGL);
    }

    // Land on the chosen pad: fly over it at cruise altitude, then descend straight
    // down (hover mode damping the lateral drift) until settled, and clear the order.
    private void doLanding(IHelicopterPilot pilot, BlockPos pad) {
        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        double padTopY = pad.getY() + 1.0; // set down on top of the looked-at block

        if (horizDistSqTo(px, pz) > LAND_OVER_RADIUS * LAND_OVER_RADIUS) {
            // Not over the pad yet — approach it at a safe height.
            this.hoverTargetY = Double.NaN;
            flyToward(px, pz, Math.max(cruiseAltitude(), padTopY + SewvConfig.HELI_CRUISE_ALTITUDE.get()));
            return;
        }

        // Over the pad. Touchdown when on the ground or essentially on the block top.
        // Transition to the sticky LANDED state — the hull stays down until the
        // player orders a new takeoff, rather than immediately resuming its orders.
        if (this.vehicle.onGround() || this.vehicle.getY() <= padTopY + 0.35) {
            releaseInputs();
            this.vehicle.setHoverMode(false);
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
            pilot.sewv$setHeliLandPos(null);
            this.hoverTargetY = Double.NaN;
            return;
        }

        // Controlled vertical descent, hover mode killing sideways drift so it stays
        // over the pad on the way down.
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setDownInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // The one horizontal control primitive: hold altitude with the collective and fly
    // VELOCITY-ERROR guidance toward the steer point. The desired velocity is a
    // distance-tapered vector at the point; the controller steers the nose at the
    // *difference* between that and the actual velocity, so tangential drift gets
    // actively braked instead of ignored. (Regulating only the closing speed — the
    // first attempt — leaves sideways velocity untouched, and the hull settles into
    // an endless circle around the destination at its minimum turn radius.)
    private void flyToward(double steerX, double steerZ, double desiredY) {
        applyCollective(desiredY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(false); // need full control authority while moving

        double dx = steerX - this.vehicle.getX();
        double dz = steerZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : Vec3.ZERO;

        double desiredSpeed = Math.min(SewvConfig.HELI_CRUISE_SPEED.get(), dist * APPROACH_GAIN);
        Vec3 vel = this.vehicle.getDeltaMovement();
        double evx = dir.x * desiredSpeed - vel.x;
        double evz = dir.z * desiredSpeed - vel.z;
        double errMag = Math.sqrt(evx * evx + evz * evz);

        Vector3f forward = this.vehicle.getForwardDirection().normalize();

        // Flying the requested profile — keep the nose on the destination and level out.
        if (errMag < VEL_ERR_DEADBAND) {
            steerNose(forward, dir, 0.0F);
            return;
        }

        Vec3 errDir = new Vec3(evx / errMag, 0, evz / errMag);
        // Yaw sign note: positive mouseMoveSpeedX INCREASES yaw, and getAngleBetween
        // is signed the other way — hence the negation inside steerNose.
        double yawErrDeg = Math.toDegrees(getAngleBetween(forward, errDir));

        // Commanded attitude magnitude from how badly the velocity is off. Attitude
        // (not raw stick) is the outer loop, so tilt stays bounded on any leg length.
        float attitudeMag = (float) Mth.clamp(errMag * PITCH_DEG_PER_SPEED_ERR, 0.0, MAX_ATTITUDE_DEG);

        if (Math.abs(yawErrDeg) <= ERR_BEHIND_DEG) {
            // Error ahead: nose toward it, pitch DOWN into it once roughly aligned.
            steerNose(forward, errDir,
                    Math.abs(yawErrDeg) < ALIGN_THRESHOLD_DEG ? attitudeMag : 0.0F);
        } else {
            // Error behind the hull (braking / killing tangential drift): keep the
            // nose where it points and pitch UP to thrust backward — no 180° pirouette.
            Vec3 back = new Vec3(-errDir.x, 0, -errDir.z);
            double backErrDeg = Math.toDegrees(getAngleBetween(forward, back));
            steerNose(forward, back,
                    Math.abs(backErrDeg) < ALIGN_THRESHOLD_DEG ? -attitudeMag : 0.0F);
        }
    }

    // Inner loops shared by every flight profile: yaw stick proportional to the
    // heading error onto `aim`, pitch stick closed against the hull's actual xRot
    // toward the commanded attitude (positive = nose down).
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

    // Hold a fixed hover: capture the altitude on entry, let hover mode auto-level and
    // damp drift, and keep the collective trimming toward the captured height.
    private void holdHover() {
        if (Double.isNaN(this.hoverTargetY)) this.hoverTargetY = this.vehicle.getY();
        applyCollective(this.hoverTargetY);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
    }

    // Pure vertical climb to a target altitude (takeoff): collective only, hover mode
    // on to auto-level and damp any lateral drift so it goes straight up.
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
    // deadband. Rate caps keep it from hunting up and down. This is also the takeoff
    // path — on the ground it just climbs until airborne. forwardInputDown is the
    // collective here, NOT translation.
    private void applyCollective(double desiredY) {
        double dy = desiredY - this.vehicle.getY();
        double deadband = SewvConfig.HELI_ALT_DEADBAND.get();
        double vy = this.vehicle.getDeltaMovement().y;
        boolean climb = dy > deadband && vy < CLIMB_RATE_CAP;
        boolean descend = dy < -deadband && vy > -DESCEND_RATE_CAP;
        this.vehicle.setForwardInputDown(climb);
        this.vehicle.setDownInputDown(descend);
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

    private double horizDistSqTo(double x, double z) {
        double dx = x - this.vehicle.getX();
        double dz = z - this.vehicle.getZ();
        return dx * dx + dz * dz;
    }

    // Target hold altitude = highest terrain surface AHEAD + configured clearance.
    private double cruiseAltitude() {
        return terrainAhead() + SewvConfig.HELI_CRUISE_ALTITUDE.get();
    }

    // Highest terrain surface under the hull and at several points ahead along the
    // velocity vector. Sampling ahead makes the collective start a climb BEFORE a
    // hill arrives — sampling only under the hull reacts when the slope is already
    // filling the windscreen, which at cruise speed means flying into it.
    private double terrainAhead() {
        double x = this.vehicle.getX();
        double z = this.vehicle.getZ();
        int highest = surfaceAt(x, z);
        Vec3 vel = this.vehicle.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (speed > 0.05) {
            double ux = vel.x / speed;
            double uz = vel.z / speed;
            for (double d : TERRAIN_LOOKAHEAD) {
                highest = Math.max(highest, surfaceAt(x + ux * d, z + uz * d));
            }
        }
        return highest;
    }

    private int surfaceAt(double x, double z) {
        return this.unit.level().getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(x), Mth.floor(z));
    }

    private boolean hasTurret() {
        if (this.vehicle != this.turretCacheVehicle) {
            this.turretCacheVehicle = this.vehicle;
            boolean turret;
            try {
                turret = this.vehicle.hasTurret();
            } catch (Exception ignored) {
                turret = false; // unknown → assume fixed guns; attack runs aim either way
            }
            this.turretCacheValue = turret;
        }
        return this.turretCacheValue;
    }

    // Signed horizontal angle (radians) to rotate `forward` onto `target`; same
    // convention as DriveVehicleGoal so the yaw sign matches the ground goal's steering.
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
