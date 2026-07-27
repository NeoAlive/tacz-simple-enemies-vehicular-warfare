package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
 * <li><b>Combat is a racetrack.</b> Each contact opens with a wide climbing turn (an approximate
 *     Immelman), then a straight ~440-block line locked onto the target, diving at it (bounded, with a
 *     hard pull-up floor so it never faceplants), then the turn again for the next pass. On each run a
 *     weapon is scored off the target's heaviness (vehicles → missiles/bombs, infantry → rockets, soft
 *     targets → cannon; weapons found by name clues since SBW doesn't order them consistently). A
 *     nose weapon fires within a deliberately generous cone (splash does the work); a bomb is released
 *     by continuous ballistic prediction — dropped the instant a bomb thrown NOW would land on the
 *     target, which self-adjusts for altitude/speed/pitch across level, dive and low passes.</li>
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

    private static final double FIRE_CONE_DEG = 45.0;
    private static final double TAKEOFF_RUNWAY_RADIUS = 64.0;
    private static final List<String> MISSILE_CLUES = List.of("missile", "agm", "kh_", "atgm", "maverick");
    private static final List<String> BOMB_CLUES = List.of("bomb");
    private static final List<String> ROCKET_CLUES = List.of("rocket", "hydra");

    // Below this fraction of max health SBW flies the plane into a death spiral on its own; let go.
    private static final float CRASH_HEALTH_FRACTION = 0.10F;
    // RU/US emergency landing: abstract "get down" before the crash spiral, reuse PMC land procedure.
    private static final float EMERGENCY_LAND_HEALTH = 0.15F;
    private static final int EMERGENCY_LAND_RETRY_TICKS = 100;
    private static final double FACTION_ESCORT_RANGE = 256.0;

    // --- Steering (proportional sticks, re-asserted every tick against the ×0.95 decay) ---
    private static final double YAW_STICK_PER_DEG = 0.6;
    private static final float MAX_YAW_STICK = 25.0F;
    private static final double PITCH_STICK_PER_DEG = 0.8;
    private static final float MAX_PITCH_STICK = 28.0F; // enough authority to snap onto a dive/pull-up
    private static final float LOITER_YAW_STICK = 8.0F; // steady turn → orbit when idle

    // --- Cruise altitude (terrain-relative, clamped to this band) ---
    // Planes operate ~3x higher than helicopters: the pilot's cruise stepper (30-50) is scaled up
    // and the band widened to match, so a jet cruises well above the terrain it is diving on.
    private static final double ALT_SCALE = 3.0;
    private static final double MIN_FLIGHT_ALT = 90.0;
    private static final double MAX_FLIGHT_ALT = 180.0;
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
    private static final double CLIMBOUT_ABOVE_GROUND = 72.0;
    private static final double[] RUNWAY_FAN_DEG = {0.0, 20.0, -20.0, 40.0, -40.0, 65.0, -65.0};
    private static final int RUNWAY_MAX_STEP = 2;           // blocks the surface may rise across a runway

    // --- Combat (run → Immelman racetrack) ---
    // Turns are scaled wide (long legs + a gentle, half-rate yaw ≈ double the radius) because heavy
    // planes carry momentum through a turn — SBW integrates deltaMovement, so the velocity lags the
    // nose and a tight yaw just skids. Wide, gentle turns let the airframe follow its own nose.
    private static final int PHASE_RUN = 0;
    private static final int PHASE_TURN = 1;
    private static final double RUN_LENGTH = 440.0;         // straight attack line before turning out
    private static final double TURN_YAW_SCALE = 0.5;       // half yaw rate in the turn → ~2x radius
    private static final double OVERFLY_MARGIN = 15.0;      // extend this far past the target, then break
    private static final double MIN_ATTACK_CLEARANCE = 22.0; // base pull-up floor (pressed in close)
    private static final double PULLUP_LEAD_TICKS = 14.0;   // anticipate recovery sink from descent rate
    private static final float HARD_CLIMB_PITCH_DEG = 30.0F; // nose-up to pull out of / climb from a dive
    private static final double IMMELMAN_CLIMB = 120.0;     // altitude gained in the climbing turn
    private static final double TURN_ALIGN_DEG = 35.0;      // heading tolerance to finish the turn
    private static final float MAX_DIVE_PITCH_DEG = 55.0F;  // steep, aggressive dive onto the target

    // Weapon scoring: target heaviness → weapon-ladder level (vehicles heaviest — the doctrine choice).
    private static final int LEVEL_VEHICLE = 3;
    private static final int LEVEL_FACTION = 2;
    private static final int LEVEL_SOFT = 1;
    // Predictive bomb release (ballistic forward-sim; the two factors are the calibration knobs).
    private static final double BOMB_GRAVITY = 0.06;       // blocks/tick^2 fall
    private static final double BOMB_VEL_FACTOR = 1.0;     // bomb inherits ~this × aircraft velocity
    private static final double BOMB_HIT_TOLERANCE = 6.0;  // release when predicted impact within this
    private static final int BOMB_SIM_MAX_TICKS = 200;

    // --- Landing (glideslope → flare → settle) ---
    private static final double LAND_GLIDE_RATIO = 0.35;    // approach altitude per block of distance
    private static final double LAND_MAX_APPROACH_HEIGHT = 90.0;
    private static final double LAND_FLARE_HEIGHT = 8.0;    // below this over the ground, flare
    private static final float LAND_FLARE_PITCH_DEG = -8.0F; // slight nose-up hold in the flare

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
    private int attackPhase = PHASE_TURN; // enter combat with the setup Immelman
    private double runDirX = Double.NaN; // locked straight-line heading of the current run (NaN = none)
    private double runDirZ = Double.NaN;
    private double runStartX;
    private double runStartZ;
    private boolean droppedThisRun;  // one payload per pass
    // Seat weapons classified into the scoring ladder once; the scored pick for the current run.
    private boolean weaponsScanned;
    private final List<PlaneWeapon> weapons = new ArrayList<>();
    private int selWeaponSlot = -1;
    private boolean selBomb;
    private String selBombName;

    /** Absolute game time before the next RU/US emergency-pad search (no field found). */
    private long nextEmergencyLandTry = Long.MIN_VALUE;

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
        this.nextEmergencyLandTry = Long.MIN_VALUE;
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

        // RU/US: try the PMC landing procedure while still controllable (<15%, above crash spiral).
        if (pilot != null && !(this.unit instanceof PmcUnitEntity)
                && command != IHelicopterPilot.HELI_CMD_LANDING
                && command != IHelicopterPilot.HELI_CMD_LANDED
                && max > 0.0F && this.vehicle.getHealth() < max * EMERGENCY_LAND_HEALTH) {
            long now = this.unit.level().getGameTime();
            if (now >= this.nextEmergencyLandTry) {
                BlockPos pad = pickEmergencyPad();
                if (pad != null) {
                    pilot.sewv$setHeliLandPos(pad);
                    command = IHelicopterPilot.HELI_CMD_LANDING;
                    pilot.sewv$setHeliCommand(command);
                } else {
                    this.nextEmergencyLandTry = now + EMERGENCY_LAND_RETRY_TICKS;
                }
            }
        }

        // Hostile RU/US crews take no player flight orders and never idle parked: any grounded
        // NONE state (spawn edge case, world reload) resolves to takeoff. Sticky LANDED after an
        // emergency landing stays down — that is the whole point of the emergency procedure.
        if (pilot != null && !(this.unit instanceof PmcUnitEntity)
                && this.vehicle.onGround()
                && command == IHelicopterPilot.HELI_CMD_NONE) {
            command = IHelicopterPilot.HELI_CMD_TAKEOFF;
            pilot.sewv$setHeliCommand(command);
        }

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
        // logic on the ground would taxi it around pointlessly. RU/US never stay here long: the
        // auto-TAKEOFF above lifts them, and event/command spawns place them already airborne.
        if (this.vehicle.onGround()) {
            releaseInputs();
            return;
        }

        // RU/US: guided faction assets — escort nearest ground ally, inherit targets, stay local.
        if (!(this.unit instanceof PmcUnitEntity)) {
            factionEscortTick();
            return;
        }

        // Airborne PMC. Combat unless an explicit movement order pins the flight path.
        LivingEntity combatTarget = this.unit.getTarget();
        if (combatTarget != null && !flightPinnedByOrder()) {
            combatTick(combatTarget);
            return;
        }

        resetAttackRun(); // not attacking — next contact starts a fresh run
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        if (combatTarget != null) {
            // Pinned by an order, but take any shot that lines up mid-leg (eased plane cone).
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, combatTarget,
                    FIRE_CONE_DEG);
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

    /**
     * RU/US planes stay tied to the local fight: nearest same-faction ground hull, inherit its
     * target, fall back to any allied unit, else orbit the nearest ground underfoot — never a
     * free roam across the map.
     */
    private void factionEscortTick() {
        VehicleEntity allyHull = findNearestGroundAlly();
        if (allyHull != null) {
            inheritTargetFromHull(allyHull);
            LivingEntity combatTarget = this.unit.getTarget();
            if (combatTarget != null) {
                combatTick(combatTarget);
                return;
            }
            resetAttackRun();
            flyToward(allyHull.getX(), allyHull.getZ(),
                    Math.max(cruiseAltitudeToward(allyHull.getX(), allyHull.getZ()),
                            allyHull.getY() + MIN_OVER_DEST));
            return;
        }
        AbstractUnit allyUnit = findNearestAllyUnit();
        if (allyUnit != null) {
            inheritTargetFromUnit(allyUnit);
            LivingEntity combatTarget = this.unit.getTarget();
            if (combatTarget != null) {
                combatTick(combatTarget);
                return;
            }
            resetAttackRun();
            flyToward(allyUnit.getX(), allyUnit.getZ(),
                    Math.max(cruiseAltitudeToward(allyUnit.getX(), allyUnit.getZ()),
                            allyUnit.getY() + MIN_OVER_DEST));
            return;
        }
        resetAttackRun();
        BlockPos ground = nearestLocalGround();
        double px = ground.getX() + 0.5;
        double pz = ground.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        if (dx * dx + dz * dz <= (2.0 * MIN_OVER_DEST) * (2.0 * MIN_OVER_DEST)) {
            loiter(Math.max(cruiseAltitudeHere(), ground.getY() + MIN_OVER_DEST));
        } else {
            flyToward(px, pz, Math.max(cruiseAltitudeToward(px, pz), ground.getY() + MIN_OVER_DEST));
        }
    }

    private void inheritTargetFromHull(VehicleEntity hull) {
        if (hull.getFirstPassenger() instanceof AbstractUnit driver) {
            inheritTargetFromUnit(driver);
        }
    }

    private void inheritTargetFromUnit(AbstractUnit ally) {
        LivingEntity theirs = ally.getTarget();
        if (theirs != null && theirs.isAlive() && theirs != this.unit) {
            this.unit.setTarget(theirs);
        }
    }

    private VehicleEntity findNearestGroundAlly() {
        AABB box = this.vehicle.getBoundingBox().inflate(FACTION_ESCORT_RANGE);
        VehicleEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (VehicleEntity v : this.unit.level().getEntitiesOfClass(VehicleEntity.class, box,
                this::isFactionGroundAlly)) {
            double d = v.distanceToSqr(this.vehicle);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    private boolean isFactionGroundAlly(VehicleEntity v) {
        if (v == this.vehicle || !v.isAlive() || v.isWreck()) return false;
        if (HullFacts.isPlaneHull(v) || HullFacts.isShipHull(v)) return false;
        try {
            if (v.computed().getEngineType() == EngineType.HELICOPTER) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return v.getFirstPassenger() instanceof AbstractUnit crew
                && VehicleTargeting.isSameFaction(this.unit, crew);
    }

    private AbstractUnit findNearestAllyUnit() {
        AABB box = this.vehicle.getBoundingBox().inflate(FACTION_ESCORT_RANGE);
        AbstractUnit best = null;
        double bestD = Double.MAX_VALUE;
        for (AbstractUnit other : this.unit.level().getEntitiesOfClass(AbstractUnit.class, box,
                a -> a != this.unit && a.isAlive() && VehicleTargeting.isSameFaction(this.unit, a))) {
            double d = other.distanceToSqr(this.vehicle);
            if (d < bestD) {
                bestD = d;
                best = other;
            }
        }
        return best;
    }

    private BlockPos nearestLocalGround() {
        Level level = this.unit.level();
        int x = this.vehicle.getBlockX();
        int z = this.vehicle.getBlockZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** Flat-enough strip near the plane for an emergency glide-in; null → retry later. */
    private BlockPos pickEmergencyPad() {
        Level level = this.unit.level();
        int bx = this.vehicle.getBlockX();
        int bz = this.vehicle.getBlockZ();
        Vec3 facing = forwardFlat();
        for (int r = 0; r <= 48; r += 4) {
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = bx + dx;
                    int z = bz + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (y <= level.getMinBuildHeight()) continue;
                    BlockPos pad = new BlockPos(x, y, z);
                    if (!level.getFluidState(pad).isEmpty()) continue;
                    if (fieldClearFrom(pad, facing)) return pad;
                }
            }
        }
        return null;
    }

    private boolean fieldClearFrom(BlockPos pad, Vec3 dir) {
        Level level = this.unit.level();
        int base = pad.getY();
        double length = Math.min(32.0, TAKEOFF_RUNWAY_RADIUS);
        for (double d = 2.0; d <= length; d += 2.0) {
            int px = Mth.floor(pad.getX() + 0.5 + dir.x * d);
            int pz = Mth.floor(pad.getZ() + 0.5 + dir.z * d);
            int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            if (Math.abs(surf - base) > RUNWAY_MAX_STEP) return false;
        }
        return true;
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
            Vec3 clear = pickRunwayHeading();
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

    // Pick a takeoff heading by a GROUND-relative clearance scan, NOT the airborne whisker: the flight
    // sensor probes a slab from floor(Y)-1 up, which on the ground is the block the plane sits on, so
    // it reported every direction blocked and takeoff needed the plane lifted a block first. This
    // instead compares the surface height ahead to the plane's own — flat ground is always clear, a
    // wall/tree/cliff (a step taller than RUNWAY_MAX_STEP) is not. Fans across headings, nearest first.
    private Vec3 pickRunwayHeading() {
        double length = TAKEOFF_RUNWAY_RADIUS;
        Vec3 facing = forwardFlat();
        for (double offDeg : RUNWAY_FAN_DEG) {
            Vec3 cand = VehicleTargeting.rotateY(facing, Math.toRadians(offDeg));
            if (runwayClearAhead(cand, length)) return cand;
        }
        return null;
    }

    private boolean runwayClearAhead(Vec3 dir, double length) {
        Level level = this.unit.level();
        int baseSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                this.vehicle.getBlockX(), this.vehicle.getBlockZ());
        double half = this.vehicle.getBbWidth() / 2.0;
        for (double d = half + 1.0; d <= length; d += 1.0) {
            int px = Mth.floor(this.vehicle.getX() + dir.x * d);
            int pz = Mth.floor(this.vehicle.getZ() + dir.z * d);
            int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            if (surf - baseSurface > RUNWAY_MAX_STEP) return false; // wall/tree/cliff the roll can't clear
        }
        return true;
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

        // Overfly test off the LOCKED run axis (projection along runDir): have we passed the target?
        double planeAlong = (gx - this.runStartX) * this.runDirX + (gz - this.runStartZ) * this.runDirZ;
        double targetAlong = (target.getX() - this.runStartX) * this.runDirX
                + (target.getZ() - this.runStartZ) * this.runDirZ;
        boolean passedTarget = planeAlong > targetAlong + OVERFLY_MARGIN;

        // Predictive pull-up floor: anticipate the sink during recovery from the CURRENT descent rate,
        // so a fast/steep dive breaks earlier (heavy-plane momentum) while a shallow one presses close.
        int groundRef = Math.max(surfaceBelow(),
                highestGroundToward(gx + this.runDirX * 24.0, gz + this.runDirZ * 24.0));
        double clearance = this.vehicle.getY() - groundRef;
        double descentRate = Math.max(0.0, -this.vehicle.getDeltaMovement().y);
        double pullupTrigger = MIN_ATTACK_CLEARANCE + descentRate * PULLUP_LEAD_TICKS;

        // End the pass: overflew the target, hit the (anticipated) floor, or flew the whole line.
        if (passedTarget || clearance <= pullupTrigger || distFromStart >= RUN_LENGTH) {
            this.attackPhase = PHASE_TURN;
            this.runDirX = Double.NaN;
            this.runDirZ = Double.NaN;
            this.vehicle.setMouseMoveSpeedX(0.0F);
            commandPitch(-HARD_CLIMB_PITCH_DEG); // hard nose-up out of the dive
            return;
        }

        // AIM: track the target with BOTH axes so the nose actually points at what it fires at — the
        // accuracy the wide cone alone couldn't give. It still flies roughly the locked line (the
        // target sits along it); the corrections just tighten the pipper as it closes.
        double horiz = Math.hypot(target.getX() - gx, target.getZ() - gz);
        steerYaw(new Vec3(target.getX() - gx, 0, target.getZ() - gz));
        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(this.vehicle.getY() - targetCenterY, Math.max(horiz, 1.0)));
        commandPitch((float) Mth.clamp(depressionDeg, -MAX_CRUISE_PITCH_DEG, MAX_DIVE_PITCH_DEG));

        if (this.selBomb) {
            // BombAttack: a continuous ballistic prediction, not a fixed release distance. Every tick
            // it simulates where a bomb dropped NOW would land (inheriting the aircraft's velocity),
            // and pickles the instant that predicted impact lands on the target — so the release point
            // shifts earlier at higher speed/altitude and later when low/slow, on its own. Same logic
            // serves level, dive and low-altitude passes; the dive above is only the approach.
            if (!this.droppedThisRun && this.selBombName != null && bombWouldHit(target)) {
                this.vehicle.vehicleShoot(this.unit, this.selBombName);
                this.droppedThisRun = true;
            }
        } else {
            // NoseAttack: select the scored weapon and fire it within the (deliberately generous)
            // nose cone. Guided missiles steer out the residual; splash covers the rest.
            if (this.selWeaponSlot >= 0) {
                this.vehicle.setWeaponIndex(this.vehicle.getSeatIndex(this.unit), this.selWeaponSlot);
            }
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    FIRE_CONE_DEG);
        }
    }

    // Lock the run: a straight heading at the target, the start point (to measure the line), and the
    // scored weapon for this pass (chosen AFTER the Immelman, off the target's heaviness).
    private void startRun(LivingEntity target) {
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
        this.droppedThisRun = false;
        selectRunWeapon(scoreTarget(target));
    }

    // Target heaviness → weapon-ladder level: vehicles draw the heaviest weapons, then faction
    // infantry, then soft single targets (monsters/players). (Doctrine choice — flip here to invert.)
    private int scoreTarget(LivingEntity target) {
        return switch (VehicleWeapons.classifyTarget(target)) {
            case VEHICLE -> LEVEL_VEHICLE;
            case FACTION_UNIT -> LEVEL_FACTION;
            default -> LEVEL_SOFT; // MONSTER + player
        };
    }

    // Pick this run's weapon by weighted random over the seat's weapons whose tier fits the target
    // level (never a heavier weapon than the target warrants — no missiles at a zombie). Heavier
    // weapons are favoured among those that fit, and a missile is favoured hardest, so it "stays in
    // the higher levels with a higher chance" while a light target settles for the cannon.
    private void selectRunWeapon(int level) {
        PlaneWeapon chosen = null;
        double total = 0.0;
        for (PlaneWeapon w : this.weapons) {
            if (w.tier() > level) continue;          // too heavy for this target
            total += weaponWeight(w);
        }
        if (total <= 0.0) {                          // nothing fits (shouldn't happen — cannon is tier 1)
            chosen = this.weapons.isEmpty() ? null : this.weapons.get(0);
        } else {
            double r = this.unit.getRandom().nextDouble() * total;
            for (PlaneWeapon w : this.weapons) {
                if (w.tier() > level) continue;
                r -= weaponWeight(w);
                if (r <= 0.0) { chosen = w; break; }
            }
        }
        if (chosen == null) {
            this.selWeaponSlot = 0;
            this.selBomb = false;
            this.selBombName = null;
            return;
        }
        this.selWeaponSlot = chosen.slot();
        this.selBomb = chosen.bomb();
        this.selBombName = chosen.name();
    }

    private static double weaponWeight(PlaneWeapon w) {
        return w.tier() + (w.missile() ? w.tier() : 0); // missile ≈ double weight → picked most at top
    }

    // Ballistic forward-sim: would a bomb dropped this tick land on the target? Inherits the aircraft's
    // velocity, falls under gravity. Factors are approximations (SBW's exact muzzle/drag differ) — the
    // per-tick re-evaluation absorbs the error, and BOMB_VEL_FACTOR/BOMB_GRAVITY are the calibration.
    private boolean bombWouldHit(LivingEntity target) {
        Vec3 pos = this.vehicle.position();
        Vec3 vel = this.vehicle.getDeltaMovement().scale(BOMB_VEL_FACTOR);
        double impactY = target.getY();
        double tx = target.getX();
        double tz = target.getZ();
        for (int t = 0; t < BOMB_SIM_MAX_TICKS; t++) {
            pos = pos.add(vel);
            vel = new Vec3(vel.x, vel.y - BOMB_GRAVITY, vel.z);
            if (pos.y <= impactY) {
                double dx = pos.x - tx;
                double dz = pos.z - tz;
                return dx * dx + dz * dz <= BOMB_HIT_TOLERANCE * BOMB_HIT_TOLERANCE;
            }
        }
        return false; // never comes down in the window (climbing / too shallow) — hold the bomb
    }

    // The wide climbing turn back onto the target — an approximate Immelman (no inverted-flight
    // modelling, so it reverses by yaw while climbing rather than by looping). Steers at a GENTLE
    // half-rate yaw so the turn radius is wide enough for a heavy plane's momentum to follow; climbs
    // hard to the high turn altitude. Finishes when it points back at the target and has regained
    // height — then rolls in for the next pass.
    private void immelmanTurn(LivingEntity target) {
        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : forwardFlat();
        Vec3 clear = this.sensor.chooseClearBearing(dir, PROBE_DISTANCE);
        if (clear == null) {
            this.vehicle.setMouseMoveSpeedX(0.0F);
            commandPitch(-CLIMB_AVOID_PITCH_DEG);
            return;
        }
        steerYaw(clear, TURN_YAW_SCALE); // gentle → wide, momentum-friendly turn

        double clearance = this.vehicle.getY() - surfaceBelow();
        double recoverAlt = MIN_ATTACK_CLEARANCE + IMMELMAN_CLIMB * 0.5;
        if (clearance < recoverAlt) {
            commandPitch(-HARD_CLIMB_PITCH_DEG); // still low from the aggressive dive — climb hard first
        } else {
            commandPitch(altitudePitch(cruiseAltitudeHere() + IMMELMAN_CLIMB));
        }

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErr = Math.abs(Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dir)));
        if (yawErr < TURN_ALIGN_DEG && clearance >= recoverAlt) {
            this.attackPhase = PHASE_RUN; // realigned and high — roll in for the next pass
        }
    }

    // Classify the seat's weapons into the scoring ladder ONCE, off their names (SBW planes don't
    // order weapons consistently, so this is the same clue idea as ifvNameClues). Missile/bomb are
    // the heavy tier, rockets the medium, cannon/gun the light — and anything unrecognised is treated
    // as a light nose gun so it is never simply unusable.
    private void scanWeapons() {
        this.weaponsScanned = true;
        this.weapons.clear();
        try {
            int seat = this.vehicle.getSeatIndex(this.unit);
            var info = this.vehicle.getSeat(seat);
            int count = info == null ? 0 : info.weapons().size();
            for (int w = 0; w < count; w++) {
                String raw = this.vehicle.getGunName(seat, w);
                String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
                if (matchesAny(name, MISSILE_CLUES)) {
                    this.weapons.add(new PlaneWeapon(w, 3, false, true, raw));
                } else if (matchesAny(name, BOMB_CLUES)) {
                    this.weapons.add(new PlaneWeapon(w, 3, true, false, raw));
                } else if (matchesAny(name, ROCKET_CLUES)) {
                    this.weapons.add(new PlaneWeapon(w, 2, false, false, raw));
                } else {
                    // Cannon clue, or unrecognised — either way a light forward gun.
                    this.weapons.add(new PlaneWeapon(w, 1, false, false, raw));
                }
            }
        } catch (Exception ignored) {}
        if (this.weapons.isEmpty()) this.weapons.add(new PlaneWeapon(0, 1, false, false, null));
    }

    private static boolean matchesAny(String name, List<? extends String> clues) {
        if (name.isEmpty()) return false;
        for (String clue : clues) {
            if (clue != null && !clue.isBlank() && name.contains(clue.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void resetAttackRun() {
        // Enter combat with the Immelman: on the next contact the turn runs first (climb + line up),
        // THEN the scored run — so the turn "enacts each time the plane is in combat".
        this.attackPhase = PHASE_TURN;
        this.runDirX = Double.NaN;
        this.runDirZ = Double.NaN;
        this.droppedThisRun = false;
    }

    // --- Landing (glideslope → flare → settle) ---------------------------------------------------

    // A fixed-wing approach: gear down, steer onto the pad while descending a shallow glideslope
    // (altitude proportional to distance out), then near the strip cut throttle and flare so it sinks
    // on. Ground contact is safe (SBW does not crash a plane on the ground), so touchdown → LANDED.
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
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Steer STRAIGHT at the pad — NOT through the airborne whisker. Near the deck that sensor reads
        // the very ground we are descending onto as an obstacle (its slab starts at floor(Y)-1) and
        // deflects the plane into a skim along it, which is why it "brushed the ground and never slowed".
        steerYaw(new Vec3(dx, 0, dz));

        double aboveGround = this.vehicle.getY() - surfaceBelow();

        // Flare on ALTITUDE alone (not distance to the pad, which a fast plane blows past before the
        // gate ever coincided): idle the throttle, THROTTLE DOWN and hold the AIR BRAKE — the brake
        // (downInput → planeBreak) is what actually sheds speed — with a slight nose-up so it settles.
        if (aboveGround <= LAND_FLARE_HEIGHT) {
            this.vehicle.setForwardInputDown(false);
            this.vehicle.setBackInputDown(true);
            this.vehicle.setDownInputDown(true);
            commandPitch(LAND_FLARE_PITCH_DEG);
            return;
        }

        // Glideslope: descend toward the pad, altitude proportional to distance, floored at the flare
        // height (arrive AT the numbers, not diving through them) and capped so it doesn't dive steeply.
        this.vehicle.setForwardInputDown(true); // maintain approach speed until the flare
        this.vehicle.setBackInputDown(false);
        this.vehicle.setDownInputDown(false);
        double glideY = pad.getY() + Mth.clamp(dist * LAND_GLIDE_RATIO, LAND_FLARE_HEIGHT, LAND_MAX_APPROACH_HEIGHT);
        commandPitch(altitudePitch(glideY));
    }

    // --- Steering helpers ------------------------------------------------------------------------

    private void steerYaw(Vec3 aim) {
        steerYaw(aim, 1.0);
    }

    // rateScale < 1 gentles the yaw (lower gain AND lower saturation), widening the turn radius so a
    // heavy airframe's lagging momentum can follow the nose instead of skidding through the turn.
    private void steerYaw(Vec3 aim, double rateScale) {
        if (aim.lengthSqr() <= 1.0E-8) {
            this.vehicle.setMouseMoveSpeedX(0.0F);
            return;
        }
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, aim));
        // Same sign as DriveHelicopterGoal.steerNose: positive mouseMoveSpeedX increases yRot and
        // signedAngleTo is signed the other way, hence the negation.
        double maxStick = MAX_YAW_STICK * rateScale;
        this.vehicle.setMouseMoveSpeedX(
                (float) Mth.clamp(-YAW_STICK_PER_DEG * rateScale * yawErrDeg, -maxStick, maxStick));
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
        return Mth.clamp(alt * ALT_SCALE, MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
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

    // A seat weapon on the scoring ladder: tier 1 (cannon/gun) .. 3 (missile/bomb), with the two
    // flags that route firing — a bomb goes to the predictive release, a missile to the guided shot.
    private record PlaneWeapon(int slot, int tier, boolean bomb, boolean missile, String name) {}
}
