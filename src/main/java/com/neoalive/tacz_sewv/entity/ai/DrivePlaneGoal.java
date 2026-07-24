package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Autopilot for SuperbWarfare fixed-wing aircraft (A-10, Ju-87, KV-16 — {@code EngineType.AIRCRAFT}).
 * The sibling of {@link DriveHelicopterGoal}, split off for the same reason ships got their own goal:
 * a plane's flight model has nothing in common with a helicopter's beyond the input FIELDS.
 *
 * <ul>
 * <li><b>Lift is emergent from forward airspeed.</b> {@code aircraftEngine} only generates lift while
 *     the hull is moving, so there is no hover and no vertical climb — every phase keeps the throttle
 *     ({@code forwardInputDown}) on. A stalled plane falls out of the sky, so nothing here ever cuts
 *     power to zero.</li>
 * <li><b>Takeoff is a ground roll.</b> On the ground SBW already gives full throttle
 *     ({@code maxPower = 3}); the goal picks a clear heading, rolls, and at rotate speed pitches the
 *     nose up until it clears the terrain. If no clear run exists it aborts loudly rather than
 *     stalling into a wall.</li>
 * <li><b>Cruise steers with yaw + pitch only.</b> Deliberately no roll: SBW's {@code yRot} turns
 *     directly off {@code mouseMoveSpeedX} with or without bank, and skipping roll removes a whole
 *     class of sign-inversion bugs (cf. the ship steering notes). Turns skid; that is acceptable.</li>
 * <li><b>Combat is a racetrack.</b> Fly a straight ~220-block line locked onto the target, diving at
 *     it (bounded, with a hard pull-up floor above the terrain so it never faceplants), then a wide
 *     climbing turn — an approximate Immelman — reverses course for the next pass. Armour is
 *     dive-bombed ({@code vehicleShoot(unit, "Bomb")} over the target); everything else is strafed
 *     with the forward gun via {@link VehicleWeapons#tryAiFireAssist}.</li>
 * </ul>
 *
 * <p>Flight command state ({@code NONE/TAKEOFF/LANDING/LANDED}) is reused from {@link IHelicopterPilot}
 * — it is aircraft-generic despite the name, and the existing takeoff/land packet, TDT button and map
 * order already reach a plane pilot (fixed-wing {@code EngineInfo} subclasses {@code Helicopter}).
 *
 * <p>Steering signs mirror {@link DriveHelicopterGoal}'s proven ones (same {@code mouseMoveSpeedX} =
 * yaw, {@code mouseMoveSpeedY} = pitch fields, positive = nose down): positive {@code xRot} is
 * nose-down, so a nose-up climb commands a NEGATIVE target attitude. Gains are first-pass and want
 * in-game tuning.
 */
public class DrivePlaneGoal extends Goal {

    // Below this fraction of max health SBW flies the plane into a death spiral on its own; let go.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;

    // --- Steering (proportional sticks, re-asserted every tick against the ×0.95 decay) ---
    private static final double YAW_STICK_PER_DEG = 0.6;
    private static final float MAX_YAW_STICK = 25.0F;
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 20.0F;
    private static final float LOITER_YAW_STICK = 8.0F; // steady turn → orbit when idle

    // --- Cruise altitude (terrain-relative, clamped to this band) ---
    private static final double MIN_FLIGHT_ALT = 30.0;
    private static final double MAX_FLIGHT_ALT = 60.0;
    private static final double MIN_OVER_DEST = 20.0;
    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    private static final double TERRAIN_LOOKAHEAD = 64.0;
    // Altitude-hold: degrees of pitch per block of altitude error, and the gentle cruise ceiling.
    private static final double ALT_PITCH_PER_BLOCK = 2.0;
    private static final float MAX_CRUISE_PITCH_DEG = 20.0F;

    // --- Whisker (a plane can't stop, so it looks well ahead; a blocked cone → hard nose-up) ---
    private static final double PROBE_DISTANCE = 48.0;
    private static final float CLIMB_AVOID_PITCH_DEG = 25.0F;

    // --- Takeoff ---
    private static final double ROTATE_SPEED = 0.35;        // horizontal blocks/tick before nose-up
    private static final float TAKEOFF_PITCH_DEG = 15.0F;   // nose-up rotate target
    private static final double CLIMBOUT_ABOVE_GROUND = 24.0;

    // --- Combat (run → Immelman racetrack) ---
    private static final int PHASE_RUN = 0;
    private static final int PHASE_TURN = 1;
    private static final double RUN_LENGTH = 220.0;         // straight attack line before turning out
    private static final double MIN_ATTACK_CLEARANCE = 30.0; // hard pull-up floor above the terrain
    private static final double IMMELMAN_CLIMB = 40.0;      // altitude gained in the climbing turn
    private static final double TURN_ALIGN_DEG = 35.0;      // heading tolerance to finish the turn
    private static final double BOMB_RELEASE_DIST = 14.0;   // horizontal range to target to pickle
    private static final float MAX_DIVE_PITCH_DEG = 30.0F;  // bounded so the dive can be pulled out of

    private static final float DECOY_HEALTH_FRACTION = 0.5F;
    private static final float PRESERVE_DECOY_CHANCE = 0.5F;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    private final HullFacts hull = new HullFacts();
    private final AirTerrainSensor sensor;
    private final DecoyEpisode flares = new DecoyEpisode();
    private final ChunkTicket chunkTicket = new ChunkTicket();

    private VehicleEntity vehicle;
    // Chosen takeoff heading, NaN until the roll begins (recomputed if the goal is rebuilt mid-roll).
    private double takeoffDirX = Double.NaN;
    private double takeoffDirZ = Double.NaN;

    // Combat racetrack state.
    private int attackPhase = PHASE_RUN;
    private double runDirX = Double.NaN; // locked straight-line heading of the current run (NaN = none)
    private double runDirZ = Double.NaN;
    private double runStartX;
    private double runStartZ;
    private boolean bombRun;         // this pass drops bombs (armour) vs strafes the cannon
    private boolean droppedThisRun;  // one payload per pass
    // Forward-gun / bomb slots, scanned once off the seat's weapon names.
    private boolean weaponsScanned;
    private int cannonSlot = -1;
    private String bombWeapon; // the seat's bomb weapon name (null = none), fired by name to drop

    public DrivePlaneGoal(AbstractUnit unit) {
        this.unit = unit;
        this.sensor = new AirTerrainSensor(unit);
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        if (v.getFirstPassenger() != this.unit) return false; // seat 0 (pilot) only
        this.hull.attach(v);
        if (!this.hull.isPlane()) return false;
        this.vehicle = v;
        this.sensor.attach(v);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && this.hull.isPlane();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // stick inputs decay ×0.95/tick and loops close against live velocity
    }

    @Override
    public void start() {
        // A freshly boarded plane on the ground stays parked until an explicit takeoff order (the
        // grounded-and-not-taking-off guard in tick() also covers this, but setting LANDED makes the
        // parked state explicit and matches the helicopter goal).
        if (this.vehicle != null && this.vehicle.onGround()
                && this.unit instanceof PmcUnitEntity
                && this.unit instanceof IHelicopterPilot pilot
                && pilot.sewv$getHeliCommand() == IHelicopterPilot.HELI_CMD_NONE) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        }
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            releaseInputs();
            this.vehicle.setDecoyInputDown(false);
            this.chunkTicket.release(this.vehicle);
        }
        this.vehicle = null;
        this.takeoffDirX = Double.NaN;
        this.takeoffDirZ = Double.NaN;
        this.weaponsScanned = false; // may re-board a different hull
        resetAttackRun();
        this.allyAssist.clear();
        this.sensor.clear();
    }

    @Override
    public void tick() {
        updateChunkLoading();
        updateDecoy(); // before the crash guard: a burning plane keeps popping flares on the way down

        float max = this.vehicle.getMaxHealth();
        if (max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION) {
            releaseInputs();
            return;
        }
        if (this.vehicle.getEnergy() <= 0) {
            releaseInputs();
            return;
        }

        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;

        // LANDED is sticky: stay shut down until a new takeoff.
        if (command == IHelicopterPilot.HELI_CMD_LANDED) {
            releaseInputs();
            return;
        }
        if (command == IHelicopterPilot.HELI_CMD_LANDING) {
            doLanding(pilot);
            return;
        }
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
            doTakeoff(pilot);
            return;
        }

        // command == NONE. A plane on the ground with no takeoff order just sits — running the cruise
        // logic on the ground would taxi it around pointlessly (and PMC-only scope means no
        // autonomous takeoff for RU/US planes, which do not spawn).
        if (this.vehicle.onGround()) {
            releaseInputs();
            return;
        }

        // Airborne. Combat unless an explicit movement order pins the flight path.
        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null && !flightPinnedByOrder()) {
            combatTick(combatTarget);
            return;
        }

        resetAttackRun(); // not attacking — next contact starts a fresh run
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (combatTarget != null) {
            // Pinned by an order, but take any shot that lines up mid-leg.
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, combatTarget,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }
        if (dest == null) {
            loiter(cruiseAltitudeHere());
            return;
        }
        double px = dest.getX() + 0.5;
        double pz = dest.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        if (dx * dx + dz * dz <= (2.0 * MIN_OVER_DEST) * (2.0 * MIN_OVER_DEST)) {
            // Close to the destination — a plane can't sit on the spot, so orbit overhead.
            loiter(Math.max(cruiseAltitudeHere(), dest.getY() + MIN_OVER_DEST));
        } else {
            flyToward(px, pz, Math.max(cruiseAltitudeToward(px, pz), dest.getY() + MIN_OVER_DEST));
        }
    }

    // Same pin set as the helicopter goal: an explicit movement/hold order owns the flight path so a
    // retaliation target can't drag the whole aircraft into a strafing run.
    private boolean flightPinnedByOrder() {
        if (!(this.unit instanceof PmcUnitEntity pmc)) return false;
        OrderType order = pmc.getOrder();
        return order == OrderType.MOVE_TO_POSITION
                || order == OrderType.FOLLOW_COMMANDER
                || order == OrderType.FORM_WEDGE
                || order == OrderType.FORM_COLUMN
                || order == OrderType.HOLD_POSITION
                || order == OrderType.CEASE_FIRE;
    }

    // --- Takeoff ---------------------------------------------------------------------------------

    private void doTakeoff(IHelicopterPilot pilot) {
        if (Double.isNaN(this.takeoffDirX)) {
            Vec3 facing = forwardFlat();
            Vec3 clear = this.sensor.chooseClearBearing(facing, SewvConfig.PLANE_TAKEOFF_RUNWAY_RADIUS.get());
            if (clear == null) {
                abortTakeoff(pilot);
                return;
            }
            this.takeoffDirX = clear.x;
            this.takeoffDirZ = clear.z;
        }

        // Airborne and clear of the terrain → gear up, resume normal duty next tick.
        double climbTo = surfaceBelow() + CLIMBOUT_ABOVE_GROUND;
        if (!this.vehicle.onGround() && this.vehicle.getY() >= climbTo) {
            this.vehicle.setGearUp(true); // retract once flying — cuts drag, the "toggle gear after takeoff"
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            this.takeoffDirX = Double.NaN;
            this.takeoffDirZ = Double.NaN;
            return;
        }

        if (this.vehicle.onGround()) this.vehicle.setGearUp(false); // gear down for the roll
        this.vehicle.setForwardInputDown(true); // full throttle (maxPower 3 while grounded)
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        Vec3 dir = new Vec3(this.takeoffDirX, 0, this.takeoffDirZ);
        steerYaw(dir);
        // Neutral until the wheels have speed, then rotate the nose up to fly off.
        boolean rotate = this.vehicle.getDeltaMovement().horizontalDistance() >= ROTATE_SPEED;
        commandPitch(rotate ? -TAKEOFF_PITCH_DEG : 0.0F);
    }

    private void abortTakeoff(IHelicopterPilot pilot) {
        releaseInputs();
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED); // don't retry every tick
        this.takeoffDirX = Double.NaN;
        this.takeoffDirZ = Double.NaN;
        if (this.unit instanceof PmcUnitEntity pmc) {
            UUID owner = pmc.getOwnerUUID();
            Player p = owner != null ? this.unit.level().getPlayerByUUID(owner) : null;
            if (p != null) {
                p.displayClientMessage(
                        Component.translatable("message.tacz_sewv.plane.takeoff.no_runway"), true);
            }
        }
    }

    // --- Cruise / loiter -------------------------------------------------------------------------

    // The one lateral primitive: whisker for a clear bearing, yaw onto it, hold desiredY by pitch.
    private void flyToward(double destX, double destZ, double desiredY) {
        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double dx = destX - this.vehicle.getX();
        double dz = destZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dirToDest = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : forwardFlat();

        Vec3 travelDir = this.sensor.chooseClearBearing(dirToDest, PROBE_DISTANCE);
        if (travelDir == null) {
            // Cone fully blocked (terrain taller than the flight level dead ahead) — climb over it.
            this.vehicle.setMouseMoveSpeedX(0.0F);
            commandPitch(-CLIMB_AVOID_PITCH_DEG);
            return;
        }
        steerYaw(travelDir);
        commandPitch(altitudePitch(desiredY));
    }

    // A plane can't hold a point, so "arrived / no destination" is a steady turn at altitude. Still
    // whisker-checked so an idling orbit doesn't fly into a hill.
    private void loiter(double desiredY) {
        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        if (this.sensor.chooseClearBearing(forwardFlat(), PROBE_DISTANCE) == null) {
            this.vehicle.setMouseMoveSpeedX(0.0F);
            commandPitch(-CLIMB_AVOID_PITCH_DEG);
            return;
        }
        this.vehicle.setMouseMoveSpeedX(LOITER_YAW_STICK);
        commandPitch(altitudePitch(desiredY));
    }

    // --- Combat (run → Immelman racetrack) -------------------------------------------------------

    // A fixed-wing attack is a racetrack, not a hover-and-aim: fly a straight line THROUGH the target
    // firing/dropping, extend out, then a wide climbing turn (an approximate Immelman) reverses course
    // for the next pass. The straight line is what stops the plane orbiting a point it can't hold, and
    // the pull-up floor is what stops the dive from flying into the ground (the faceplant it used to).
    private void combatTick(LivingEntity target) {
        if (!this.weaponsScanned) scanWeapons();
        if (this.attackPhase == PHASE_TURN) {
            immelmanTurn(target);
            return;
        }

        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        if (Double.isNaN(this.runDirX)) startRun(target);

        double gx = this.vehicle.getX();
        double gz = this.vehicle.getZ();
        double distFromStart = Math.hypot(gx - this.runStartX, gz - this.runStartZ);
        // Clearance to the ground under AND just ahead, so rising terrain triggers the pull-up early.
        int groundRef = Math.max(surfaceBelow(),
                highestGroundToward(gx + this.runDirX * 24.0, gz + this.runDirZ * 24.0));
        double clearance = this.vehicle.getY() - groundRef;

        // End the pass: flown the line out, or hit the pull-up floor. Either way climb and turn.
        if (distFromStart >= RUN_LENGTH || clearance <= MIN_ATTACK_CLEARANCE) {
            this.attackPhase = PHASE_TURN;
            this.runDirX = Double.NaN;
            this.runDirZ = Double.NaN;
            this.vehicle.setMouseMoveSpeedX(0.0F);
            commandPitch(-MAX_CRUISE_PITCH_DEG); // nose up out of the dive
            return;
        }

        // Hold the LOCKED straight heading (not tracking the target — that would curve the line).
        steerYaw(new Vec3(this.runDirX, 0, this.runDirZ));

        // Dive toward the target, bounded so the pull-up floor above can always recover it.
        double horiz = Math.hypot(target.getX() - gx, target.getZ() - gz);
        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(this.vehicle.getY() - targetCenterY, Math.max(horiz, 1.0)));
        commandPitch((float) Mth.clamp(depressionDeg, -MAX_CRUISE_PITCH_DEG, MAX_DIVE_PITCH_DEG));

        if (this.bombRun) {
            // Dive-bomb: pickle one payload as it passes over the target (bomb inherits the diving
            // velocity + gravity, so releasing on the dive throws it onto the aimpoint).
            if (!this.droppedThisRun && horiz <= BOMB_RELEASE_DIST) {
                this.vehicle.vehicleShoot(this.unit, this.bombWeapon);
                this.droppedThisRun = true;
            }
        } else {
            // Strafe: keep the forward gun selected and let the assist fire it within the cone.
            if (this.cannonSlot >= 0) {
                this.vehicle.setWeaponIndex(this.vehicle.getSeatIndex(this.unit), this.cannonSlot);
            }
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }
    }

    // Lock the run: a straight heading at the target, the start point (to measure the line), and
    // whether it's a bomb pass. Armour gets bombed (if the hull carries them); everything else strafed.
    private void startRun(LivingEntity target) {
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
        this.droppedThisRun = false;
        this.bombRun = this.bombWeapon != null
                && VehicleWeapons.classifyTarget(target) == VehicleWeapons.TargetCategory.VEHICLE;
    }

    // The wide climbing turn back onto the target — an approximate Immelman (no inverted-flight
    // modelling, so it reverses by yaw while climbing rather than by looping). Reuses flyToward for
    // the whisker terrain avoidance; finishes when it points back at the target and has regained height.
    private void immelmanTurn(LivingEntity target) {
        flyToward(target.getX(), target.getZ(), cruiseAltitudeHere() + IMMELMAN_CLIMB);
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        double yawErr = toT.lengthSqr() > 1.0E-6
                ? Math.abs(Math.toDegrees(VehicleTargeting.signedAngleTo(forward, toT.normalize()))) : 0.0;
        double clearance = this.vehicle.getY() - surfaceBelow();
        if (yawErr < TURN_ALIGN_DEG && clearance >= MIN_ATTACK_CLEARANCE + IMMELMAN_CLIMB * 0.5) {
            this.attackPhase = PHASE_RUN; // realigned and high — roll in for the next pass
        }
    }

    // Which seat weapon is the forward gun and whether the hull carries bombs — read once off the
    // weapon names (planes' slots are real weapons, so no placeholder guarding needed).
    private void scanWeapons() {
        this.weaponsScanned = true;
        this.cannonSlot = -1;
        this.bombWeapon = null;
        try {
            int seat = this.vehicle.getSeatIndex(this.unit);
            var info = this.vehicle.getSeat(seat);
            int count = info == null ? 0 : info.weapons().size();
            for (int w = 0; w < count; w++) {
                String raw = this.vehicle.getGunName(seat, w);
                String name = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);
                if (this.cannonSlot < 0
                        && (name.contains("cannon") || name.contains("gun") || name.contains("mg"))) {
                    this.cannonSlot = w;
                }
                // Keep the raw name (case-sensitive weapon key), preferring the plain "Bomb".
                if (name.contains("bomb") && (this.bombWeapon == null || name.equals("bomb"))) {
                    this.bombWeapon = raw;
                }
            }
        } catch (Exception ignored) {}
        if (this.cannonSlot < 0) this.cannonSlot = 0; // fall back to the first weapon
    }

    private void resetAttackRun() {
        this.attackPhase = PHASE_RUN;
        this.runDirX = Double.NaN;
        this.runDirZ = Double.NaN;
        this.droppedThisRun = false;
    }

    // --- Landing (crude, deferred proper autoland) -----------------------------------------------

    // Descend toward the designated column and settle; ground contact is safe (SBW does not crash a
    // plane on the ground). A real glideslope/flare approach is a follow-up.
    private void doLanding(IHelicopterPilot pilot) {
        BlockPos pad = pilot.sewv$getHeliLandPos();
        if (pad == null) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            return;
        }
        if (this.vehicle.onGround()) {
            releaseInputs();
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
            pilot.sewv$setHeliLandPos(null);
            return;
        }
        this.vehicle.setGearUp(false); // gear down for the approach
        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        // Fly toward the pad losing height: aim below cruise so the plane sinks onto the approach.
        flyToward(px, pz, pad.getY() + MIN_OVER_DEST * 0.5);
    }

    // --- Steering helpers ------------------------------------------------------------------------

    private void steerYaw(Vec3 aim) {
        if (aim.lengthSqr() <= 1.0E-8) {
            this.vehicle.setMouseMoveSpeedX(0.0F);
            return;
        }
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, aim));
        // Same sign as DriveHelicopterGoal.steerNose: positive mouseMoveSpeedX increases yRot and
        // signedAngleTo is signed the other way, hence the negation.
        this.vehicle.setMouseMoveSpeedX(
                (float) Mth.clamp(-YAW_STICK_PER_DEG * yawErrDeg, -MAX_YAW_STICK, MAX_YAW_STICK));
    }

    // Command a target pitch (positive = nose down, matching xRot) via the pitch stick, closed
    // against the hull's actual xRot.
    private void commandPitch(float targetXRotDeg) {
        float err = targetXRotDeg - this.vehicle.getXRot();
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(err * PITCH_STICK_PER_DEG, -MAX_PITCH_STICK, MAX_PITCH_STICK));
    }

    // Pitch target for an altitude error: below the desired level → nose up (negative), above → nose
    // down (positive), bounded to a gentle cruise attitude.
    private float altitudePitch(double desiredY) {
        double altErr = desiredY - this.vehicle.getY();
        return (float) Mth.clamp(-altErr * ALT_PITCH_PER_BLOCK, -MAX_CRUISE_PITCH_DEG, MAX_CRUISE_PITCH_DEG);
    }

    private Vec3 forwardFlat() {
        Vector3f f = this.vehicle.getForwardDirection();
        Vec3 flat = new Vec3(f.x(), 0, f.z());
        return flat.lengthSqr() > 1.0E-8 ? flat.normalize() : new Vec3(0, 0, 1);
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

    // --- Altitude / terrain (same terrain-relative model as the helicopter goal) -----------------

    private double cruiseAltitudeHere() {
        return surfaceBelow() + flightAltitude();
    }

    private double cruiseAltitudeToward(double toX, double toZ) {
        return highestGroundToward(toX, toZ) + flightAltitude();
    }

    private int highestGroundToward(double toX, double toZ) {
        int highest = surfaceBelow();
        double dx = toX - this.vehicle.getX();
        double dz = toZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0E-4) {
            Level level = this.unit.level();
            double nx = dx / dist;
            double nz = dz / dist;
            double reach = Math.min(dist, TERRAIN_LOOKAHEAD);
            for (double d = TERRAIN_SAMPLE_STEP; d <= reach; d += TERRAIN_SAMPLE_STEP) {
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                        Mth.floor(this.vehicle.getX() + nx * d),
                        Mth.floor(this.vehicle.getZ() + nz * d));
                if (h > highest) highest = h;
            }
        }
        return highest;
    }

    private double flightAltitude() {
        int alt = (this.unit instanceof IHelicopterPilot pilot)
                ? pilot.sewv$getCruiseAltitude() : IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
        return Mth.clamp(alt, MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }

    private int surfaceBelow() {
        return this.unit.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, this.vehicle.getBlockX(), this.vehicle.getBlockZ());
    }

    // --- Decoy / chunk loading (shared shape with the helicopter goal) ---------------------------

    private void updateDecoy() {
        float max = this.vehicle.getMaxHealth();
        boolean low = max > 0.0F && this.vehicle.getHealth() <= max * DECOY_HEALTH_FRACTION;
        if (!low || this.vehicle.onGround()) {
            this.vehicle.setDecoyInputDown(false);
            return;
        }
        boolean flare = this.flares.roll(
                this.unit.level().getGameTime(), this.unit.getRandom(), PRESERVE_DECOY_CHANCE);
        if (flare && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
        }
    }

    private void updateChunkLoading() {
        if (SewvConfig.PLANE_CHUNK_LOADING.get()) {
            this.chunkTicket.follow(this.vehicle);
        } else {
            this.chunkTicket.release(this.vehicle);
        }
    }
}
