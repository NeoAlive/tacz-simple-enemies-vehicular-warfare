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

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
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
import com.neoalive.tacz_sewv.item.PlaneAttackMode;
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
    /**
     * How much higher than the pilot's cruise stepper (30-50) a plane actually flies. This is the
     * vertical half of the operating scale and it is the one axis that cannot simply be multiplied
     * with the rest: the horizontal envelope has the whole world to grow into, while altitude runs
     * out of sky. At this scale a stepper of 40 puts a jet 200 blocks over the terrain, and terrain
     * of any height plus the top of the band is already near the build limit. Raising it further
     * buys nothing anyway — what a dive needs is the height to trade, and 160 is more than any
     * delivery in {@link #deliveryPitchLimit} can spend across the engage bubble.
     */
    private static final double ALT_SCALE = 4.0;
    private static final double MIN_FLIGHT_ALT = 120.0;
    private static final double MAX_FLIGHT_ALT = 240.0;
    private static final double MIN_OVER_DEST = 48.0;
    /**
     * How far ahead terrain is read. Deliberately <b>not</b> scaled with the engagement envelope:
     * this distance answers "can the aircraft still get out of the way", which is a function of its
     * turn radius and sink rate, and neither of those changed. It is scaled up somewhat regardless
     * because the cruise band did, and cruise altitude is picked from the highest ground along the
     * leg — a lookahead much shorter than the leg makes a high-flying aircraft step over each ridge
     * separately instead of clearing the range.
     */
    private static final double TERRAIN_LOOKAHEAD = 192.0;
    private static final float CLIMB_AVOID_PITCH_DEG = 25.0F;
    /**
     * When every bearing is blocked the aircraft climbs and holds a floor until it is clear, rather
     * than pitching up for one tick and letting momentum carry it into the face. Decays back so the
     * surplus height is given up gradually. Same shape as the helicopter's avoidance floor.
     */
    private static final double AVOID_CLIMB_STEP = 40.0;
    private static final double AVOID_FLOOR_DECAY = 0.15;

    // --- Takeoff -------------------------------------------------------------------------------
    private static final double TAKEOFF_RUNWAY_RADIUS = 64.0;
    private static final double ROTATE_SPEED = 0.35;
    private static final float TAKEOFF_PITCH_DEG = 15.0F;
    private static final double CLIMBOUT_ABOVE_GROUND = 160.0;
    private static final double[] RUNWAY_FAN_DEG = {0.0, 20.0, -20.0, 40.0, -40.0, 65.0, -65.0};
    private static final int RUNWAY_MAX_STEP = 2;

    // --- Combat --------------------------------------------------------------------------------
    private static final double OVERFLY_MARGIN = 32.0;
    private static final double MIN_ATTACK_CLEARANCE = 40.0;
    /**
     * Height above the ground at the target that the aircraft rolls in from, and the height the
     * break-off climbs back to before the next pass.
     *
     * <p>It is set against the engage bubble rather than picked: the roll-in height and the run-in
     * length are two sides of the same triangle, and the angle between them is the dive. At 160
     * over a target 384 blocks away that angle is 23 degrees — a long, shallow, steady descent the
     * aircraft can hold the gun line down for the whole way. Cutting the height without cutting the
     * bubble flattens the run until there is nothing to dive; cutting the bubble without cutting
     * the height is the near-vertical plunge this constant exists to prevent.
     */
    private static final double ATTACK_ENTRY_AGL = 160.0;
    /** Air kept over the highest ground between here and the target while closing on it. */
    private static final double MIN_INGRESS_CLEARANCE = 80.0;
    /**
     * Clearance required under the <b>planned</b> dive path. Lower than the pull-up floor because
     * the run deliberately ends at that floor: requiring the full margin at the end of the run
     * would refuse every attack, which is exactly what the first version of this check did.
     */
    private static final double DIVE_PATH_CLEARANCE = 24.0;
    private static final double PULLUP_LEAD_TICKS = 14.0;
    private static final float HARD_CLIMB_PITCH_DEG = 30.0F;
    /** The climb a slow aircraft is allowed instead — shallow enough for the wing to hold it. */
    private static final float SOFT_CLIMB_PITCH_DEG = 10.0F;
    /** Airspeed below which the hard climb is refused. See {@link #climbPitch}. */
    private static final double CLIMB_MIN_SPEED = 0.9;
    private static final float MAX_DIVE_PITCH_DEG = 55.0F;
    /**
     * How nose-down each kind of store is delivered. This is the doctrine, not a tuning number: a
     * gun has to be pointed at what it is hitting, so it is flown as a dive; a guided missile only
     * has to have the target inside its seeker cone, so it is launched from a shallow descent; and
     * a free-fall bomb is aimed by <em>where it is released</em>, not by where the aircraft is
     * pointing, so a bombing run is flown level and diving on one would only throw the store long.
     */
    private static final double GUIDED_RUN_PITCH_DEG = 25.0;
    private static final double BOMB_RUN_PITCH_DEG = 8.0;
    /** No delivery is flown lower than this over the target's own ground. */
    private static final double MIN_RUN_AGL = 80.0;
    /** Yaw rate scale in the reversal — gentle, so a heavy hull's momentum can follow the nose. */
    private static final double TURN_YAW_SCALE = 0.3;
    private static final double TURN_ALIGN_DEG = 35.0;
    /**
     * How straight the aircraft has to already be flying at the target before the run may start.
     *
     * <p>The run locks an axis and immediately starts the firing and pull-up clocks, and nothing
     * used to check that the aircraft was <em>on</em> that axis — only that it was near the target.
     * Rolling in from a hold circle means committing from a tangent, up to ninety degrees off, and
     * an aircraft is pointed by hauling the whole hull round at a fraction of a degree per tick
     * inside a run that lasts a second or two. It cannot be done, so every such pass was flown with
     * the gun swinging through the aim point and the gate never satisfied. Twelve degrees is what
     * the airframe can wash out during the run itself.
     */
    private static final double RUN_ALIGN_DEG = 12.0;
    /** Room left between the computed bomb release point and the start of the run that offers it. */
    private static final double BOMB_RELEASE_MARGIN = 24.0;
    /**
     * Pure-pursuit lookahead for the bombing ground track, in blocks.
     *
     * <p>Long, and deliberately so. It is the same primitive the landing approach steers by, but
     * the two want opposite settings: a final approach converges over a hundred-odd blocks and
     * wants a short carrot, while a bombing run has the whole engage bubble to null a few blocks
     * of offset in and has to arrive <b>wings level</b>, because the release window is only a few
     * ticks wide and a hull still rolling out of a correction throws the store sideways. A short
     * carrot here corrects hard, late, and is still correcting at the release point.
     */
    private static final double BOMB_TRACK_LOOKAHEAD = 160.0;
    /** Inside this, the bombing track stops correcting and just holds the axis. */
    private static final double BOMB_TRACK_MIN_PURSUIT = 32.0;
    /**
     * How nose-down the run-in itself may get while it comes down onto the roll-in height.
     *
     * <p>The approach used to descend at the ordinary cruise bound, which is a gentle angle chosen
     * for transiting rather than for arriving. From the cruise band that is a descent long enough
     * that the aircraft was still stepping down as it reached the target, and the whole nose-over
     * then had to happen at the merge — the nose going down late is the same thing as the gun
     * settling late. Letting the approach come down steeply puts the aircraft on its roll-in height
     * well before it gets there, so the dive it flies is the one the geometry asked for and it
     * starts at the beginning of the run.
     */
    private static final float INGRESS_DIVE_PITCH_DEG = 35.0F;
    /**
     * Airspeed at which the dive brake comes off again, in blocks per tick.
     *
     * <p>Braking a diving aircraft is close to free — see {@link PlaneController#airbrake} — but
     * only down to SBW's pitch-authority knee, which sits at 0.44 blocks/tick along the nose.
     * Below that the engine halves the response to the pitch stick, and an aircraft that has traded
     * its authority for time has nothing left to spend the time on.
     */
    private static final double DIVE_BRAKE_MIN_SPEED = 0.6;

    // --- Hold ----------------------------------------------------------------------------------
    /**
     * Hold circle radius as a multiple of the demonstrated turn radius. Well clear of the tightest
     * circle the hull can fly, not merely inside it: an orbit flown at the limit is flown with the
     * stick against its stop, which leaves nothing to correct the drift with and skids the hull
     * through the turn instead of taking it round.
     */
    private static final double HOLD_RADIUS_FACTOR = 2.0;
    /**
     * Floor under that radius. It is the engage bubble's own order of magnitude on purpose: the
     * aircraft rolls in off this circle, and a circle much tighter than the run it feeds presents
     * every tangent at a steep angle to the target, which is the geometry {@link #RUN_ALIGN_DEG}
     * then has to reject.
     */
    private static final double HOLD_RADIUS_MIN = 192.0;

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

    // Latched run-in axis while repositioning onto it (NaN = not repositioning). It has to be
    // latched: derived from the live bearing it would sit behind the aircraft and flip sides on
    // every turn, which is the same chase the landing pattern avoids by remembering its axis.
    private double runInDirX = Double.NaN;
    private double runInDirZ = Double.NaN;

    // Locked straight-line heading of the current attack run (NaN = none).
    private double runDirX = Double.NaN;
    private double runDirZ = Double.NaN;
    private double runStartX;
    private double runStartZ;
    /** Altitude the current run was briefed at, held fixed for its duration (NaN = none). */
    private double runY = Double.NaN;

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
        // A standing radio order, re-read rather than latched: the player may change it mid-sortie
        // and the next run should honour the new one.
        this.weapons.setMode(pilot != null ? pilot.sewv$getPlaneAttackMode() : PlaneAttackMode.AUTO);

        LivingEntity target = resolveCombatTarget();
        Vec3 mark = target == null ? resolveStrikeMark() : null;
        PlaneMode next = chooseMode(pilot, target, mark, now);
        if (next != this.mode) {
            onModeChange(this.mode, next, target);
            this.mode = next;
        }
        if (SewvDiag.planeVerbose()) {
            SewvDiag.planeHeartbeat(now, "mode={} leash={} spd={} agl={} r={} target={}",
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
            case INGRESS -> {
                if (target != null) ingress(target);
                else if (mark != null) ingressMark(mark);
                else hold();
            }
            case ATTACK -> {
                if (target != null) attack(target);
                else if (mark != null) attackMark(mark);
                else {
                    this.mode = PlaneMode.HOLD;
                    hold();
                }
            }
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
                                 @Nullable Vec3 mark, long now) {
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

        if (mark != null) {
            if (tether == PlaneLeash.State.RECALL) {
                if (this.mode == PlaneMode.ATTACK) return PlaneMode.ATTACK;
                return PlaneMode.RTB;
            }
            return combatModeMark(mark, now);
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
        // The store has to be chosen before the geometry is judged: what counts as a safe run
        // depends entirely on what is being delivered, and a bomb run is not a dive.
        this.weapons.ensureSelected(target, dist);
        if (dist > engageRange(target)) {
            this.runInDirX = Double.NaN;
            this.runInDirZ = Double.NaN;
            return PlaneMode.INGRESS;
        }

        // Inside the bubble but not lined up on it: fly the run-in leg first. Committing from here
        // is what produced the pass that never fires — see RUN_ALIGN_DEG.
        Vec3 dir = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : this.kinematics.forwardFlat();
        if (!establishedForRun(target, dir)) {
            SewvDiag.planeThrottled(now, "not established dist={} hdgErr={} — flying the run-in",
                    String.format("%.0f", dist),
                    String.format("%.0f",
                            PlaneNav.headingErrorDeg(this.kinematics.forwardFlat(), dir)));
            return PlaneMode.INGRESS;
        }

        // Inside the bubble but the run would fly us into the ground: keep working the geometry
        // rather than committing. An attack run that has to be abandoned halfway is a pass wasted
        // and, on a heavy hull, a recovery that may not fit under the terrain.
        if (!runWouldBeSafe(target, dir)) {
            // The single most useful line in this whole file when a plane "just won't attack".
            SewvDiag.planeThrottled(now, "run refused kind={} dist={} y={} floor={} — orbiting",
                    this.weapons.selectedKind(), String.format("%.0f", dist),
                    String.format("%.0f", this.vehicle.getY()),
                    String.format("%.0f", runFloorY(target)));
            return PlaneMode.INGRESS;
        }
        this.runInDirX = Double.NaN;
        this.runInDirZ = Double.NaN;
        return PlaneMode.ATTACK;
    }

    /** Grid-mark combat cycle — same geometry as {@link #combatMode}, static aim. */
    private PlaneMode combatModeMark(Vec3 mark, long now) {
        if (this.mode == PlaneMode.ATTACK || this.mode == PlaneMode.BREAK) return this.mode;

        double dx = mark.x - this.vehicle.getX();
        double dz = mark.z - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        this.weapons.ensureSelectedMark(dist);
        if (dist > engageRange(mark)) {
            this.runInDirX = Double.NaN;
            this.runInDirZ = Double.NaN;
            return PlaneMode.INGRESS;
        }

        Vec3 dir = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : this.kinematics.forwardFlat();
        if (!establishedForRun(mark, dir)) {
            return PlaneMode.INGRESS;
        }
        if (!runWouldBeSafe(mark, dir)) {
            return PlaneMode.INGRESS;
        }
        this.runInDirX = Double.NaN;
        this.runInDirZ = Double.NaN;
        return PlaneMode.ATTACK;
    }

    /**
     * How far out the attack run has to begin — the engage bubble for a weapon that is aimed, and
     * the ballistic release distance plus room to settle for one that is dropped.
     *
     * <p>A bomb is the case that forced this. Its release point is tens of blocks upwind of the
     * target and scales with airspeed: an A-10 at 40 blocks over the deck lets go 52 blocks out at
     * 1.5 blocks/tick and 87 out at 2.5. A run that could only begin inside the fixed 96-block
     * bubble therefore handed the release solution a window a few ticks wide, and at any real jet
     * speed no window at all — the aircraft crossed the release point while still in ingress, where
     * bombs are deliberately never pickled, and by the time the run started the only solutions left
     * were long. That is the reported "it drops after it has flown over".
     */
    private double engageRange(LivingEntity target) {
        return engageRangeAt(target.getY(), runAltitude(target));
    }

    private double engageRange(Vec3 mark) {
        return engageRangeAt(mark.y, runAltitude(mark));
    }

    private double engageRangeAt(double groundY, double runY) {
        double base = SewvConfig.PLANE_ENGAGE_RADIUS.get();
        if (!this.weapons.hasBombSelected()) return base;
        double release = this.weapons.bombReleaseRange(runY - groundY);
        return Math.max(base, release + BOMB_RELEASE_MARGIN);
    }

    /** The run may not be longer than the bubble it started from, or it ends before the release. */
    private double runLengthLimit(LivingEntity target) {
        return Math.max(SewvConfig.PLANE_ATTACK_RUN_LENGTH.get(),
                engageRange(target) + OVERFLY_MARGIN);
    }

    private double runLengthLimit(Vec3 mark) {
        return Math.max(SewvConfig.PLANE_ATTACK_RUN_LENGTH.get(),
                engageRange(mark) + OVERFLY_MARGIN);
    }

    /**
     * Is the aircraft already pointing down a line through the target, or merely near it? Latches
     * a run-in axis on the first miss so {@link #ingress} has a stable point to fly the leg to.
     *
     * <p>What is latched is the aircraft's own <b>heading</b>, because the reposition is flown
     * outbound: an initial point derived from the bearing to the target sits <em>behind</em> an
     * aircraft that is already inside the bubble, so steering at it is a reversal onto a track
     * pointing away from the target and the aircraft arrives there facing the wrong way.
     */
    private boolean establishedForRun(LivingEntity target, Vec3 bearing) {
        return establishedForRun(bearing);
    }

    private boolean establishedForRun(Vec3 mark, Vec3 bearing) {
        return establishedForRun(bearing);
    }

    private boolean establishedForRun(Vec3 bearing) {
        if (PlaneNav.headingErrorDeg(this.kinematics.forwardFlat(), bearing) <= RUN_ALIGN_DEG) {
            return true;
        }
        if (Double.isNaN(this.runInDirX)) {
            Vec3 heading = this.kinematics.forwardFlat();
            this.runInDirX = heading.x;
            this.runInDirZ = heading.z;
        }
        return false;
    }

    private void onModeChange(PlaneMode from, PlaneMode to, @Nullable LivingEntity target) {
        if (to == PlaneMode.ATTACK && target != null) {
            startRun(target);
        }
        // Mark runs start lazily in attackMark (same as a late startRun).
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

    /**
     * Standing radio grid mark ({@link FireMission}), or null. Live targets outrank it — see
     * {@link #resolveCombatTarget}.
     */
    @Nullable
    private Vec3 resolveStrikeMark() {
        if (!(this.unit instanceof IMortarCrew crew)) return null;
        FireMission mission = crew.sewv$getFireMission();
        if (mission == null) return null;
        if (mission.isExpired(this.unit.level().getGameTime())) {
            crew.sewv$setFireMission(null);
            return null;
        }
        return Vec3.atCenterOf(mission.pos());
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
        Vec3 clear = this.terrain.clearBearing(this.sensor, ahead, this.kinematics.speed());
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
        Vec3 clear = this.terrain.clearBearing(this.sensor, steer, this.kinematics.speed());
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
        flyToward(destX, destZ, desiredY, PlaneController.MAX_CRUISE_PITCH_DEG);
    }

    /** As above, with a descent allowed steeper than the cruise bound — see the ingress dive. */
    private void flyToward(double destX, double destZ, double desiredY, float maxNoseDownDeg) {
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

        Vec3 travelDir = this.terrain.clearBearing(this.sensor, desiredBearing,
                this.kinematics.speed());
        if (travelDir == null) {
            avoidBlocked(desiredBearing, desiredY);
            return;
        }
        this.control.steerYaw(travelDir);
        this.control.holdAltitude(held, maxNoseDownDeg);
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
     * Closing on the target: come down to the height this store is delivered from, wings mostly
     * level, no aiming yet.
     *
     * <p>The descent is the point, and getting it <b>finished early</b> is the point of the
     * descent. Ingress used to hold the full cruise band right up to the engagement, which leaves
     * the aircraft directly above its target with nothing to do but a near-vertical dive it cannot
     * pull out of — so it flew over instead, again and again. Coming down converts that into an
     * ordinary shallow run-in; coming down at {@link #INGRESS_DIVE_PITCH_DEG} rather than the
     * cruise bound is what gets it level on the roll-in height before the run rather than during
     * it. The terrain floor below keeps the descent honest over anything in the way.
     */
    private void ingress(@Nullable LivingEntity target) {
        if (target == null) {
            hold();
            return;
        }
        double dist = Math.hypot(target.getX() - this.vehicle.getX(),
                target.getZ() - this.vehicle.getZ());
        this.weapons.ensureSelected(target, dist);
        // The height the run will be flown at, not a separate approach height. They were computed
        // by two different expressions and the approach one was the higher, so every attack began
        // with an unbriefed descent — see runAltitude.
        double approachY = runAltitude(target);
        if (!Double.isNaN(this.runInDirX)) {
            // Repositioning: hold the latched heading outbound until clear of the bubble, and the
            // branch below then flies the long straight leg back in. There is no way to turn onto
            // an aligned run from inside the bubble — that is the whole problem, the room to
            // straighten out is the distance itself. Circling here instead, which is what this did
            // before, aligns nothing at all: an orbit's tangent is never radial, so the aircraft
            // rolled in off the circle and committed to a run it was ninety degrees across.
            double out = engageRange(target) * 2.0;
            flyToward(this.vehicle.getX() + this.runInDirX * out,
                    this.vehicle.getZ() + this.runInDirZ * out, approachY,
                    INGRESS_DIVE_PITCH_DEG);
        } else if (dist < engageRange(target)) {
            // Inside the bubble, on the axis, and still not attacking — the dive must have been
            // refused. Circling keeps the target in reach while the geometry changes.
            holdAbout(new Vec3(target.getX(), 0.0, target.getZ()), approachY);
        } else {
            flyToward(target.getX(), target.getZ(), approachY, INGRESS_DIVE_PITCH_DEG);
        }
        // A shot that lines up on the way in is still a shot, and it is gated exactly as tightly
        // as one on the run — this is not the old "spray while transiting" path.
        this.weapons.arm();
        // Bombs are never pickled off the transit: a release is a predicted-impact decision made on
        // a stable run-in, and the ordinary fire gate knows nothing about where one would land.
        if (!this.weapons.hasBombSelected()) {
            this.weapons.fire(target, this.weapons.aimPoint(target));
        }
    }

    /** Lock the run axis and pick the weapon for what we are about to attack, and from how far. */
    private void startRun(LivingEntity target) {
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0,
                target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : this.kinematics.forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
        // Briefed once, then flown. A run altitude recomputed every tick is a moving setpoint, and
        // an altitude loop chasing one is never settled — which for a bomb is the difference
        // between a platform and a guess, since the store leaves along the velocity vector the
        // chasing is bending. It is also what {@code runWouldBeSafe} just cleared the whole run
        // length against, so re-deriving it mid-run would be flying a profile nothing checked.
        this.runY = runAltitude(target);
        this.weapons.beginRun(target, toT.length());
        SewvDiag.plane("run start kind={} range={} alt={} dir=({},{})", this.weapons.selectedKind(),
                String.format("%.0f", toT.length()), String.format("%.0f", this.runY),
                String.format("%.2f", dir.x), String.format("%.2f", dir.z));
    }

    private void resetRun() {
        this.runDirX = Double.NaN;
        this.runDirZ = Double.NaN;
        this.runY = Double.NaN;
    }

    /**
     * The attack run: hold the locked line, put the <b>weapon</b> on the intercept, and fire only
     * when the shot would land.
     *
     * <p>The run is <b>long</b> by design, and every one of its exit conditions is a real event
     * rather than a distance budget — passed the target, stick gone, ground coming up, off the end
     * of the bubble. A short line was tried, on the reasoning that it kept a plane near the fight,
     * and it does: it keeps it near the fight missing, because the line is the only place the
     * aircraft has to get its nose onto the target and it is pointed a fraction of a degree at a
     * time.
     *
     * <p>Which delivery is flown comes from what was selected, not from a mode flag here: a gun is
     * dived, a missile is launched from a shallow descent, a bomb is dropped from level flight.
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

        // A completed stick is deliberately NOT an exit. The last bomb goes at the release point,
        // which for a level delivery is a hundred blocks short of the target, so breaking there
        // pulls the aircraft up before it ever reaches what it just bombed — and pulls it up out of
        // the one profile in this class that is flown slow. The overfly costs a couple of seconds
        // and is what {@code passedTarget} is already for.
        if (passedTarget || clearance <= pullupTrigger
                || distFromStart >= runLengthLimit(target)) {
            this.mode = PlaneMode.BREAK;
            resetRun();
            this.control.holdHeading();
            this.control.commandPitch(climbPitch());
            return;
        }

        this.weapons.arm();
        if (this.weapons.hasBombSelected()) {
            bombRun(target);
            return;
        }

        // Put the GUN LINE on the intercept point, not the nose on the target. SBW's weapons sit
        // forward of the hull origin and canted off its axis, so an attitude worked out from hull
        // geometry aims every shot slightly wrong, in the same direction, forever. Firing is gated
        // against the same measured line, so the shot goes when it would actually land.
        Vec3 aim = this.weapons.aimPoint(target);
        this.control.trackGunLine(this.weapons.gunLine(), this.weapons.toAim(aim),
                -PlaneController.MAX_CRUISE_PITCH_DEG, (float) deliveryPitchLimit());

        // Brake into the dive. The run is the only stretch where the aircraft has to hold a line
        // rather than get somewhere, so it is the one place airspeed is worth less than time: the
        // gun converges at a rate set by the airframe, and slowing the closure simply gives that
        // rate more ticks to work in before the pull-up. SBW's brake is two multipliers on the
        // hull's own datapack numbers, so this asks for "slower than whatever this aircraft is",
        // never for a speed — a jet and a piston bomber both give up the same proportion.
        this.control.airbrake(this.kinematics.speed() > DIVE_BRAKE_MIN_SPEED);

        this.weapons.fire(target, aim);
    }

    /** Ingress toward a radio grid mark. Prefers bombs; guns have nothing to lock. */
    private void ingressMark(Vec3 mark) {
        double dx = mark.x - this.vehicle.getX();
        double dz = mark.z - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        this.weapons.ensureSelectedMark(dist);
        double approachY = runAltitude(mark);
        if (!Double.isNaN(this.runInDirX) && dist < engageRange(mark)) {
            double out = engageRange(mark) * 2.0;
            flyToward(this.vehicle.getX() + this.runInDirX * out,
                    this.vehicle.getZ() + this.runInDirZ * out, approachY,
                    INGRESS_DIVE_PITCH_DEG);
        } else if (dist < engageRange(mark)) {
            holdAbout(new Vec3(mark.x, 0.0, mark.z), approachY);
        } else {
            flyToward(mark.x, mark.z, approachY, INGRESS_DIVE_PITCH_DEG);
        }
    }

    private void startRunMark(Vec3 mark) {
        Vec3 toT = new Vec3(mark.x - this.vehicle.getX(), 0, mark.z - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : this.kinematics.forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
        this.runY = runAltitude(mark);
        this.weapons.beginRunMark(toT.length());
    }

    private void attackMark(Vec3 mark) {
        if (Double.isNaN(this.runDirX)) startRunMark(mark);

        this.control.throttleUp();

        double gx = this.vehicle.getX();
        double gz = this.vehicle.getZ();
        double distFromStart = Math.hypot(gx - this.runStartX, gz - this.runStartZ);

        double planeAlong = (gx - this.runStartX) * this.runDirX
                + (gz - this.runStartZ) * this.runDirZ;
        double targetAlong = (mark.x - this.runStartX) * this.runDirX
                + (mark.z - this.runStartZ) * this.runDirZ;
        boolean passedTarget = planeAlong > targetAlong + OVERFLY_MARGIN;

        int groundRef = Math.max(this.kinematics.surfaceBelow(),
                PlaneTerrain.ridgeToward(this.vehicle, gx + this.runDirX * 24.0,
                        gz + this.runDirZ * 24.0, TERRAIN_LOOKAHEAD));
        double clearance = this.vehicle.getY() - groundRef;
        double pullupTrigger = MIN_ATTACK_CLEARANCE + this.kinematics.sinkRate() * PULLUP_LEAD_TICKS;

        if (passedTarget || clearance <= pullupTrigger
                || distFromStart >= runLengthLimit(mark)) {
            this.mode = PlaneMode.BREAK;
            resetRun();
            this.control.holdHeading();
            this.control.commandPitch(climbPitch());
            return;
        }

        this.weapons.arm();
        // Grid marks are area fires — bombs only. Guns / guided need a living lock.
        if (this.weapons.hasBombSelected()) {
            bombRunMark(mark);
            return;
        }
        // No bomb aboard: hold the line and overfly rather than invent a gun lock on dirt.
        this.control.steerYaw(new Vec3(this.runDirX, 0.0, this.runDirZ));
        this.control.holdAltitude(runAltitude(mark));
    }

    private void bombRunMark(Vec3 mark) {
        Vec3 axis = new Vec3(this.runDirX, 0.0, this.runDirZ);
        Vec3 aim = this.weapons.bombGroundAim(mark);
        double along = PlaneNav.alongTrack(aim.x - this.vehicle.getX(),
                aim.z - this.vehicle.getZ(), axis);

        if (along > BOMB_TRACK_MIN_PURSUIT) {
            Vec3 carrot = PlaneNav.approachCarrot(aim.x, aim.z, axis, along, BOMB_TRACK_LOOKAHEAD);
            this.control.steerYaw(new Vec3(carrot.x - this.vehicle.getX(), 0.0,
                    carrot.z - this.vehicle.getZ()));
        } else {
            this.control.steerYaw(axis);
        }
        double runY = runAltitude(mark);
        this.control.holdAltitude(runY);
        this.control.airbrake(!this.weapons.stickComplete()
                && this.kinematics.speed() > DIVE_BRAKE_MIN_SPEED);
        this.weapons.releaseBombIfOnTarget(aim, this.kinematics.forwardFlat(), runY);
    }

    /**
     * Level bombing pass. There is no aiming here in the pointing sense — a free-fall store is
     * aimed by <em>when</em> it leaves the aircraft, which {@code releaseBombIfOnTarget} solves
     * from the hull's own velocity, so the run's whole job is to present a straight, level, stable
     * platform over the target's track and let the release decide.
     *
     * <p>Steering follows the <b>locked run axis as a line</b>, not as a heading, and not as the
     * live bearing to the target. All three are different and only the first is right: a heading
     * hold flies parallel to the line it should be on, and chasing the bearing swings the aircraft
     * violently as it passes overhead, when a carpet is by definition straight.
     */
    private void bombRun(LivingEntity target) {
        Vec3 axis = new Vec3(this.runDirX, 0.0, this.runDirZ);
        // The point the ground track has to pass over is where the bomb has to ARRIVE, not where
        // the target is now — the same led point the release solves against, so the steering and
        // the gate can never disagree about what is being bombed.
        Vec3 aim = this.weapons.bombGroundAim(target);
        double along = PlaneNav.alongTrack(aim.x - this.vehicle.getX(),
                aim.z - this.vehicle.getZ(), axis);

        // Cross-track, not heading. The axis was laid THROUGH the target at run start, but matching
        // only its direction leaves the aircraft on a line parallel to that one, offset by however
        // far it drifted while it turned — and for a store aimed by where it is released, lateral
        // offset is the entire miss. It cannot be timed out of: a predicted impact that passes a
        // few blocks to the side never enters the release window on any tick, so the aircraft flies
        // the whole run with the bay shut. Same pure pursuit the landing axis is flown by.
        //
        // Close in, the pursuit is switched off and the wings are simply held level. Steering at a
        // point a few blocks ahead turns a fraction of a block of residual offset into a hard
        // correction — the aircraft would jink over the target, which is the worst possible moment
        // for it — and past the aim point the carrot sits BEHIND the aircraft and the correction
        // becomes a reversal. There is nothing left to align by then anyway: the offset is closed
        // out on the long leg or not at all.
        if (along > BOMB_TRACK_MIN_PURSUIT) {
            Vec3 carrot = PlaneNav.approachCarrot(aim.x, aim.z, axis, along, BOMB_TRACK_LOOKAHEAD);
            this.control.steerYaw(new Vec3(carrot.x - this.vehicle.getX(), 0.0,
                    carrot.z - this.vehicle.getZ()));
        } else {
            this.control.steerYaw(axis);
        }
        double runY = runAltitude(target);
        this.control.holdAltitude(runY);
        // Braked like the dive, and for a sharper reason than buying tracking time: a bomb's
        // downrange travel and its whole time of fall scale with the speed it is let go at, and
        // everything that can go wrong between release and impact — the target driving out from
        // under it, the release solution going stale — grows with that time. Slower is nearer, and
        // nearer is more accurate.
        //
        // The brake comes off the moment the stick is away, so the aircraft spends the overfly
        // building the speed the break-off climb has to be paid for with. Braking through it and
        // then hauling up is how a bombing pass ended in a stall.
        this.control.airbrake(!this.weapons.stickComplete()
                && this.kinematics.speed() > DIVE_BRAKE_MIN_SPEED);
        this.weapons.releaseBombIfOnTarget(target, this.kinematics.forwardFlat(), runY);
    }

    /**
     * How hard the aircraft may pull up, given what it is doing it on.
     *
     * <p>A fixed-wing hull in SBW makes its lift out of forward airspeed, so a climb is bought
     * with speed and there has to be some to spend. The dive profile always has plenty — it has
     * just traded height for it — but the bombing run is flown <b>level and on the brake</b>, and
     * settles near {@link #DIVE_BRAKE_MIN_SPEED} by design. Commanding the full climb from there
     * asks for a pitch attitude the wing cannot hold: the aircraft rotates, stops flying and comes
     * down, which is exactly the "pitches up and then falls out of the sky" a bombing pass ended
     * with. Below the threshold it climbs gently instead and lets the engine build the speed back.
     */
    private float climbPitch() {
        return this.kinematics.speed() >= CLIMB_MIN_SPEED
                ? -HARD_CLIMB_PITCH_DEG : -SOFT_CLIMB_PITCH_DEG;
    }

    /** How nose-down the current store is delivered — see the run-pitch constants. */
    private double deliveryPitchLimit() {
        PlaneWeapons.Kind kind = this.weapons.selectedKind();
        if (kind == null) return MAX_DIVE_PITCH_DEG;
        return switch (kind) {
            case BOMB -> BOMB_RUN_PITCH_DEG;
            case GUIDED -> GUIDED_RUN_PITCH_DEG;
            default -> MAX_DIVE_PITCH_DEG;
        };
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
        Vec3 clear = this.terrain.clearBearing(this.sensor, wanted, this.kinematics.speed());
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
            this.control.commandPitch(climbPitch());
        } else {
            this.control.holdAltitude(recoverY);
        }

        double yawErr = PlaneNav.headingErrorDeg(this.kinematics.forwardFlat(), dir);
        if (yawErr < TURN_ALIGN_DEG && recovered) {
            this.mode = PlaneMode.INGRESS;
        }
    }

    /** Would the run along {@code dir} clear the ground all the way in? */
    private boolean runWouldBeSafe(LivingEntity target, Vec3 dir) {
        return runWouldBeSafeAt(target.getX(), target.getZ(), runFloorY(target), dir);
    }

    private boolean runWouldBeSafe(Vec3 mark, Vec3 dir) {
        return runWouldBeSafeAt(mark.x, mark.z, runFloorY(mark), dir);
    }

    private boolean runWouldBeSafeAt(double tx, double tz, double floorY, Vec3 dir) {
        double runLength = Math.min(SewvConfig.PLANE_ATTACK_RUN_LENGTH.get(),
                Math.hypot(tx - this.vehicle.getX(), tz - this.vehicle.getZ()) + OVERFLY_MARGIN);
        return PlaneTerrain.diveSafe(this.unit.level(), this.vehicle.getX(), this.vehicle.getY(),
                this.vehicle.getZ(), dir, runLength, floorY, DIVE_PATH_CLEARANCE);
    }

    /**
     * The lowest altitude this run is planned down to, which depends on what is being delivered.
     * A gun run ends at the pull-up floor over the target's own terrain (or at the target's own
     * altitude, if it is airborne — there is no reason to stay above a helicopter you are shooting
     * at). A bomb or missile run never descends past its release height at all, so judging it
     * against a strafing floor would refuse perfectly safe passes.
     */
    private double runFloorY(LivingEntity target) {
        if (this.weapons.levelDelivery()) return runAltitude(target);
        return Math.max(groundAt(target.getX(), target.getZ()) + MIN_ATTACK_CLEARANCE, target.getY());
    }

    private double runFloorY(Vec3 mark) {
        if (this.weapons.levelDelivery()) return runAltitude(mark);
        return Math.max(groundAt(mark.x, mark.z) + MIN_ATTACK_CLEARANCE, mark.y);
    }

    /**
     * Height the run is flown at, chosen so that the delivery angle the selected store wants is
     * actually achievable at the engagement range: {@code tan(deliveryAngle) x engageRadius}.
     *
     * <p>Deriving it rather than fixing it is what stops the two failures that bracket this. Held
     * at cruise, the aircraft arrives directly above its target with nothing available but a
     * near-vertical plunge it cannot recover from — so it flew over instead, again and again. Fixed
     * low, a gun run has no height to trade for the dive. The floor keeps every profile clear of
     * the ground, and the ceiling is the roll-in height a strafing pass wants.
     */
    private double runAltitude(LivingEntity target) {
        return runAltitudeAt(target.getX(), target.getY(), target.getZ());
    }

    private double runAltitude(Vec3 mark) {
        return runAltitudeAt(mark.x, mark.y, mark.z);
    }

    private double runAltitudeAt(double tx, double ty, double tz) {
        if (!Double.isNaN(this.runY)) return this.runY;
        double geometric = Math.tan(Math.toRadians(deliveryPitchLimit()))
                * SewvConfig.PLANE_ENGAGE_RADIUS.get();
        double agl = Mth.clamp(geometric, MIN_RUN_AGL, ATTACK_ENTRY_AGL);
        double planned = Math.max(groundAt(tx, tz) + agl, ty + MIN_OVER_DEST);
        double enRoute = AirframeSupport.highestGroundToward(this.vehicle, tx, tz, TERRAIN_LOOKAHEAD)
                + MIN_INGRESS_CLEARANCE;
        return Math.max(planned, enRoute);
    }

    private int groundAt(LivingEntity target) {
        return groundAt(target.getX(), target.getZ());
    }

    private int groundAt(double x, double z) {
        return this.unit.level().getHeight(Heightmap.Types.WORLD_SURFACE,
                Mth.floor(x), Mth.floor(z));
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
