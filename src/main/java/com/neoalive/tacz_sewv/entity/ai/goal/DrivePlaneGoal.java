package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneController;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneKinematics;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneLeash;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneMode;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneNav;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneTerrain;
import com.neoalive.tacz_sewv.entity.ai.plane.PlaneWeapons;
import com.neoalive.tacz_sewv.entity.ai.sensor.AirTerrainSensor;
import com.neoalive.tacz_sewv.entity.ai.support.AirframeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.DecoyEpisode;
import com.neoalive.tacz_sewv.util.ChunkTicket;

/**
 * Autopilot for SuperbWarfare fixed-wing aircraft (A-10, Ju-87, KV-16 — {@code EngineType.AIRCRAFT}).
 *
 * <p>This class decides <b>which</b> of a small set of things the aircraft is doing and dispatches
 * to it. It deliberately does no geometry, no terrain reading, no aiming and no input writing of
 * its own: those live in {@code entity.ai.plane} as separate layers, each of which is allowed to do
 * exactly one job — {@link PlaneKinematics} measures, {@link PlaneTerrain} answers,
 * {@link PlaneNav} computes geometry, {@link PlaneWeapons} shoots, {@link PlaneController} steers,
 * {@link PlaneLeash} decides how far is too far. The previous version had all six mixed into one
 * file, which is how it ended up with a landing routine that could not be reached, a fire cone
 * chosen for a reason that did not survive contact, and an attack pattern that flew the aircraft
 * off the map.
 *
 * <p>The flight model constrains everything above. SBW's {@code aircraftEngine} makes lift out of
 * forward airspeed, so there is no hover, no vertical climb and no stopping: <b>every</b> branch
 * through a tick must issue throttle and an attitude, and "do nothing this tick" is not an option —
 * the sticks decay by 0.95 each tick, so silence is a control release. A plane also cannot hold a
 * point, so idling is a closed circular hold, and it cannot turn on the spot, so an approach has to
 * be flown as a pattern onto an axis rather than a dive at a pad.
 *
 * <p>Flight command state ({@code NONE/TAKEOFF/LANDING/LANDED}) is shared with the helicopter
 * through {@link IHelicopterPilot}; it is aircraft-generic despite the name, and the takeoff/land
 * packet, TDT button and map order all reach a plane pilot now that the rotary-wing filter they
 * used to sit behind has been widened.
 *
 * <p>Steering signs mirror {@link DriveHelicopterGoal}'s proven ones and live in
 * {@link PlaneController}: positive {@code xRot} is nose-down, so a climb commands a negative
 * attitude.
 */
public class DrivePlaneGoal extends Goal {

    /** Player Land from TDT/map. Hull-side backup so a goal rebuild mid-approach keeps the pad. */
    public static final String TAG_FORCED_LAND = "sewv:plane_forced_land";
    public static final String TAG_LAND_PAD = "sewv:plane_land_pad";
    /**
     * The approach heading chosen for the current landing, in degrees. Stored on the hull because
     * re-picking it mid-approach restarts the circuit: the aircraft would fly to a fix, have the
     * axis change under it, and go round again forever.
     */
    public static final String TAG_APPROACH_YAW = "sewv:plane_approach_yaw";

    public static void setForcedLand(VehicleEntity v, BlockPos pad) {
        if (v == null || pad == null) return;
        v.getPersistentData().putBoolean(TAG_FORCED_LAND, true);
        v.getPersistentData().putLong(TAG_LAND_PAD, pad.asLong());
        v.getPersistentData().remove(TAG_APPROACH_YAW); // a new pad wants a new approach
    }

    public static void clearForcedLand(VehicleEntity v) {
        if (v == null) return;
        CompoundTag tag = v.getPersistentData();
        tag.remove(TAG_FORCED_LAND);
        tag.remove(TAG_LAND_PAD);
        tag.remove(TAG_APPROACH_YAW);
    }

    // --- Health / power ---------------------------------------------------------------------
    /** Below this SBW flies the plane into its own death spiral; let go of the controls. */
    private static final float CRASH_HEALTH_FRACTION = 0.10F;
    /** RU/US: get down while still controllable, above the spiral. */
    private static final float EMERGENCY_LAND_HEALTH = 0.15F;
    private static final int EMERGENCY_LAND_RETRY_TICKS = 100;
    private static final float DECOY_HEALTH_FRACTION = 0.5F;
    private static final float PRESERVE_DECOY_CHANCE = 0.5F;

    // --- Cruise altitude (terrain-relative, clamped to this band) ------------------------------
    // Planes operate ~3x higher than helicopters: the pilot's cruise stepper (30-50) is scaled up
    // and the band widened to match, so a jet cruises well above the terrain it is diving on.
    private static final double ALT_SCALE = 3.0;
    private static final double MIN_FLIGHT_ALT = 90.0;
    private static final double MAX_FLIGHT_ALT = 180.0;
    private static final double MIN_OVER_DEST = 20.0;
    private static final double TERRAIN_LOOKAHEAD = 96.0;
    private static final float CLIMB_AVOID_PITCH_DEG = 25.0F;
    /**
     * When every bearing is blocked the aircraft climbs and holds a floor until it is clear, rather
     * than pitching up for one tick and letting momentum carry it into the face. Decays back so the
     * surplus height is given up gradually. Same shape as the helicopter's avoidance floor.
     */
    private static final double AVOID_CLIMB_STEP = 24.0;
    private static final double AVOID_FLOOR_DECAY = 0.15;

    // --- Takeoff -------------------------------------------------------------------------------
    private static final double TAKEOFF_RUNWAY_RADIUS = 64.0;
    private static final double ROTATE_SPEED = 0.35;
    private static final float TAKEOFF_PITCH_DEG = 15.0F;
    private static final double CLIMBOUT_ABOVE_GROUND = 72.0;
    private static final double[] RUNWAY_FAN_DEG = {0.0, 20.0, -20.0, 40.0, -40.0, 65.0, -65.0};
    private static final int RUNWAY_MAX_STEP = 2;

    // --- Combat --------------------------------------------------------------------------------
    private static final double OVERFLY_MARGIN = 15.0;
    private static final double MIN_ATTACK_CLEARANCE = 22.0;
    /**
     * Height above the ground at the target that the aircraft rolls in from. Cruise is 90-180 AGL,
     * and a dive from there onto something 96 blocks away is a 45-60 degree drop the aircraft
     * cannot recover from inside its pull-up margin — so the ingress trades that height for a
     * ~25 degree run-in. Low for a transit, which is why it is only ever flown at the target and
     * only with {@link #MIN_INGRESS_CLEARANCE} still held over everything on the way.
     */
    private static final double ATTACK_ENTRY_AGL = 64.0;
    /** Air kept over the highest ground between here and the target while closing on it. */
    private static final double MIN_INGRESS_CLEARANCE = 40.0;
    /**
     * Clearance required under the <b>planned</b> dive path. Lower than the pull-up floor because
     * the run deliberately ends at that floor: requiring the full margin at the end of the run
     * would refuse every attack, which is exactly what the first version of this check did.
     */
    private static final double DIVE_PATH_CLEARANCE = 12.0;
    private static final double PULLUP_LEAD_TICKS = 14.0;
    private static final float HARD_CLIMB_PITCH_DEG = 30.0F;
    private static final float MAX_DIVE_PITCH_DEG = 55.0F;
    /** Yaw rate scale in the reversal — gentle, so a heavy hull's momentum can follow the nose. */
    private static final double TURN_YAW_SCALE = 0.5;
    private static final double TURN_ALIGN_DEG = 35.0;

    // --- Hold ----------------------------------------------------------------------------------
    /** Hold circle radius as a multiple of the demonstrated turn radius — comfortably flyable. */
    private static final double HOLD_RADIUS_FACTOR = 1.35;
    private static final double HOLD_RADIUS_MIN = 48.0;

    // --- Landing -------------------------------------------------------------------------------
    private static final double LAND_GLIDE_RATIO = 0.35;
    private static final double LAND_MAX_APPROACH_HEIGHT = 90.0;
    private static final float LAND_FLARE_PITCH_DEG = -8.0F;
    /** Length of the final approach leg; the fix sits this far back up the axis from the pad. */
    private static final double FINAL_LEG_LENGTH = 140.0;
    /** How far off the axis still counts as established. */
    private static final double APPROACH_CORRIDOR = 24.0;
    private static final double APPROACH_HEADING_TOLERANCE_DEG = 40.0;
    /** Pure-pursuit lookahead down the axis — short enough to converge, long enough not to weave. */
    private static final double APPROACH_LOOKAHEAD = 40.0;
    /** Flown past the pad by this much on final: go around. */
    private static final double APPROACH_OVERSHOOT_MARGIN = 12.0;
    /** Approach axis candidates, tried in order from "straight in from where we are". */
    private static final double[] APPROACH_FAN_DEG = {
            0.0, 30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 130.0, -130.0, 180.0
    };
    private static final double APPROACH_SAMPLE_STEP = 8.0;
    private static final double APPROACH_CLEARANCE = 10.0;
    /** Speed above which the approach runs reduced power; SBW's throttle has no analogue setting. */
    private static final double APPROACH_SPEED_CAP = 0.9;
    private static final int APPROACH_THROTTLE_PERIOD = 4;
    private static final int APPROACH_THROTTLE_ON = 1;
    /** Ground speed below which the roll-out counts as finished, wherever it finished. */
    private static final double ROLLOUT_STOP_SPEED = 0.05;

    // --- Escort --------------------------------------------------------------------------------
    private static final double FACTION_ESCORT_RANGE = 256.0;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    private final HullFacts hull = new HullFacts();
    private final AirTerrainSensor sensor;
    private final DecoyEpisode flares = new DecoyEpisode();
    private final ChunkTicket chunkTicket = new ChunkTicket();
    private final PlaneKinematics kinematics = new PlaneKinematics();
    private final PlaneTerrain terrain = new PlaneTerrain();
    private final PlaneLeash leash = new PlaneLeash();

    private VehicleEntity vehicle;
    private PlaneController control;
    private PlaneWeapons weapons;

    private PlaneMode mode = PlaneMode.GROUNDED;

    // Takeoff roll heading, NaN until the roll begins.
    private double takeoffDirX = Double.NaN;
    private double takeoffDirZ = Double.NaN;

    // Locked straight-line heading of the current attack run (NaN = none).
    private double runDirX = Double.NaN;
    private double runDirZ = Double.NaN;
    private double runStartX;
    private double runStartZ;

    /** Temporary altitude floor held while climbing out of a fully blocked cone. */
    private double avoidFloorY = Double.NaN;
    /** Absolute game time before the next RU/US emergency-pad search. */
    private long nextEmergencyLandTry = Long.MIN_VALUE;

    /**
     * Where an anchorless hold is centred. It has to be remembered: centring the circle on the
     * aircraft's own live position makes the offset zero every tick, the orbit field degenerates,
     * and the "hold" is a straight line to the horizon — the exact drift this rewrite exists to
     * stop, reintroduced by the laziest possible fallback.
     */
    @Nullable
    private Vec3 holdCentre;

    // Ally lookup is a 256-block entity scan. Target inheritance, the destination and the leash
    // anchor all want it in the same tick, so it is resolved once and shared.
    private long allyScanTick = Long.MIN_VALUE;
    @Nullable
    private VehicleEntity cachedAllyHull;
    @Nullable
    private AbstractUnit cachedAllyUnit;

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
        if (this.vehicle != v) {
            this.vehicle = v;
            this.control = new PlaneController(v);
            this.weapons = new PlaneWeapons(v, this.unit);
            this.kinematics.reset();
            this.terrain.clear();
            SewvDiag.planeAttached();
        }
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
        return true; // stick inputs decay x0.95/tick and loops close against live velocity
    }

    @Override
    public void start() {
        // A freshly boarded plane on the ground stays parked until an explicit takeoff order.
        if (this.vehicle != null && this.vehicle.onGround()
                && this.unit instanceof PmcUnitEntity
                && this.unit instanceof IHelicopterPilot pilot
                && pilot.sewv$getHeliCommand() == IHelicopterPilot.HELI_CMD_NONE) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        }
        this.mode = PlaneMode.GROUNDED;
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            AirframeSupport.releaseInputs(this.vehicle);
            AirframeSupport.clearDecoy(this.vehicle);
            this.chunkTicket.release(this.vehicle);
        }
        this.vehicle = null;
        this.control = null;
        if (this.weapons != null) this.weapons.reset();
        this.takeoffDirX = Double.NaN;
        this.takeoffDirZ = Double.NaN;
        resetRun();
        this.allyAssist.clear();
        this.sensor.clear();
        this.terrain.clear();
        this.leash.reset();
        this.kinematics.reset();
        this.avoidFloorY = Double.NaN;
        this.nextEmergencyLandTry = Long.MIN_VALUE;
        this.holdCentre = null;
        this.allyScanTick = Long.MIN_VALUE;
        this.cachedAllyHull = null;
        this.cachedAllyUnit = null;
        this.mode = PlaneMode.GROUNDED;
    }

    @Override
    public void tick() {
        AirframeSupport.updateChunkLoading(this.chunkTicket, this.vehicle,
                SewvConfig.PLANE_CHUNK_LOADING.get());
        AirframeSupport.updateDecoy(this.vehicle, this.unit, this.flares,
                DECOY_HEALTH_FRACTION, PRESERVE_DECOY_CHANCE);

        long now = this.unit.level().getGameTime();
        this.kinematics.sample(this.vehicle, now);
        refreshAllies(now);

        // Unflyable: SBW is already taking the aircraft down, or there is nothing to fly it with.
        float max = this.vehicle.getMaxHealth();
        if ((max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION)
                || this.vehicle.getEnergy() <= 0) {
            this.control.release();
            return;
        }

        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        maybeEmergencyLand(pilot, max, now);

        LivingEntity target = resolveCombatTarget();
        PlaneMode next = chooseMode(pilot, target, now);
        if (next != this.mode) {
            onModeChange(this.mode, next, target);
            this.mode = next;
        }
        if (SewvDiag.planeVerbose()) {
            SewvDiag.planeThrottled(now, "mode={} leash={} spd={} agl={} r={} target={}",
                    this.mode, this.leash.state(),
                    String.format("%.2f", this.kinematics.speed()),
                    String.format("%.1f", this.kinematics.agl()),
                    String.format("%.0f", this.kinematics.turnRadius()),
                    target == null ? "none" : target.getName().getString());
        }

        // Every branch below issues a complete set of inputs. There is no "do nothing" case: the
        // sticks decay, so a silent tick is a control release at flying speed.
        switch (this.mode) {
            case LANDED, GROUNDED -> this.control.release();
            case TAKEOFF -> doTakeoff(pilot);
            case CLIMBOUT -> climbout();
            case LAND_PATTERN -> landPattern(pilot);
            case LAND_FINAL -> landFinal(pilot);
            case RTB -> returnToAnchor();
            case INGRESS -> ingress(target);
            case ATTACK -> attack(target);
            case BREAK -> breakOff(target);
            case CRUISE -> cruise();
            case HOLD -> hold();
        }
    }

    // --- Mode selection ---------------------------------------------------------------------

    /**
     * The whole precedence order, in one block, deliberately. It used to be a chain of early
     * returns spread down a 400-line tick method, which is how a landing order and an attack run
     * could both believe they owned the aircraft.
     */
    private PlaneMode chooseMode(@Nullable IHelicopterPilot pilot, @Nullable LivingEntity target,
                                 long now) {
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;

        // Ordered to land: nothing outranks it, and the order is re-asserted every tick so no
        // combat or order-queue change can quietly retask the aircraft mid-approach.
        if (command == IHelicopterPilot.HELI_CMD_LANDING || forcedLand()) {
            BlockPos pad = resolveLandPad(pilot);
            if (pad == null) {
                clearLanding(pilot);
            } else {
                if (pilot != null) {
                    pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDING);
                    pilot.sewv$setHeliLandPos(pad);
                }
                this.unit.setTarget(null); // do not fight while landing
                return this.mode == PlaneMode.LAND_FINAL ? PlaneMode.LAND_FINAL
                        : PlaneMode.LAND_PATTERN;
            }
        }

        if (command == IHelicopterPilot.HELI_CMD_LANDED) return PlaneMode.LANDED;
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) return PlaneMode.TAKEOFF;

        // Hostile crews take no player flight orders and never sit parked: any grounded NONE state
        // (spawn edge case, world reload) resolves to takeoff. Sticky LANDED after an emergency
        // landing stays down — that is the point of the emergency procedure.
        if (this.vehicle.onGround()) {
            if (pilot != null && !(this.unit instanceof PmcUnitEntity)) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_TAKEOFF);
                return PlaneMode.TAKEOFF;
            }
            return PlaneMode.GROUNDED;
        }

        // Still below the flight band right after a roll: climb before anything else asks for a
        // manoeuvre, or the first turn is flown at treetop height.
        if (this.mode == PlaneMode.CLIMBOUT && this.kinematics.agl() < CLIMBOUT_ABOVE_GROUND) {
            return PlaneMode.CLIMBOUT;
        }

        PlaneLeash.State tether = this.leash.update(anchor(), this.vehicle.position(),
                SewvConfig.PLANE_COMMAND_RADIUS.get());
        if (tether == PlaneLeash.State.RETURN) return PlaneMode.RTB;

        if (target != null) {
            // Recalled: see the current pass out, then go home. Breaking off mid-dive at speed is
            // worse than finishing it, and starting a NEW run is what actually walks the aircraft
            // away over successive passes.
            if (tether == PlaneLeash.State.RECALL) {
                if (this.mode == PlaneMode.ATTACK) return PlaneMode.ATTACK;
                return PlaneMode.RTB;
            }
            return combatMode(target, now);
        }

        resetRun();
        if (tether == PlaneLeash.State.RECALL) return PlaneMode.RTB;
        return destination() != null ? PlaneMode.CRUISE : PlaneMode.HOLD;
    }

    /** Where in the attack cycle we are. Transitions out of ATTACK/BREAK live in their handlers. */
    private PlaneMode combatMode(LivingEntity target, long now) {
        if (this.mode == PlaneMode.ATTACK || this.mode == PlaneMode.BREAK) return this.mode;

        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > SewvConfig.PLANE_ENGAGE_RADIUS.get()) return PlaneMode.INGRESS;

        // Inside the bubble but the dive would fly us into the ground: keep working the geometry
        // rather than committing. An attack run that has to be abandoned halfway is a pass wasted
        // and, on a heavy hull, a recovery that may not fit under the terrain.
        Vec3 dir = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : this.kinematics.forwardFlat();
        if (!diveWouldBeSafe(target, dir)) {
            // The single most useful line in this whole file when a plane "just won't attack".
            SewvDiag.planeThrottled(now, "dive refused dist={} y={} floor={} — orbiting",
                    String.format("%.0f", dist), String.format("%.0f", this.vehicle.getY()),
                    String.format("%.0f", attackFloorY(target)));
            return PlaneMode.INGRESS;
        }
        return PlaneMode.ATTACK;
    }

    private void onModeChange(PlaneMode from, PlaneMode to, @Nullable LivingEntity target) {
        if (to == PlaneMode.ATTACK && target != null) {
            startRun(target);
        }
        if (from == PlaneMode.ATTACK) {
            resetRun();
        }
        if (to == PlaneMode.TAKEOFF) {
            this.takeoffDirX = Double.NaN;
            this.takeoffDirZ = Double.NaN;
        }
        if (to == PlaneMode.HOLD) {
            // Anchor the circle where the hold began, not wherever the aircraft drifts to.
            Vec3 anchor = this.leash.anchor();
            this.holdCentre = anchor != null ? anchor : this.vehicle.position();
        } else if (from == PlaneMode.HOLD) {
            this.holdCentre = null;
        }
        if (!to.isLanding() && from.isLanding()) {
            this.terrain.clear();
        }
        SewvDiag.plane("mode {} -> {}", from, to);
    }

    // --- Targets, destinations, anchors -------------------------------------------------------

    /**
     * The target this aircraft may attack. A PMC fights its own target unless an explicit movement
     * order pins the flight path; RU/US have no order queue, so they inherit from the ally they are
     * supporting and stay tied to that fight.
     */
    @Nullable
    private LivingEntity resolveCombatTarget() {
        if (this.unit instanceof PmcUnitEntity) {
            if (flightPinnedByOrder()) return null;
            return this.unit.getTarget();
        }
        if (this.cachedAllyHull != null
                && this.cachedAllyHull.getFirstPassenger() instanceof AbstractUnit driver) {
            inheritTarget(driver);
        } else if (this.cachedAllyUnit != null) {
            inheritTarget(this.cachedAllyUnit);
        }
        return this.unit.getTarget();
    }

    /** One ally scan per tick, shared by target inheritance, the destination and the leash. */
    private void refreshAllies(long now) {
        if (this.unit instanceof PmcUnitEntity || now == this.allyScanTick) return;
        this.allyScanTick = now;
        this.cachedAllyHull = findNearestGroundAlly();
        this.cachedAllyUnit = this.cachedAllyHull == null ? findNearestAllyUnit() : null;
    }

    private void inheritTarget(AbstractUnit ally) {
        LivingEntity theirs = ally.getTarget();
        if (VehicleTargeting.mayAssignTarget(this.unit, theirs) && theirs != this.unit) {
            this.unit.setTarget(theirs);
        }
    }

    @Nullable
    private BlockPos destination() {
        if (this.unit instanceof PmcUnitEntity) {
            return VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        }
        if (this.cachedAllyHull != null) return this.cachedAllyHull.blockPosition();
        return this.cachedAllyUnit != null ? this.cachedAllyUnit.blockPosition() : null;
    }

    /**
     * The point the aircraft is tethered to. A PMC answers to its owner; RU/US answer to the local
     * fight, which is the nearest ground ally. With neither, the leash releases rather than pinning
     * the aircraft to a stale position.
     */
    @Nullable
    private Vec3 anchor() {
        if (this.unit instanceof PmcUnitEntity) {
            return PlaneLeash.ownerAnchor(this.unit);
        }
        if (this.cachedAllyHull != null) return this.cachedAllyHull.position();
        return PlaneLeash.entityAnchor(this.cachedAllyUnit);
    }

    // Same pin set as the helicopter goal: an explicit movement/hold order owns the flight path so
    // a retaliation target can't drag the whole aircraft into a strafing run.
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

    // --- Takeoff ------------------------------------------------------------------------------

    private void doTakeoff(@Nullable IHelicopterPilot pilot) {
        if (Double.isNaN(this.takeoffDirX)) {
            Vec3 clear = pickRunwayHeading();
            if (clear == null) {
                abortTakeoff(pilot);
                return;
            }
            this.takeoffDirX = clear.x;
            this.takeoffDirZ = clear.z;
        }

        // Airborne and clear of the terrain: gear up, hand over to the climbout.
        if (!this.vehicle.onGround() && this.kinematics.agl() >= CLIMBOUT_ABOVE_GROUND) {
            this.vehicle.setGearUp(true);
            if (pilot != null) pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            this.takeoffDirX = Double.NaN;
            this.takeoffDirZ = Double.NaN;
            this.mode = PlaneMode.CLIMBOUT;
            climbout();
            return;
        }

        if (this.vehicle.onGround()) this.vehicle.setGearUp(false);
        this.control.throttleUp();
        this.control.steerYaw(new Vec3(this.takeoffDirX, 0, this.takeoffDirZ));
        boolean rotate = this.kinematics.speed() >= ROTATE_SPEED;
        this.control.commandPitch(rotate ? -TAKEOFF_PITCH_DEG : 0.0F);
    }

    /** Straight ahead and up until the flight band is reached. No turns down here. */
    private void climbout() {
        this.control.throttleUp();
        this.vehicle.setGearUp(true);
        Vec3 ahead = this.kinematics.forwardFlat();
        Vec3 clear = PlaneTerrain.clearBearing(this.sensor, ahead, this.kinematics.speed());
        this.control.steerYaw(clear != null ? clear : ahead, TURN_YAW_SCALE);
        this.control.commandPitch(-TAKEOFF_PITCH_DEG);
    }

    // Pick a takeoff heading by a GROUND-relative clearance scan, NOT the airborne whisker: the
    // flight sensor probes a slab from floor(Y)-1 up, which on the ground is the block the plane
    // sits on, so it reported every direction blocked and takeoff needed the plane lifted a block
    // first. This compares the surface height ahead to the plane's own instead.
    @Nullable
    private Vec3 pickRunwayHeading() {
        Vec3 facing = this.kinematics.forwardFlat();
        for (double offDeg : RUNWAY_FAN_DEG) {
            Vec3 cand = VehicleTargeting.rotateY(facing, Math.toRadians(offDeg));
            if (runwayClearAhead(cand, TAKEOFF_RUNWAY_RADIUS)) return cand;
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
            if (surf - baseSurface > RUNWAY_MAX_STEP) return false;
        }
        return true;
    }

    private void abortTakeoff(@Nullable IHelicopterPilot pilot) {
        this.control.release();
        if (pilot != null) pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        this.mode = PlaneMode.LANDED;
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

    // --- Cruise / hold / return ----------------------------------------------------------------

    private void cruise() {
        BlockPos dest = destination();
        if (dest == null) {
            hold();
            return;
        }
        double px = dest.getX() + 0.5;
        double pz = dest.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        double holdRadius = holdRadius();
        if (dx * dx + dz * dz <= holdRadius * holdRadius) {
            // A plane cannot sit over a point: hold a circle about it instead.
            holdAbout(new Vec3(px, 0, pz),
                    Math.max(cruiseAltitudeHere(), dest.getY() + MIN_OVER_DEST));
            return;
        }
        flyToward(px, pz, Math.max(cruiseAltitudeToward(px, pz), dest.getY() + MIN_OVER_DEST));
    }

    /**
     * No destination: a closed circle about the anchor, or about the point the hold was entered at
     * when there is nobody to answer to. That remembered point is the whole difference between a
     * hold and a departure — a circle centred on the aircraft's own live position has zero offset
     * every tick, which the orbit field correctly answers as "fly straight".
     */
    private void hold() {
        Vec3 anchor = this.leash.anchor();
        if (anchor != null) {
            this.holdCentre = anchor;
        } else if (this.holdCentre == null) {
            this.holdCentre = this.vehicle.position();
        }
        holdAbout(this.holdCentre, cruiseAltitudeHere());
    }

    /** Ordered or leashed home: fly at the anchor until back inside, then normal duty resumes. */
    private void returnToAnchor() {
        Vec3 home = this.leash.anchor();
        if (home == null) {
            hold();
            return;
        }
        double dx = home.x - this.vehicle.getX();
        double dz = home.z - this.vehicle.getZ();
        double holdRadius = holdRadius();
        if (dx * dx + dz * dz <= holdRadius * holdRadius) {
            holdAbout(home, Math.max(cruiseAltitudeHere(), home.y + MIN_OVER_DEST));
            return;
        }
        flyToward(home.x, home.z,
                Math.max(cruiseAltitudeToward(home.x, home.z), home.y + MIN_OVER_DEST));
    }

    /**
     * Hold a circle about a point. The old idle behaviour was a constant yaw stick, which is an
     * open loop: the turn rate depends on airspeed, so the "orbit" drifted with every speed change
     * and the aircraft slowly wandered off. This closes the loop against the centre every tick.
     */
    private void holdAbout(Vec3 centre, double desiredY) {
        double radius = holdRadius();
        Vec3 steer = PlaneNav.orbitSteer(this.vehicle.getX() - centre.x,
                this.vehicle.getZ() - centre.z, radius, orbitClockwise());
        this.control.throttleUp();
        Vec3 clear = PlaneTerrain.clearBearing(this.sensor, steer, this.kinematics.speed());
        if (clear == null) {
            avoidBlocked(steer, desiredY);
            return;
        }
        this.control.steerYaw(clear);
        this.control.holdAltitude(applyAvoidFloor(desiredY));
    }

    /**
     * Circle radius sized off the hull's own demonstrated agility. A fixed radius is either
     * unflyable for a heavy jet (which then spirals outward, drifting) or needlessly wide for a
     * nimble one.
     */
    private double holdRadius() {
        return Math.max(HOLD_RADIUS_MIN, this.kinematics.turnRadius() * HOLD_RADIUS_FACTOR);
    }

    /** Entity-id parity, so two aircraft holding the same anchor turn opposite ways. */
    private boolean orbitClockwise() {
        return (this.vehicle.getId() & 1) == 0;
    }

    /**
     * The one lateral primitive: find a bearing that is actually flyable, turn onto it, hold the
     * altitude. Terrain is consulted in two independent ways because they fail differently — the
     * corridor search reads the heightmap and routes AROUND high ground, the whisker reads real
     * blocks and catches the things a heightmap cannot (a wall, a bridge, another aircraft).
     */
    private void flyToward(double destX, double destZ, double desiredY) {
        Vec3 avoid = com.neoalive.tacz_sewv.compat.ExterminationPodAvoidance.adjustHorizontal(
                this.vehicle, destX, destZ);
        destX = avoid.x;
        destZ = avoid.z;

        this.control.throttleUp();

        double dx = destX - this.vehicle.getX();
        double dz = destZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dirToDest = dist > 1.0E-4
                ? new Vec3(dx / dist, 0, dz / dist) : this.kinematics.forwardFlat();

        double held = applyAvoidFloor(desiredY);
        Vec3 corridor = this.terrain.corridorBearing(this.unit.level(),
                this.vehicle.getX(), this.vehicle.getZ(), dirToDest, held,
                Math.min(dist, TERRAIN_LOOKAHEAD), this.unit.level().getGameTime());
        Vec3 desiredBearing = corridor != null ? corridor : dirToDest;

        Vec3 travelDir = PlaneTerrain.clearBearing(this.sensor, desiredBearing,
                this.kinematics.speed());
        if (travelDir == null) {
            avoidBlocked(desiredBearing, desiredY);
            return;
        }
        this.control.steerYaw(travelDir);
        this.control.holdAltitude(held);
    }

    /**
     * Boxed in: climb, and keep climbing until clear. Raising a floor rather than commanding one
     * nose-up tick is what makes this work at speed — momentum eats a single pitch input, and the
     * aircraft arrives at the obstacle still level.
     */
    private void avoidBlocked(Vec3 desiredBearing, double desiredY) {
        double floor = this.vehicle.getY() + AVOID_CLIMB_STEP;
        this.avoidFloorY = Double.isNaN(this.avoidFloorY)
                ? floor : Math.max(this.avoidFloorY, floor);
        this.control.throttleUp();
        this.control.steerYaw(desiredBearing, TURN_YAW_SCALE);
        this.control.commandPitch(-CLIMB_AVOID_PITCH_DEG);
        SewvDiag.plane("blocked cone, climbing to floor {}", this.avoidFloorY);
    }

    /** Surplus avoidance height is given back gradually, and only once it is no longer needed. */
    private double applyAvoidFloor(double desiredY) {
        if (Double.isNaN(this.avoidFloorY)) return desiredY;
        if (this.avoidFloorY <= desiredY) {
            this.avoidFloorY = Double.NaN;
            return desiredY;
        }
        this.avoidFloorY -= AVOID_FLOOR_DECAY;
        return Math.max(desiredY, this.avoidFloorY);
    }

    // --- Combat --------------------------------------------------------------------------------

    /**
     * Closing on the target: come down to the roll-in height, wings mostly level, no diving yet.
     *
     * <p>The descent is the point. Ingress used to hold the full cruise band right up to the
     * engagement, which leaves the aircraft directly above its target with nothing to do but a
     * near-vertical dive it cannot pull out of — so it flew over instead, again and again. Coming
     * down while still 100+ blocks out converts that into an ordinary shallow run-in, and the
     * terrain floor below keeps the descent honest over anything in the way.
     */
    private void ingress(@Nullable LivingEntity target) {
        if (target == null) {
            hold();
            return;
        }
        double approachY = Math.max(rollInY(target),
                AirframeSupport.highestGroundToward(this.vehicle, target.getX(), target.getZ(),
                        TERRAIN_LOOKAHEAD) + MIN_INGRESS_CLEARANCE);

        double dist = Math.hypot(target.getX() - this.vehicle.getX(),
                target.getZ() - this.vehicle.getZ());
        if (dist < SewvConfig.PLANE_ENGAGE_RADIUS.get()) {
            // Already inside the bubble and still not attacking — the dive must have been refused.
            // Circling keeps the target in reach while the geometry changes; flying at it just
            // puts the aircraft over the top of it with nothing to shoot, which is the overfly.
            holdAbout(new Vec3(target.getX(), 0.0, target.getZ()), approachY);
        } else {
            flyToward(target.getX(), target.getZ(), approachY);
        }
        // A shot that lines up on the way in is still a shot, and it is gated exactly as tightly
        // as one on the run — this is not the old "spray while transiting" path.
        this.weapons.ensureSelected(target);
        this.weapons.arm();
        // Bombs are never pickled off the transit: a release is a predicted-impact decision made on
        // a stable run-in, and the ordinary fire gate knows nothing about where one would land.
        if (!this.weapons.hasBombSelected()) {
            this.weapons.fire(target, this.weapons.aimPoint(target));
        }
    }

    /** Lock the run axis and pick the weapon for what we are about to attack. */
    private void startRun(LivingEntity target) {
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0,
                target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : this.kinematics.forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
        this.weapons.beginRun(target);
        SewvDiag.plane("run start kind={} dir=({},{})", this.weapons.selectedKind(),
                String.format("%.2f", dir.x), String.format("%.2f", dir.z));
    }

    private void resetRun() {
        this.runDirX = Double.NaN;
        this.runDirZ = Double.NaN;
    }

    /**
     * The attack run: hold the locked line, point the nose at the intercept, and fire only when the
     * nose is genuinely there. The run is short by design — the old 440-block line is most of why a
     * plane in a fight ended up on the other side of the map.
     */
    private void attack(@Nullable LivingEntity target) {
        if (target == null) {
            this.mode = PlaneMode.HOLD;
            hold();
            return;
        }
        if (Double.isNaN(this.runDirX)) startRun(target);

        this.control.throttleUp();

        double gx = this.vehicle.getX();
        double gz = this.vehicle.getZ();
        double distFromStart = Math.hypot(gx - this.runStartX, gz - this.runStartZ);

        // Overfly test on the LOCKED axis, not on raw distance: "have we passed it" is a projection
        // question, and measuring it any other way breaks the moment the target moves.
        double planeAlong = (gx - this.runStartX) * this.runDirX
                + (gz - this.runStartZ) * this.runDirZ;
        double targetAlong = (target.getX() - this.runStartX) * this.runDirX
                + (target.getZ() - this.runStartZ) * this.runDirZ;
        boolean passedTarget = planeAlong > targetAlong + OVERFLY_MARGIN;

        // Predictive pull-up: anticipate the sink during recovery from the CURRENT descent rate, so
        // a fast steep dive breaks earlier while a shallow one may press in close.
        int groundRef = Math.max(this.kinematics.surfaceBelow(),
                PlaneTerrain.ridgeToward(this.vehicle, gx + this.runDirX * 24.0,
                        gz + this.runDirZ * 24.0, TERRAIN_LOOKAHEAD));
        double clearance = this.vehicle.getY() - groundRef;
        double pullupTrigger = MIN_ATTACK_CLEARANCE + this.kinematics.sinkRate() * PULLUP_LEAD_TICKS;

        if (passedTarget || clearance <= pullupTrigger
                || distFromStart >= SewvConfig.PLANE_ATTACK_RUN_LENGTH.get()) {
            this.mode = PlaneMode.BREAK;
            resetRun();
            this.control.holdHeading();
            this.control.commandPitch(-HARD_CLIMB_PITCH_DEG);
            return;
        }

        // Aim both axes at the intercept point, not at where the target is standing. Firing is
        // gated on the same point, so the shot goes when the geometry is right rather than when a
        // generous cone happens to admit it.
        this.weapons.arm();
        Vec3 aim = this.weapons.aimPoint(target);
        this.control.steerYaw(new Vec3(aim.x - gx, 0, aim.z - gz));

        double horiz = Math.hypot(aim.x - gx, aim.z - gz);
        double depressionDeg = Math.toDegrees(
                Math.atan2(this.vehicle.getY() - aim.y, Math.max(horiz, 1.0)));
        this.control.commandPitch((float) Mth.clamp(depressionDeg,
                -PlaneController.MAX_CRUISE_PITCH_DEG, MAX_DIVE_PITCH_DEG));

        if (this.weapons.hasBombSelected()) {
            this.weapons.releaseBombIfOnTarget(target, this.kinematics.forwardFlat());
        } else {
            this.weapons.fire(target, aim);
        }
    }

    /**
     * Reverse for the next pass: climb away and turn back onto the target at a gentle rate, because
     * a hard yaw on a heavy hull skids rather than turns. Finishes when the nose is back on the
     * target and the height lost in the dive has been regained.
     */
    private void breakOff(@Nullable LivingEntity target) {
        if (target == null) {
            this.mode = PlaneMode.HOLD;
            hold();
            return;
        }
        this.control.throttleUp();

        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0,
                target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : this.kinematics.forwardFlat();
        double recoverY = cruiseAltitudeHere();

        Vec3 corridor = this.terrain.corridorBearing(this.unit.level(),
                this.vehicle.getX(), this.vehicle.getZ(), dir, recoverY, TERRAIN_LOOKAHEAD,
                this.unit.level().getGameTime());
        Vec3 wanted = corridor != null ? corridor : dir;
        Vec3 clear = PlaneTerrain.clearBearing(this.sensor, wanted, this.kinematics.speed());
        if (clear == null) {
            avoidBlocked(wanted, recoverY);
            return;
        }
        this.control.steerYaw(clear, TURN_YAW_SCALE);

        // The break exists to buy back the height the dive spent, so it is over at exactly the
        // height the next run starts from. Any other number here makes the aircraft climb past the
        // roll-in altitude and immediately descend again between every pass.
        boolean recovered = this.kinematics.agl() >= ATTACK_ENTRY_AGL;
        if (!recovered) {
            this.control.commandPitch(-HARD_CLIMB_PITCH_DEG);
        } else {
            this.control.holdAltitude(recoverY);
        }

        double yawErr = PlaneNav.headingErrorDeg(this.kinematics.forwardFlat(), dir);
        if (yawErr < TURN_ALIGN_DEG && recovered) {
            this.mode = PlaneMode.INGRESS;
        }
    }

    /** Would the dive along {@code dir} clear the ground all the way in? */
    private boolean diveWouldBeSafe(LivingEntity target, Vec3 dir) {
        double runLength = Math.min(SewvConfig.PLANE_ATTACK_RUN_LENGTH.get(),
                Math.hypot(target.getX() - this.vehicle.getX(),
                        target.getZ() - this.vehicle.getZ()) + OVERFLY_MARGIN);
        return PlaneTerrain.diveSafe(this.unit.level(), this.vehicle.getX(), this.vehicle.getY(),
                this.vehicle.getZ(), dir, runLength, attackFloorY(target), DIVE_PATH_CLEARANCE);
    }

    /**
     * The lowest altitude a run is planned down to. For something on the ground that is the pull-up
     * floor over its own terrain; for something already flying it is that aircraft's altitude,
     * since there is no reason to stay above a helicopter you are shooting at.
     */
    private double attackFloorY(LivingEntity target) {
        return Math.max(groundAt(target) + MIN_ATTACK_CLEARANCE, target.getY());
    }

    /** Height the ingress descends to so that the roll-in is a flyable angle rather than a plunge. */
    private double rollInY(LivingEntity target) {
        return Math.max(groundAt(target) + ATTACK_ENTRY_AGL, target.getY() + MIN_OVER_DEST);
    }

    private int groundAt(LivingEntity target) {
        return this.unit.level().getHeight(Heightmap.Types.WORLD_SURFACE,
                Mth.floor(target.getX()), Mth.floor(target.getZ()));
    }

    // --- Landing -------------------------------------------------------------------------------

    private boolean forcedLand() {
        return this.vehicle.getPersistentData().getBoolean(TAG_FORCED_LAND);
    }

    /** Pilot NBT is authoritative; the hull tag is the backup that survives a goal rebuild. */
    @Nullable
    private BlockPos resolveLandPad(@Nullable IHelicopterPilot pilot) {
        BlockPos pad = pilot != null ? pilot.sewv$getHeliLandPos() : null;
        if (pad != null) return pad;
        CompoundTag tag = this.vehicle.getPersistentData();
        return tag.contains(TAG_LAND_PAD) ? BlockPos.of(tag.getLong(TAG_LAND_PAD)) : null;
    }

    private void clearLanding(@Nullable IHelicopterPilot pilot) {
        if (pilot != null) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            pilot.sewv$setHeliLandPos(null);
        }
        clearForcedLand(this.vehicle);
    }

    private void settleLanded(@Nullable IHelicopterPilot pilot) {
        this.control.release();
        if (pilot != null) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
            pilot.sewv$setHeliLandPos(null);
        }
        clearForcedLand(this.vehicle);
        this.mode = PlaneMode.LANDED;
        SewvDiag.plane("landed");
    }

    /**
     * Fly the pattern: get to the initial approach fix, at a height that clears everything between
     * here and there, so that the turn onto final happens aligned and high. This is the half that
     * did not exist before — the old code descended a glideslope straight at the pad from wherever
     * it happened to be, which is why it flew through ridges, arrived across the strip, and either
     * skimmed the ground or planted itself somewhere else entirely and called that a landing.
     */
    private void landPattern(@Nullable IHelicopterPilot pilot) {
        BlockPos pad = resolveLandPad(pilot);
        if (pad == null) {
            clearLanding(pilot);
            return;
        }
        this.vehicle.setGearUp(false);

        double padX = pad.getX() + 0.5;
        double padZ = pad.getZ() + 0.5;
        Vec3 axis = approachAxis(padX, padZ);
        double dx = padX - this.vehicle.getX();
        double dz = padZ - this.vehicle.getZ();
        double along = PlaneNav.alongTrack(dx, dz, axis);
        double cross = PlaneNav.crossTrack(dx, dz, axis);
        double headingErr = PlaneNav.headingErrorDeg(this.kinematics.forwardFlat(), axis);

        if (PlaneNav.established(cross, along, headingErr, APPROACH_CORRIDOR, FINAL_LEG_LENGTH,
                APPROACH_HEADING_TOLERANCE_DEG)) {
            this.mode = PlaneMode.LAND_FINAL;
            landFinal(pilot);
            return;
        }

        // Already on the ground next to the pad — a plane ordered to land where it is parked. Take
        // the arrival rather than flying a circuit to get back to the spot it is standing on.
        double distToPad = Math.sqrt(dx * dx + dz * dz);
        if (this.vehicle.onGround()
                && distToPad <= SewvConfig.PLANE_LAND_SETTLE_RADIUS.get()) {
            settleLanded(pilot);
            return;
        }

        Vec3 fix = PlaneNav.approachFix(padX, padZ, axis, FINAL_LEG_LENGTH);
        double transitY = Math.max(
                pad.getY() + SewvConfig.PLANE_LAND_TRANSIT_AGL.get(),
                AirframeSupport.highestGroundToward(this.vehicle, fix.x, fix.z, TERRAIN_LOOKAHEAD)
                        + SewvConfig.PLANE_LAND_TRANSIT_AGL.get());
        flyToward(fix.x, fix.z, transitY);
        SewvDiag.planeThrottled(this.unit.level().getGameTime(),
                "pattern along={} cross={} hdgErr={} transitY={}",
                String.format("%.0f", along), String.format("%.0f", cross),
                String.format("%.0f", headingErr), String.format("%.0f", transitY));
    }

    /**
     * Established on final: track the axis by pure pursuit, ride the glideslope down, flare low AND
     * near the pad, touch down and roll out. The only way out of a final that is not a landing is
     * flying past the pad with the wheels still up; once they are down the aircraft is committed
     * and {@link #rollOut} finishes the job.
     */
    private void landFinal(@Nullable IHelicopterPilot pilot) {
        BlockPos pad = resolveLandPad(pilot);
        if (pad == null) {
            clearLanding(pilot);
            return;
        }
        this.vehicle.setGearUp(false);

        double padX = pad.getX() + 0.5;
        double padZ = pad.getZ() + 0.5;
        Vec3 axis = approachAxis(padX, padZ);
        double dx = padX - this.vehicle.getX();
        double dz = padZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double along = PlaneNav.alongTrack(dx, dz, axis);
        double settleRadius = SewvConfig.PLANE_LAND_SETTLE_RADIUS.get();

        if (this.vehicle.onGround()) {
            rollOut(pilot, axis, dist, settleRadius);
            return;
        }
        if (PlaneNav.overshot(along, APPROACH_OVERSHOOT_MARGIN)) {
            SewvDiag.plane("go-around: flown past the pad, dist={} along={}",
                    String.format("%.0f", dist), String.format("%.0f", along));
            this.mode = PlaneMode.LAND_PATTERN;
            this.vehicle.getPersistentData().remove(TAG_APPROACH_YAW); // re-plan the circuit
            landPattern(pilot);
            return;
        }

        // Steer at a carrot further down the axis rather than at the pad itself: aiming at the pad
        // makes the correction grow as you close on it, which is what produced the weave-and-skim.
        Vec3 carrot = PlaneNav.approachCarrot(padX, padZ, axis, Math.max(along, 0.0),
                APPROACH_LOOKAHEAD);
        this.control.steerYaw(new Vec3(carrot.x - this.vehicle.getX(), 0,
                carrot.z - this.vehicle.getZ()));

        double agl = this.vehicle.getY() - touchdownSurface(pad);
        if (PlaneNav.flareReady(agl, dist, SewvConfig.PLANE_LAND_FLARE_AGL.get(),
                SewvConfig.PLANE_LAND_FLARE_RADIUS.get())) {
            // Both gates matter. Height alone flared over whatever the aircraft happened to be
            // crossing, cut the power there, and sank into it.
            this.control.idleAndBrake();
            this.control.commandPitch(LAND_FLARE_PITCH_DEG);
            return;
        }

        // Approach speed: a fixed wing cannot idle without stalling, so "slower" is a duty cycle.
        // The air brake does the real work of shedding speed.
        if (this.kinematics.speed() > APPROACH_SPEED_CAP) {
            this.control.throttleDuty(this.unit.level().getGameTime(),
                    APPROACH_THROTTLE_PERIOD, APPROACH_THROTTLE_ON);
            this.control.airbrake(true);
        } else {
            this.control.throttleUp();
            this.control.airbrake(false);
        }

        double glideY = pad.getY() + Mth.clamp(along * LAND_GLIDE_RATIO,
                SewvConfig.PLANE_LAND_FLARE_AGL.get(), LAND_MAX_APPROACH_HEIGHT);
        this.control.holdAltitude(glideY);
        SewvDiag.planeThrottled(this.unit.level().getGameTime(),
                "final dist={} along={} agl={} glideY={} spd={}",
                String.format("%.0f", dist), String.format("%.0f", along),
                String.format("%.0f", agl), String.format("%.0f", glideY),
                String.format("%.2f", this.kinematics.speed()));
    }

    /**
     * On the wheels: brakes on, straight down the strip, done when it stops or reaches the pad.
     * This is a landing in progress and nothing may interrupt it — a rolling hull has no lift to
     * fly away with, so there is no "abort" available here even if the touchdown was untidy.
     */
    private void rollOut(@Nullable IHelicopterPilot pilot, Vec3 axis, double distToPad,
                         double settleRadius) {
        this.control.idleAndBrake();
        this.control.steerYaw(axis, TURN_YAW_SCALE);
        this.control.commandPitch(0.0F);
        if (PlaneNav.settled(true, distToPad, settleRadius, this.kinematics.speed(),
                ROLLOUT_STOP_SPEED)) {
            settleLanded(pilot);
            return;
        }
        SewvDiag.planeThrottled(this.unit.level().getGameTime(), "rolling out dist={} spd={}",
                String.format("%.0f", distToPad), String.format("%.2f", this.kinematics.speed()));
    }

    /**
     * The approach heading for this landing, chosen once and remembered on the hull. Candidates
     * start from "straight in from where we are", so an aircraft that is already lined up does not
     * fly a pointless circuit, and fan outward until one has a corridor the glideslope actually
     * fits through.
     */
    private Vec3 approachAxis(double padX, double padZ) {
        CompoundTag tag = this.vehicle.getPersistentData();
        if (tag.contains(TAG_APPROACH_YAW)) {
            double rad = Math.toRadians(tag.getDouble(TAG_APPROACH_YAW));
            return new Vec3(Math.sin(rad), 0, Math.cos(rad));
        }
        double dx = padX - this.vehicle.getX();
        double dz = padZ - this.vehicle.getZ();
        Vec3 preferred = (dx * dx + dz * dz) > 1.0E-4
                ? new Vec3(dx, 0, dz).normalize() : this.kinematics.forwardFlat();

        Vec3 chosen = preferred;
        for (double offDeg : APPROACH_FAN_DEG) {
            Vec3 cand = PlaneNav.rotateY(preferred, Math.toRadians(offDeg));
            if (approachCorridorClear(padX, padZ, cand)) {
                chosen = cand;
                break;
            }
        }
        tag.putDouble(TAG_APPROACH_YAW, Math.toDegrees(Math.atan2(chosen.x, chosen.z)));
        return chosen;
    }

    /** Does the glideslope along this axis clear the ground the whole way in to the pad? */
    private boolean approachCorridorClear(double padX, double padZ, Vec3 axis) {
        Level level = this.unit.level();
        int padY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(padX), Mth.floor(padZ));
        for (double d = APPROACH_SAMPLE_STEP; d <= FINAL_LEG_LENGTH; d += APPROACH_SAMPLE_STEP) {
            int x = Mth.floor(padX - axis.x * d);
            int z = Mth.floor(padZ - axis.z * d);
            int surf = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            double allowed = padY + Math.max(2.0, d * LAND_GLIDE_RATIO - APPROACH_CLEARANCE);
            if (surf > allowed) return false;
        }
        return true;
    }

    /** Feet-level surface the hull can actually sit on at the pad's column. */
    private double touchdownSurface(BlockPos pad) {
        return this.unit.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pad.getX(), pad.getZ());
    }

    // --- Emergency landing ---------------------------------------------------------------------

    private void maybeEmergencyLand(@Nullable IHelicopterPilot pilot, float maxHealth, long now) {
        if (pilot == null || this.unit instanceof PmcUnitEntity) return;
        int command = pilot.sewv$getHeliCommand();
        if (command == IHelicopterPilot.HELI_CMD_LANDING
                || command == IHelicopterPilot.HELI_CMD_LANDED) {
            return;
        }
        if (!(maxHealth > 0.0F)
                || this.vehicle.getHealth() >= maxHealth * EMERGENCY_LAND_HEALTH) {
            return;
        }
        if (now < this.nextEmergencyLandTry) return;

        BlockPos pad = pickEmergencyPad();
        if (pad == null) {
            this.nextEmergencyLandTry = now + EMERGENCY_LAND_RETRY_TICKS;
            return;
        }
        pilot.sewv$setHeliLandPos(pad);
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDING);
        setForcedLand(this.vehicle, pad);
    }

    /**
     * Flat-enough strip for an emergency arrival. Scored along the bearing the aircraft would
     * actually approach on rather than the one it happens to be pointing, since a pad that is only
     * flat crosswind is a pad it cannot land on.
     */
    @Nullable
    private BlockPos pickEmergencyPad() {
        Level level = this.unit.level();
        int bx = this.vehicle.getBlockX();
        int bz = this.vehicle.getBlockZ();
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
                    if (fieldClearOnApproach(pad)) return pad;
                }
            }
        }
        return null;
    }

    private boolean fieldClearOnApproach(BlockPos pad) {
        double ax = pad.getX() + 0.5 - this.vehicle.getX();
        double az = pad.getZ() + 0.5 - this.vehicle.getZ();
        Vec3 approach = (ax * ax + az * az) > 1.0E-4
                ? new Vec3(ax, 0, az).normalize() : this.kinematics.forwardFlat();
        Level level = this.unit.level();
        int base = pad.getY();
        // The roll-out beyond the touchdown point has to be flat too, or the aircraft lands into
        // the side of something.
        for (double d = 2.0; d <= 32.0; d += 2.0) {
            int px = Mth.floor(pad.getX() + 0.5 + approach.x * d);
            int pz = Mth.floor(pad.getZ() + 0.5 + approach.z * d);
            int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            if (Math.abs(surf - base) > RUNWAY_MAX_STEP) return false;
        }
        return true;
    }

    // --- Ally lookups ---------------------------------------------------------------------------

    @Nullable
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

    @Nullable
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

    // --- Altitude ------------------------------------------------------------------------------

    private double cruiseAltitudeHere() {
        return AirframeSupport.cruiseAltitudeHere(this.vehicle, flightAltitude());
    }

    private double cruiseAltitudeToward(double toX, double toZ) {
        return AirframeSupport.cruiseAltitudeToward(
                this.vehicle, toX, toZ, flightAltitude(), TERRAIN_LOOKAHEAD);
    }

    private double flightAltitude() {
        int alt = (this.unit instanceof IHelicopterPilot pilot)
                ? pilot.sewv$getCruiseAltitude() : IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
        return Mth.clamp(alt * ALT_SCALE, MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }
}
