package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHeliRunPhase;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.List;

/**
 * Autopilot for SuperbWarfare helicopters. Flight model, deliberately simple:
 *
 * <ul>
 * <li><b>Terrain-following cruise.</b> Every leg flies the configured cruise
 *     altitude (clamped 30-50) above the terrain actually below and ahead of the
 *     hull: the heightmap is sampled along the next stretch of the route and the
 *     collective holds the offset over the HIGHEST upcoming ground, so the
 *     aircraft climbs before a ridge and sinks with falling land. An absolute
 *     level anchored at the takeoff origin turned into treetop-skimming (and a
 *     wall of whisker deflections) the moment an order led into rising terrain.
 *     Cliffs and structures taller than the cruise offset remain the whiskers'
 *     job.</li>
 * <li><b>Whisker avoidance.</b> Every lateral leg asks {@link AirTerrainSensor} for the
 *     nearest clear bearing to the one it wants (yaw avoidance); probe reach grows with
 *     current ground speed so momentum can't outrun the lookahead; a fully-blocked
 *     forward cone answers with a climb (vertical avoidance).</li>
 * <li><b>Combat.</b> Pilot ground armament via {@link HeliArmament} (not
 *     {@code selectWeaponForTarget} — that latches rockets over AG missiles).
 *     <em>Guided</em> AG holds cruise + geometry standoff (v1). <em>Unguided</em>
 *     runs a committed INGRESS→ATTACK→BREAK→REPOSITION firing pass — never a
 *     static low hover. Hover mode OFF while aiming/breaking. Whiskers can force
 *     BREAK at any time.</li>
 * <li><b>Orders outrank auto-acquired targets.</b> A PMC pilot under an explicit
 *     movement order (move-to, follow, formation, hold, cease-fire) keeps flying
 *     the order: a retaliation target must not hijack the hull into the combat
 *     profile — with hull-fixed weapons that means flying AT the target, i.e.
 *     the whole aircraft goes freelancing. The fire assist still takes any shot
 *     that happens to line up mid-leg. ATTACK_THAT_TARGET and FREE_FIRE hand the
 *     hull to the fight; autonomous RU/US crews (no order system) always
 *     fight.</li>
 * <li><b>FOLLOW_COMMANDER</b> parks the aircraft over the commander's X/Z at the
 *     cruise altitude above their ground (never closer than a fixed clearance
 *     over their head).</li>
 * <li><b>Landing (CTRL+L)</b> rides a glide slope toward the designated block's
 *     surface (top of its solid column), then inside the capture ring switches
 *     to hover mode + direct velocity command onto the pad (captureTick) and
 *     settles into the sticky LANDED state on ground contact near the pad.</li>
 * </ul>
 *
 * <p>Control plumbing (from SBW's {@code helicopterEngine}): {@code forwardInput}
 * is the collective (climb), {@code downInput} descends, {@code mouseMoveSpeedY}
 * pitches (positive = nose down), {@code mouseMoveSpeedX} yaws (positive = yaw
 * increases). Analog sticks decay ×0.95/tick and are raw mouse-delta scale (tens,
 * not ±1), so every input is re-asserted every tick at realistic magnitudes.
 */
public class DriveHelicopterGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Synced / NBT / overlay phase names — ordinals must stay stable. */
    public enum RunPhase {
        IDLE, INGRESS, ATTACK, BREAK, REPOSITION, RAPPEL
    }

    public static final String TAG_HELI_RUN_PHASE = "sewv:heli_run_phase";
    /** Debug / later-stage request: goal enters {@link RunPhase#RAPPEL} while set. */
    public static final String TAG_HELI_RAPPEL = "sewv:heli_rappel";

    /**
     * True while this hull's pilot is in INGRESS/ATTACK/BREAK/REPOSITION. Written to the
     * hull's persistent data every phase change so mounted-lock goals can read it without
     * holding a goal reference. IDLE / RAPPEL / missing tag = not in a firing run.
     */
    public static boolean inFiringRun(VehicleEntity vehicle) {
        if (vehicle == null) return false;
        String phase = vehicle.getPersistentData().getString(TAG_HELI_RUN_PHASE);
        if (phase == null || phase.isEmpty()) return false;
        try {
            return isFiringRunPhase(RunPhase.valueOf(phase));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Combat racetrack phases only — RAPPEL is committed but not a firing run. */
    public static boolean isFiringRunPhase(RunPhase phase) {
        return phase == RunPhase.INGRESS
                || phase == RunPhase.ATTACK
                || phase == RunPhase.BREAK
                || phase == RunPhase.REPOSITION;
    }

    public static boolean isRappelRequested(VehicleEntity vehicle) {
        return vehicle != null && vehicle.getPersistentData().getBoolean(TAG_HELI_RAPPEL);
    }

    public static void setRappelRequested(VehicleEntity vehicle, boolean on) {
        if (vehicle == null) return;
        if (on) {
            vehicle.getPersistentData().putBoolean(TAG_HELI_RAPPEL, true);
        } else {
            vehicle.getPersistentData().remove(TAG_HELI_RAPPEL);
        }
    }

    private static final double ALT_DEADBAND = 2.5;
    private static final double CRUISE_SPEED = 0.6;

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
    private static final double APPROACH_GAIN = 0.1;
    /** Along-track speed error below this → level pitch (no accelerate/brake). */
    private static final double VEL_ERR_DEADBAND = 0.03;

    // --- Collective (vertical) ---
    private static final double CLIMB_RATE_CAP = 0.22;
    private static final double DESCEND_RATE_CAP = 0.22;

    // --- Cruise altitude (terrain-relative) ---
    // The terrain offset is the config value hard-clamped to this band.
    private static final double MIN_FLIGHT_ALT = 30.0;
    private static final double MAX_FLIGHT_ALT = 50.0;
    // Never fly a leg below destination + this (e.g. stay above the followed player).
    private static final double MIN_OVER_DEST = 12.0;
    // Heightmap sampling for the terrain-following collective: step spacing and
    // how far ahead along the leg the highest ground is looked for. The lookahead
    // outranges the longest whisker probe, so ridge climbs start on the collective
    // before the whiskers ever have to veto the bearing.
    private static final double TERRAIN_LOOKAHEAD = 48.0;

    // --- Whiskers (see AirTerrainSensor for what counts as blocked) ---
    // Probe reach = base + ~1.5s of current travel: a fixed 12-block line was
    // routinely outrun by cruise momentum (the hull can't shed speed in the
    // distance the probe cleared), so the fan looks further ahead the faster
    // the aircraft is actually moving (~34 blocks at default cruise speed).
    private static final double WHISKER_BASE_DISTANCE = 16.0;
    private static final double WHISKER_LOOKAHEAD_TICKS = 30.0;
    // Fully boxed in: pop up this far above the obstacle line and try again. The
    // avoidance floor decays ~1 block/s afterwards so surplus altitude is given
    // back gradually (and re-triggers cleanly if the obstacle is tall).
    private static final double AVOID_CLIMB_STEP = 4.0;
    private static final double AVOID_FLOOR_DECAY = 0.05;

    // --- Combat / firing run ---
    private static final double ENGAGE_DEADBAND = 4.0;
    private static final double BREAK_RANGE = 14.0;
    private static final float MAX_COMBAT_DIVE_DEG = 60.0F;
    private static final float MAX_CLIMB_AIM_DEG = 15.0F;
    private static final double AIM_STICK_PER_DEG = 1.0;
    private static final float MAX_AIM_PITCH_STICK = 30.0F;
    private static final float MAX_AIM_YAW_STICK = 40.0F;
    // Bounded pass: commanded AGL vs pull-up abort floor must stay apart — when they
    // shared one constant (22), ATTACK steered into the abort band and self-terminated
    // on arrival (~1s passes, no time to fire). Gap = RUN_ALTITUDE - PULLUP_FLOOR = 16.
    private static final double RUN_ALTITUDE = 34.0;   // fly the pass at this AGL
    private static final double PULLUP_FLOOR = 18.0;   // abort when clearance <= this (+ sink lead)
    private static final double RUN_LENGTH = 80.0;
    private static final double OVERFLY_MARGIN = 8.0;
    private static final double PULLUP_LEAD_TICKS = 12.0;
    private static final float BREAK_YAW_STICK = 12.0F;   // capped — yaw also rolls
    private static final float BREAK_CLIMB_PITCH_DEG = -28.0F; // nose up
    private static final float BREAK_ALIGN_DEG = 40.0F;
    private static final double REPOSITION_ARRIVE = 10.0;
    // Committed INGRESS…REPOSITION: getTarget() may stay null for seconds (LOS /
    // scan cylinder) while the sticky entity is still alive — a short miss counter
    // was wiping BREAK before REPOSITION. Sticky owns the run until the entity is
    // gone; this grace only covers the entity-unloaded blip after that.
    private static final int RUN_GATE_GRACE_TICKS = 40;

    // --- Rappel ---
    // Terrain-relative AGL — not an offset from current altitude.
    private static final double RAPPEL_HOVER_AGL = 10.0;
    /** Last-resort exit if a rappel never completes (debug left on, etc.). */
    private static final long RAPPEL_TIMEOUT_TICKS = 6000L;
    private static final double RAPPEL_STABLE_XZ = 1.0;
    /**
     * RU/US combat-insert: enemy must be within this horizontal range (engagement-scale,
     * not the old 12–48 knife band that fought the firing-run gate).
     */
    private static final double RAPPEL_INSERT_RADIUS = 64.0;
    /** Same cap as {@link DriveVehicleGoal}'s IFV dismount — one or two AT gunners per insert. */
    private static final int MAX_AT_GUNNERS = 2;
    /** Ticks holding an in-range enemy before dropping — not first-contact insta-rappel. */
    private static final int RAPPEL_ENGAGE_DEBOUNCE_TICKS = 40;
    /** After any rappel ends, don't autonomous-retrigger while still in the same scrap. */
    private static final int RAPPEL_AUTONOMOUS_COOLDOWN_TICKS = 200;

    // --- Arrival ---
    private static final double ARRIVE_RADIUS = 4.0;
    // Landing approach closes at half the transit gain so speed is shed early.
    private static final double LAND_APPROACH_GAIN = 0.05;
    // Approach glide slope: blocks of height above the pad per block of horizontal
    // distance out, clamped between the over-pad clearance and the cruise offset.
    private static final double LAND_GLIDE_RATIO = 0.5;
    // Capture phase (direct velocity command, see captureTick). Speeds sized so a
    // worst-case impact stays under SBW's 0.2 crash gate: |(0.15, -0.12+0.06)| ≈ 0.16.
    private static final double LAND_CAPTURE_RADIUS = 14.0;
    private static final double LAND_CAPTURE_EXIT_RADIUS = 20.0;
    private static final double LAND_DESCENT_RADIUS = 2.5;
    private static final double LAND_SETTLE_RADIUS = 6.5;
    private static final double CAPTURE_MAX_SPEED = 0.15;
    private static final double CAPTURE_GAIN = 0.15;
    private static final double CAPTURE_BLEND = 0.35;
    private static final double CAPTURE_ALT = 9.0;
    private static final double CAPTURE_VY_GAIN = 0.08;
    private static final double CAPTURE_MAX_SINK = 0.12;

    private static final float DECOY_HEALTH_FRACTION = 0.5F;
    private static final float PRESERVE_DECOY_CHANCE = 0.5F;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    private final HullFacts hull = new HullFacts();
    private final AirTerrainSensor sensor;
    private final DecoyEpisode flares = new DecoyEpisode();
    // Held on the airframe so it keeps flying with no player nearby (config-gated).
    private final ChunkTicket chunkTicket = new ChunkTicket();

    private VehicleEntity vehicle;
    private double avoidFloorY = Double.NaN;
    private boolean landingCapture;
    /** Physical seat weapon slot held for this engagement, or -1 if none. */
    private int heldWeaponSlot = -1;
    /** Network id of the target the hold was taken against. */
    private int heldTargetId = Integer.MIN_VALUE;

    private RunPhase runPhase = RunPhase.IDLE;
    private double runDirX = Double.NaN;
    private double runDirZ = Double.NaN;
    private double runStartX;
    private double runStartZ;
    private double repositionX = Double.NaN;
    private double repositionZ = Double.NaN;
    /** Network id of the target the current run was fighting, for gate-grace sticky resolve. */
    private int lastRunTargetId = Integer.MIN_VALUE;
    /** Consecutive null-target ticks while a run is committed. */
    private int runGateMisses;
    /** Consecutive empty-hold ticks while a run is committed (separate from target grace). */
    private int runHoldMisses;
    /** XZ locked on RAPPEL entry — station-keep like landing capture, no sink. */
    private double rappelLockX = Double.NaN;
    private double rappelLockZ = Double.NaN;
    private long rappelStartedAt = Long.MIN_VALUE;
    /** Game time when the hover first sat inside the stable band; MIN = not stable yet. */
    private long rappelStableAt = Long.MIN_VALUE;
    /** Entity ids on each rope (−1 = free); anchors locked at start so a slide finishes committed. */
    private int rappelRopeMinusId = -1;
    private int rappelRopePlusId = -1;
    private double rappelRopeMinusAx = Double.NaN;
    private double rappelRopeMinusAz = Double.NaN;
    private double rappelRopePlusAx = Double.NaN;
    private double rappelRopePlusAz = Double.NaN;
    /** Game time we first held an in-range enemy while carrying cargo; MIN = not engaged. */
    private long rappelEngageSince = Long.MIN_VALUE;
    /** Don't autonomous-rappel again before this game time (set on every exitRappel). */
    private long rappelAutonomousCooldownUntil = Long.MIN_VALUE;
    /** AT launchers handed out this RAPPEL session (mirrors IFV {@code dismountSquad} armed count). */
    private int rappelAtIssued;

    // --- Flight-quality diagnosis (heliFlightDebug) — observe-only ---
    private static final int FLIGHT_LOG_INTERVAL_TICKS = 10;
    private String flightHoverMode = "";
    private String flightBranch = "";
    private long flightLastLogAt = Long.MIN_VALUE;
    private int flightTicksAlign;
    private int flightTicksTrack;
    private int flightTicksWhisker;
    private boolean flightWasArriveHover;
    private boolean flightAvoidFloorWasActive;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this.unit = unit;
        this.sensor = new AirTerrainSensor(unit);
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) flies — same driver/commander model as the ground goal.
        if (v.getFirstPassenger() != this.unit) return false;

        this.hull.attach(v);
        if (!this.hull.isHelicopter()) return false;

        this.vehicle = v;
        this.sensor.attach(v);
        // Run whenever mounted in a helicopter, even with no destination: a helicopter
        // must be actively controlled to hold station, so "idle" means "hover", not "off".
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && this.hull.isHelicopter();
    }

    // The flight model re-asserts analog stick inputs against their ×0.95/tick
    // decay and closes control loops against live velocity; vanilla only ticks
    // running goals every OTHER tick unless this is overridden.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        // A freshly boarded PMC helicopter sitting on the ground stays parked
        // (sticky LANDED) until an explicit takeoff order — without this, mounting
        // a parked hull auto-launched it to cruise altitude, making the takeoff
        // key ceremonial. ONLY player-owned crews park: RU/US crews take no player
        // flight orders and lift off immediately instead (see the normalization in
        // tick()). Spawned PMC crews are unaffected: TankSpawner issues TAKEOFF
        // before their first AI tick, so their command is never NONE here.
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
            AirframeSupport.releaseInputs(this.vehicle);
            // releaseInputs leaves the decoy latch alone (crash-spin flares must
            // survive its per-tick calls) — but a crew leaving the seat lets go.
            AirframeSupport.clearDecoy(this.vehicle);
            setRappelRequested(this.vehicle, false);
            // Hand the chunk back before we drop the vehicle the ticket is keyed to.
            this.chunkTicket.release(this.vehicle);
        }
        this.vehicle = null;
        this.avoidFloorY = Double.NaN;
        this.landingCapture = false;
        clearWeaponHold();
        clearRun();
        clearFlightDiag();
        this.allyAssist.clear();
        this.sensor.clear();
    }

    @Override
    public void tick() {
        // Independent of flight state: hold the airframe's chunk loaded (if enabled)
        // whether it is cruising, fighting, spiraling in, or parked.
        AirframeSupport.updateChunkLoading(this.chunkTicket, this.vehicle, SewvConfig.HELI_CHUNK_LOADING.get());

        // Before the crash guard on purpose: a burning airframe spiraling in keeps
        // popping flares all the way down.
        AirframeSupport.updateDecoy(this.vehicle, this.unit, this.flares,
                DECOY_HEALTH_FRACTION, PRESERVE_DECOY_CHANCE);

        // Sub-10% health: the engine flies it into the ground on its own. Let go.
        float max = this.vehicle.getMaxHealth();
        if (max > 0.0F && this.vehicle.getHealth() < max * CRASH_HEALTH_FRACTION) {
            AirframeSupport.releaseInputs(this.vehicle);
            return;
        }
        // No power to the rotor — inputs do nothing anyway; don't pretend to fly.
        if (this.vehicle.getEnergy() <= 0) {
            AirframeSupport.releaseInputs(this.vehicle);
            return;
        }

        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;

        // Hostile RU/US crews take no player flight orders and never idle parked:
        // any grounded resting state (spawn edge cases, world reload, a survived
        // crash-spin) resolves to an immediate takeoff.
        if (pilot != null && !(this.unit instanceof PmcUnitEntity)
                && this.vehicle.onGround()
                && (command == IHelicopterPilot.HELI_CMD_NONE
                    || command == IHelicopterPilot.HELI_CMD_LANDED)) {
            command = IHelicopterPilot.HELI_CMD_TAKEOFF;
            pilot.sewv$setHeliCommand(command);
        }

        if (command != IHelicopterPilot.HELI_CMD_LANDING) {
            this.landingCapture = false;
        }

        // LANDED is sticky: stay shut down on the ground — no hover, no order-driven
        // flying — until the player issues a new takeoff (L) or landing (CTRL+L).
        if (command == IHelicopterPilot.HELI_CMD_LANDED) {
            AirframeSupport.releaseInputs(this.vehicle);
            this.vehicle.setHoverMode(false);
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

        // TAKEOFF: climb straight up to the terrain-relative cruise level over the
        // takeoff column, then clear the order and fall through to normal duty.
        // The heightmap under a vertically climbing hull is stable, so the climb
        // target doesn't chase its own altitude the way a getY() offset would.
        if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
            double climbTo = cruiseAltitudeHere();
            if (this.vehicle.getY() >= climbTo - ALT_DEADBAND) {
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
            } else {
                climbVertically(climbTo);
                return;
            }
        }

        // Committed rope slides finish even if RAPPEL tears down mid-descent.
        rappelAdvanceDescents();

        // RU/US combat-insert: no command-tier arrive/deploy for helis exists yet
        // (CommandEligibility is ground-only). Local doctrine until that is scoped.
        maybeAutonomousRappel();

        // RAPPEL sequence: hover → settle → descend → last trooper down → teardown
        // (flag clear → phase IDLE → fall through to flight). Debug toggle + TDT
        // still force-enter / force-exit via the request flag.
        if (isRappelRequested(this.vehicle) || this.runPhase == RunPhase.RAPPEL) {
            if (rappelTick()) {
                return; // still holding the hover
            }
            // Teardown finished this tick — fall through to normal flight/combat.
        }

        // Combat: firing-run SM or guided standoff. A committed run sticks to its
        // last living target even when getTarget() flickers null (scan/LOS) — only
        // a dead/unloaded sticky, lasting hold-empty, order-pin, or an IDLE-state
        // guided pick abandons to IDLE. After abandon, hold cruise here so a
        // collapsing run cannot fall through into a low destination and sink.
        LivingEntity combatTarget = this.unit.getTarget();
        boolean pinned = flightPinnedByOrder();

        if (isFiringRunPhase(this.runPhase)) {
            if (pinned) {
                abandonRun("order-pin");
                holdHover(cruiseAltitudeHere());
                return;
            }
            LivingEntity runTarget = combatTarget != null ? combatTarget : stickyRunTarget();
            if (runTarget != null) {
                if (combatTarget != null) {
                    this.lastRunTargetId = combatTarget.getId();
                }
                this.runGateMisses = 0;
                combatTick(runTarget);
                return;
            }
            // Sticky id points at a corpse → hand off to another in-range enemy and
            // CONTINUE the run when possible; only abandon when the fight is truly over.
            if (stickyTargetDead()) {
                if (tryHandoffFromDeadTarget()) {
                    return;
                }
                abandonRun("target-dead");
                holdHover(cruiseAltitudeHere());
                return;
            }
            this.runGateMisses++;
            if (this.runGateMisses < RUN_GATE_GRACE_TICKS) {
                holdHover(cruiseAltitudeHere());
                return;
            }
            abandonRun("target-null");
            holdHover(cruiseAltitudeHere());
            return;
        }

        if (combatTarget != null && !pinned) {
            this.lastRunTargetId = combatTarget.getId();
            this.runGateMisses = 0;
            combatTick(combatTarget);
            return;
        }

        // Order-driven movement (move-to, follow, formation, ally assist) or idle hover.
        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);

        // A pinned flight path doesn't ground the guns: if the nose happens to
        // bear on the live target mid-leg, take the shot (canShoot still gates
        // ammo, CEASE_FIRE, LOS and smoke).
        if (combatTarget != null) {
            logAiFire(combatTarget, SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }

        if (dest == null) {
            noteHoverMode("IDLE_HOVER");
            holdHover(cruiseAltitudeHere());
            return;
        }
        double dx = dest.getX() + 0.5 - this.vehicle.getX();
        double dz = dest.getZ() + 0.5 - this.vehicle.getZ();
        if (dx * dx + dz * dz <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            // Arrived — hold overhead: cruise level above the ground HERE, never
            // closer than the fixed clearance over the destination (a followed
            // commander's head included).
            noteHoverMode("ARRIVE_HOVER");
            holdHover(Math.max(cruiseAltitudeHere(), dest.getY() + MIN_OVER_DEST));
        } else {
            if (this.flightWasArriveHover) {
                noteArriveThrash();
            }
            double px = dest.getX() + 0.5;
            double pz = dest.getZ() + 0.5;
            flyToward(px, pz,
                    Math.max(cruiseAltitudeToward(px, pz), dest.getY() + MIN_OVER_DEST),
                    "transit");
        }
    }

    // True when the pilot's current SEM order explicitly owns the flight path.
    // HOLD_POSITION/CEASE_FIRE pin too: a crew ordered to hold parks and watches,
    // exactly like the ground goal (which doesn't maneuver at all without a
    // destination) — it doesn't get dragged across the map by a retaliation
    // target it happened to acquire.
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

    // Combat: pick/hold a ground-usable pilot weapon, then guided standoff or firing run.
    private void combatTick(LivingEntity target) {
        updateWeaponHold(target);
        if (this.heldWeaponSlot < 0) {
            // Empty armament → IDLE once settled; mid-run, grace the hold hiccup so
            // BREAK/REPOSITION can finish instead of collapsing on a one-tick miss.
            // Own counter: must not share runGateMisses (live-target path zeros that).
            if (isFiringRunPhase(this.runPhase) && this.runHoldMisses < RUN_GATE_GRACE_TICKS) {
                this.runHoldMisses++;
                runStateMachine(target);
                return;
            }
            abandonRun("hold-empty");
            holdCruiseNear(target);
            return;
        }
        this.runHoldMisses = 0;
        if (heldWeaponGuided()) {
            // Guided standoff only from IDLE. Mid-run a re-pick to AG missiles
            // (rockets dry → DriverMissile) used to force IDLE and skip BREAK —
            // finish the racetrack on the held slot instead.
            if (this.runPhase == RunPhase.IDLE) {
                guidedCombatTick(target);
                return;
            }
            this.runGateMisses = 0;
            runStateMachine(target);
            return;
        }
        this.runGateMisses = 0;
        runStateMachine(target);
    }

    /** Last engagement target still alive in-world, or null. Used only for run-gate grace. */
    @Nullable
    private LivingEntity stickyRunTarget() {
        if (this.lastRunTargetId == Integer.MIN_VALUE || this.vehicle == null) return null;
        if (!(this.vehicle.level().getEntity(this.lastRunTargetId) instanceof LivingEntity living)) {
            return null;
        }
        return living.isAlive() ? living : null;
    }

    /** Sticky id resolves to an entity that is present but dead — genuine end of fight. */
    private boolean stickyTargetDead() {
        if (this.lastRunTargetId == Integer.MIN_VALUE || this.vehicle == null) return false;
        if (!(this.vehicle.level().getEntity(this.lastRunTargetId) instanceof LivingEntity living)) {
            return false; // unloaded / missing — not a confirmed corpse
        }
        return !living.isAlive();
    }

    /**
     * After the sticky target dies: if another valid enemy is in the scan cylinder
     * (and LOS when required / not mid firing-run), lock it and keep the racetrack.
     * Returns true when the run continued on the new target.
     */
    private boolean tryHandoffFromDeadTarget() {
        LivingEntity next = VehicleTargetScanGoal.findHandoffTarget(this.unit, this.vehicle);
        if (next == null) return false;

        this.unit.setTarget(next);
        // setTarget may be cancelled (friendly / support role) — verify.
        if (this.unit.getTarget() != next) return false;

        this.lastRunTargetId = next.getId();
        this.runGateMisses = 0;
        // Force doctrine re-pick for the new contact (soft→armor must switch slots).
        clearWeaponHold();

        if (SewvConfig.HELI_COMBAT_DEBUG.get()) {
            LOGGER.info("[sewv heli] {}#{} HANDOFF dead→{} phase={} alt={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    next.getId(),
                    this.runPhase,
                    String.format("%.1f", this.vehicle.getY()));
        }
        combatTick(next);
        return true;
    }

    private void runStateMachine(LivingEntity target) {
        if (this.runPhase == RunPhase.IDLE) {
            setRunPhase(RunPhase.INGRESS);
        }
        switch (this.runPhase) {
            case INGRESS -> ingressTick(target);
            case ATTACK -> attackPassTick(target);
            case BREAK -> breakTick(target);
            case REPOSITION -> repositionTick(target);
            default -> holdCruiseNear(target);
        }
    }

    private void holdCruiseNear(LivingEntity target) {
        double holdY = Math.max(cruiseAltitudeToward(target.getX(), target.getZ()),
                target.getY() + MIN_OVER_DEST);
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        if (dx * dx + dz * dz > ARRIVE_RADIUS * ARRIVE_RADIUS) {
            flyToward(target.getX(), target.getZ(), holdY, "hold_cruise");
        } else {
            noteHoverMode("ARRIVE_HOVER");
            holdHover(holdY);
        }
    }

    /**
     * Horizontal standoff so that a nose depression of {@code maxDepressionDeg} points at a
     * target {@code heightAboveTarget} below the hold altitude, floored at {@code minStandoff}.
     * Pure geometry — no world access. When height ≤ 0 (target at/above hold), returns the floor.
     */
    static double guidedStandoffRing(double heightAboveTarget, double maxDepressionDeg, double minStandoff) {
        if (!(minStandoff > 0.0)) minStandoff = 0.0;
        if (!(heightAboveTarget > 0.0)) return minStandoff;
        double tan = Math.tan(Math.toRadians(maxDepressionDeg));
        if (!(tan > 1.0E-6)) return minStandoff;
        return Math.max(minStandoff, heightAboveTarget / tan);
    }

    private void guidedCombatTick(LivingEntity target) {
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        double cruiseY = cruiseAltitudeToward(target.getX(), target.getZ());
        double holdY = Math.max(cruiseY, target.getY() + MIN_OVER_DEST);
        double engage = guidedStandoffRing(
                cruiseY - target.getY(),
                SewvConfig.HELI_MAX_DEPRESSION_DEG.get(),
                SewvConfig.HELI_MIN_STANDOFF.get());

        if (horizDist > engage + ENGAGE_DEADBAND) {
            flyToward(target.getX(), target.getZ(), holdY, "guided_engage");
            return;
        }
        if (horizDist < BREAK_RANGE) {
            BlockPos out = VehicleTargeting.computeStandoffPoint(
                    this.vehicle, target.blockPosition(), engage);
            flyToward(out.getX() + 0.5, out.getZ() + 0.5, holdY, "guided_break");
            return;
        }
        aimAtTarget(target, horizDist, holdY);
    }

    // --- Firing run phases -----------------------------------------------------------------------

    private void ingressTick(LivingEntity target) {
        double cruiseY = Math.max(cruiseAltitudeToward(target.getX(), target.getZ()),
                target.getY() + MIN_OVER_DEST);
        double engage = SewvConfig.HELI_ENGAGE_RADIUS.get();
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        Vec3 toT = horiz > 1.0E-4 ? new Vec3(dx / horiz, 0, dz / horiz) : forwardFlat();
        double probe = WHISKER_BASE_DISTANCE
                + this.vehicle.getDeltaMovement().horizontalDistance() * WHISKER_LOOKAHEAD_TICKS;
        if (this.sensor.chooseClearBearing(toT, Math.min(probe, Math.max(horiz, 4.0))) == null) {
            enterBreak(target);
            return;
        }

        if (horiz <= engage + ENGAGE_DEADBAND
                && this.vehicle.getY() >= cruiseY - ALT_DEADBAND * 2) {
            startAttackRun(target);
            setRunPhase(RunPhase.ATTACK);
            attackPassTick(target);
            return;
        }
        flyToward(target.getX(), target.getZ(), cruiseY, "ingress");
    }

    private void startAttackRun(LivingEntity target) {
        Vec3 toT = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        Vec3 dir = toT.lengthSqr() > 1.0E-6 ? toT.normalize() : forwardFlat();
        this.runDirX = dir.x;
        this.runDirZ = dir.z;
        this.runStartX = this.vehicle.getX();
        this.runStartZ = this.vehicle.getZ();
    }

    private void attackPassTick(LivingEntity target) {
        if (Double.isNaN(this.runDirX)) startAttackRun(target);

        double gx = this.vehicle.getX();
        double gz = this.vehicle.getZ();
        double distFromStart = Math.hypot(gx - this.runStartX, gz - this.runStartZ);

        double planeAlong = (gx - this.runStartX) * this.runDirX + (gz - this.runStartZ) * this.runDirZ;
        double targetAlong = (target.getX() - this.runStartX) * this.runDirX
                + (target.getZ() - this.runStartZ) * this.runDirZ;
        boolean passedTarget = planeAlong > targetAlong + OVERFLY_MARGIN;

        int groundRef = Math.max(surfaceBelow(),
                AirframeSupport.highestGroundToward(
                        this.vehicle, gx + this.runDirX * 24.0, gz + this.runDirZ * 24.0, TERRAIN_LOOKAHEAD));
        double clearance = this.vehicle.getY() - groundRef;
        double descentRate = Math.max(0.0, -this.vehicle.getDeltaMovement().y);
        double pullupTrigger = PULLUP_FLOOR + descentRate * PULLUP_LEAD_TICKS;

        Vec3 runDir = new Vec3(this.runDirX, 0, this.runDirZ);
        double probe = WHISKER_BASE_DISTANCE
                + this.vehicle.getDeltaMovement().horizontalDistance() * WHISKER_LOOKAHEAD_TICKS;
        if (this.sensor.chooseClearBearing(runDir, probe) == null
                || passedTarget || clearance <= pullupTrigger || distFromStart >= RUN_LENGTH
                || heldWeaponDepleted(this.vehicle.getSeatIndex(this.unit))) {
            enterBreak(target);
            return;
        }

        // ATTACK pass: collective holds run altitude; nose is owned by aimNoseOnly so
        // hull-fixed weapons (rockets) sit inside the fire-assist cone. Do NOT call
        // flyToward here — ATTACK needs the nose on the target, not the travel path.
        double runY = Math.max(groundRef + RUN_ALTITUDE, target.getY() + MIN_OVER_DEST);
        applyCollective(withAvoidFloor(runY));
        this.vehicle.setHoverMode(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double horiz = Math.hypot(target.getX() - gx, target.getZ() - gz);
        aimNoseOnly(target, horiz);
        logAiFire(target, SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
    }

    private void enterBreak(LivingEntity target) {
        clearRunAxis();
        setRunPhase(RunPhase.BREAK);
        breakTick(target);
    }

    private void breakTick(LivingEntity target) {
        double cruiseY = Math.max(cruiseAltitudeToward(target.getX(), target.getZ()),
                target.getY() + MIN_OVER_DEST);
        double holdY = withAvoidFloor(cruiseY);

        // Climb hard (collective + nose-up) while yawing off the target — yaw rolls the
        // airframe, so climb must compensate. Aim/fire OFF.
        this.vehicle.setHoverMode(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        applyCollective(holdY);

        Vec3 away = new Vec3(this.vehicle.getX() - target.getX(), 0, this.vehicle.getZ() - target.getZ());
        if (away.lengthSqr() < 1.0E-6) away = forwardFlat().scale(-1);
        else away = away.normalize();
        // Prefer a clear break bearing; else force the away vector.
        double probe = WHISKER_BASE_DISTANCE + 16.0;
        Vec3 clear = this.sensor.chooseClearBearing(away, probe);
        Vec3 breakDir = clear != null ? clear : away;

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, breakDir));
        this.vehicle.setMouseMoveSpeedX(
                (float) Mth.clamp(-YAW_STICK_PER_DEG * yawErrDeg, -BREAK_YAW_STICK, BREAK_YAW_STICK));
        float attitudeErr = BREAK_CLIMB_PITCH_DEG - this.vehicle.getXRot();
        this.vehicle.setMouseMoveSpeedY(
                (float) Mth.clamp(attitudeErr * PITCH_STICK_PER_DEG, -MAX_PITCH_STICK, MAX_PITCH_STICK));

        double horiz = Math.hypot(this.vehicle.getX() - target.getX(), this.vehicle.getZ() - target.getZ());
        boolean high = this.vehicle.getY() >= cruiseY - ALT_DEADBAND;
        boolean clearHeading = clear != null;
        boolean farEnough = horiz >= SewvConfig.HELI_MIN_STANDOFF.get();
        boolean aligned = Math.abs(yawErrDeg) < BREAK_ALIGN_DEG;
        if (high && clearHeading && farEnough && aligned) {
            pickReposition(target);
            setRunPhase(RunPhase.REPOSITION);
        }
    }

    private void pickReposition(LivingEntity target) {
        double engage = SewvConfig.HELI_ENGAGE_RADIUS.get();
        // Entity-id parity for flank side — same idea as ground FLANK_*.
        double side = (this.unit.getId() & 1) == 0 ? 1.0 : -1.0;
        Vec3 toHeli = new Vec3(this.vehicle.getX() - target.getX(), 0, this.vehicle.getZ() - target.getZ());
        if (toHeli.lengthSqr() < 1.0E-6) toHeli = forwardFlat();
        else toHeli = toHeli.normalize();
        // Perpendicular offset at standoff range for the next ingress.
        double px = -toHeli.z * side;
        double pz = toHeli.x * side;
        this.repositionX = target.getX() + px * engage;
        this.repositionZ = target.getZ() + pz * engage;
    }

    private void repositionTick(LivingEntity target) {
        if (Double.isNaN(this.repositionX)) pickReposition(target);
        double holdY = Math.max(cruiseAltitudeToward(this.repositionX, this.repositionZ),
                target.getY() + MIN_OVER_DEST);
        double dx = this.repositionX - this.vehicle.getX();
        double dz = this.repositionZ - this.vehicle.getZ();
        if (dx * dx + dz * dz <= REPOSITION_ARRIVE * REPOSITION_ARRIVE) {
            this.repositionX = Double.NaN;
            this.repositionZ = Double.NaN;
            setRunPhase(RunPhase.INGRESS);
            return;
        }
        // Whisker abort during reposition still climbs via flyToward's avoid floor.
        flyToward(this.repositionX, this.repositionZ, holdY, "reposition");
    }

    private Vec3 forwardFlat() {
        Vector3f f = this.vehicle.getForwardDirection();
        Vec3 v = new Vec3(f.x(), 0, f.z());
        return v.lengthSqr() > 1.0E-6 ? v.normalize() : new Vec3(0, 0, 1);
    }

    private void clearRunAxis() {
        this.runDirX = Double.NaN;
        this.runDirZ = Double.NaN;
    }

    private void clearRun() {
        setRunPhase(RunPhase.IDLE);
        clearRunAxis();
        this.repositionX = Double.NaN;
        this.repositionZ = Double.NaN;
        this.lastRunTargetId = Integer.MIN_VALUE;
        this.runGateMisses = 0;
        this.runHoldMisses = 0;
        this.rappelLockX = Double.NaN;
        this.rappelLockZ = Double.NaN;
        this.rappelStartedAt = Long.MIN_VALUE;
        this.rappelStableAt = Long.MIN_VALUE;
    }

    /**
     * RU/US combat insertion: bring troops to a fight, then drop them near it.
     *
     * <p><b>Fires when all of:</b>
     * <ol>
     *   <li>Eligible cargo aboard (weaponless passengers) — nothing to insert otherwise.</li>
     *   <li>Live target within {@link #RAPPEL_INSERT_RADIUS} (64) — "there is a fight here",
     *       engagement-scale, not a knife-fight ring.</li>
     *   <li>That contact has held for {@link #RAPPEL_ENGAGE_DEBOUNCE_TICKS} — debounce so a
     *       first lock at long range does not insta-hover; the heli has actually arrived.</li>
     *   <li>Hull ≥ half health — healthy enough to survive the hover; below that this goal
     *       is already in the flare/escape band and should not park for a rappel.</li>
     *   <li>Past post-rappel cooldown — stops timeout/abort with cargo still aboard from
     *       immediately re-requesting; a successful drop already clears cargo so won't re-fire.</li>
     * </ol>
     *
     * <p><b>Deliberately does NOT require:</b>
     * <ul>
     *   <li>A min range — overflying the scrap and dropping is fine for insertion.</li>
     *   <li>{@code !isFiringRunPhase} — that veto fought condition (2): a heli near a target
     *       is usually in INGRESS/ATTACK/BREAK, so the old gate almost never fired. Dropping
     *       on arrival or after a pass <em>is</em> the intent; enterRappel exits the run.</li>
     * </ul>
     *
     * <p>Command tier still has no arrive/deploy/LZ for helis; this remains the interim.
     */
    private void maybeAutonomousRappel() {
        if (this.unit instanceof PmcUnitEntity) return;
        if (isRappelRequested(this.vehicle) || this.runPhase == RunPhase.RAPPEL) return;

        long now = this.unit.level().getGameTime();
        if (now < this.rappelAutonomousCooldownUntil) return;

        if (!RappelSupport.hasEligiblePassenger(this.vehicle)) {
            this.rappelEngageSince = Long.MIN_VALUE;
            return;
        }

        float maxHp = this.vehicle.getMaxHealth();
        if (maxHp > 0.0F && this.vehicle.getHealth() < maxHp * DECOY_HEALTH_FRACTION) {
            this.rappelEngageSince = Long.MIN_VALUE; // mid-escape — abandon insert plan
            return;
        }

        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) {
            this.rappelEngageSince = Long.MIN_VALUE; // left the fight / lost contact
            return;
        }

        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq > RAPPEL_INSERT_RADIUS * RAPPEL_INSERT_RADIUS) {
            this.rappelEngageSince = Long.MIN_VALUE; // not in the engagement area yet
            return;
        }

        if (this.rappelEngageSince == Long.MIN_VALUE) {
            this.rappelEngageSince = now;
            return; // start debounce — do not drop on the first tick of contact
        }
        if (now - this.rappelEngageSince < RAPPEL_ENGAGE_DEBOUNCE_TICKS) {
            return;
        }

        this.rappelEngageSince = Long.MIN_VALUE;
        setRappelRequested(this.vehicle, true);
        if (SewvConfig.HELI_COMBAT_DEBUG.get()) {
            LOGGER.info("[sewv heli] {}#{} autonomous rappel (target=#{} dist={} debounce={})",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    target.getId(),
                    String.format("%.0f", Math.sqrt(distSq)),
                    RAPPEL_ENGAGE_DEBOUNCE_TICKS);
        }
    }

    /**
     * One RAPPEL sequence tick. {@code true} = keep holding hover (caller returns);
     * {@code false} = teardown done, resume normal flight.
     */
    private boolean rappelTick() {
        boolean requested = isRappelRequested(this.vehicle);

        // Debug force-exit (or external clear) while still in phase — tear down now.
        // Mid-rope slides keep advancing via rappelAdvanceDescents above.
        if (!requested && this.runPhase == RunPhase.RAPPEL) {
            exitRappel("debug-off");
            return false;
        }

        if (requested && this.runPhase != RunPhase.RAPPEL) {
            enterRappel();
        }
        if (this.runPhase != RunPhase.RAPPEL) {
            return false;
        }

        long now = this.unit.level().getGameTime();
        if (now - this.rappelStartedAt >= RAPPEL_TIMEOUT_TICKS) {
            exitRappel("timeout");
            return false;
        }

        rappelStationHover();

        if (!rappelHoverStable()) {
            this.rappelStableAt = Long.MIN_VALUE;
            return true;
        }
        if (this.rappelStableAt == Long.MIN_VALUE) {
            this.rappelStableAt = now;
            if (SewvConfig.HELI_COMBAT_DEBUG.get()) {
                LOGGER.info("[sewv heli] {}#{} rappel settle start ({} ticks)",
                        this.vehicle.getName().getString(),
                        this.vehicle.getId(),
                        RappelSupport.SETTLE_TICKS);
            }
        }
        if (now - this.rappelStableAt < RappelSupport.SETTLE_TICKS) {
            return true; // settle delay — no descents yet
        }

        rappelStartEligible();

        // Done when nobody eligible remains aboard and both ropes are clear —
        // covers "all troopers landed" and "never had eligible cargo".
        if (rappelRopesIdle() && !RappelSupport.hasEligiblePassenger(this.vehicle)) {
            exitRappel("complete");
            return false;
        }
        return true;
    }

    private boolean rappelHoverStable() {
        double targetY = surfaceBelow() + RAPPEL_HOVER_AGL;
        if (Math.abs(this.vehicle.getY() - targetY) > ALT_DEADBAND) return false;
        double dx = this.rappelLockX - this.vehicle.getX();
        double dz = this.rappelLockZ - this.vehicle.getZ();
        return dx * dx + dz * dz <= RAPPEL_STABLE_XZ * RAPPEL_STABLE_XZ;
    }

    private boolean rappelRopesIdle() {
        return this.rappelRopeMinusId < 0 && this.rappelRopePlusId < 0;
    }

    /** Wipe any firing-run / idle into RAPPEL and lock the hover station. */
    private void enterRappel() {
        clearRunAxis();
        this.repositionX = Double.NaN;
        this.repositionZ = Double.NaN;
        this.lastRunTargetId = Integer.MIN_VALUE;
        this.runGateMisses = 0;
        this.runHoldMisses = 0;
        this.rappelLockX = this.vehicle.getX();
        this.rappelLockZ = this.vehicle.getZ();
        this.rappelStartedAt = this.unit.level().getGameTime();
        this.rappelStableAt = Long.MIN_VALUE;
        this.rappelAtIssued = 0;
        setRunPhase(RunPhase.RAPPEL);
    }

    /**
     * Teardown order is load-bearing: clear the request flag (wires gate on the synced
     * RAPPEL phase, which exits next) → exit phase → caller falls through to flight.
     * Active rope slides are NOT cancelled here — they keep advancing until land.
     */
    private void exitRappel(String reason) {
        setRappelRequested(this.vehicle, false);
        if (SewvConfig.HELI_COMBAT_DEBUG.get() && this.vehicle != null) {
            LOGGER.info("[sewv heli] {}#{} rappel teardown reason={} ropesIdle={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    reason,
                    rappelRopesIdle());
        }
        if (this.runPhase == RunPhase.RAPPEL) {
            setRunPhase(RunPhase.IDLE);
        }
        this.rappelLockX = Double.NaN;
        this.rappelLockZ = Double.NaN;
        this.rappelStartedAt = Long.MIN_VALUE;
        this.rappelStableAt = Long.MIN_VALUE;
        this.rappelEngageSince = Long.MIN_VALUE;
        this.rappelAtIssued = 0;
        // Keeps RU/US from immediately re-arming after a timeout/abort that left cargo aboard.
        this.rappelAutonomousCooldownUntil =
                this.unit.level().getGameTime() + RAPPEL_AUTONOMOUS_COOLDOWN_TICKS;
    }

    /**
     * Landing capture station-hover without the pad descent: hover mode + capture
     * lateral blend onto the entry lock, collective onto terrain + {@link #RAPPEL_HOVER_AGL}.
     */
    private void rappelStationHover() {
        double targetY = surfaceBelow() + RAPPEL_HOVER_AGL;
        double dx = this.rappelLockX - this.vehicle.getX();
        double dz = this.rappelLockZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setMouseMoveSpeedX(0.0F);
        this.vehicle.setMouseMoveSpeedY(0.0F);
        this.vehicle.setHoverMode(true);
        applyCollective(targetY);

        double speed = Math.min(CAPTURE_MAX_SPEED, dist * CAPTURE_GAIN);
        double desX = dist > 1.0E-4 ? dx / dist * speed : 0.0;
        double desZ = dist > 1.0E-4 ? dz / dist * speed : 0.0;
        Vec3 v = this.vehicle.getDeltaMovement();
        this.vehicle.setDeltaMovement(
                Mth.lerp(CAPTURE_BLEND, v.x, desX),
                v.y,
                Mth.lerp(CAPTURE_BLEND, v.z, desZ));
    }

    /** Kick eligible cargo onto free ropes (one per side). Pilot/gunners stay aboard. */
    private void rappelStartEligible() {
        if (this.rappelRopeMinusId < 0) {
            tryStartRope(false);
        }
        if (this.rappelRopePlusId < 0) {
            tryStartRope(true);
        }
    }

    private void tryStartRope(boolean plusX) {
        for (Entity passenger : List.copyOf(this.vehicle.getPassengers())) {
            if (!RappelSupport.isRappelEligible(this.vehicle, passenger)) continue;
            if (!(passenger instanceof AbstractUnit unit)) continue;
            int id = unit.getId();
            if (id == this.rappelRopeMinusId || id == this.rappelRopePlusId) continue;

            // Same AT issue seam as DriveVehicleGoal.dismountSquad — first always, second rolls,
            // max two per RAPPEL session. issueAtWeapon no-ops for PMC / already-armed.
            if (this.rappelAtIssued == 0 || (this.rappelAtIssued < MAX_AT_GUNNERS
                    && unit.getRandom().nextDouble() < SewvConfig.AT_SECOND_GUNNER_CHANCE.get())) {
                if (SmallArmsSupport.issueAtWeapon(unit)) this.rappelAtIssued++;
            }

            Vec3 top = RappelSupport.ropeTopWorld(this.vehicle, plusX);
            unit.stopRiding();
            unit.setDeltaMovement(Vec3.ZERO);
            unit.fallDistance = 0.0F;
            unit.setPos(top.x, top.y, top.z);
            if (plusX) {
                this.rappelRopePlusId = id;
                this.rappelRopePlusAx = top.x;
                this.rappelRopePlusAz = top.z;
            } else {
                this.rappelRopeMinusId = id;
                this.rappelRopeMinusAx = top.x;
                this.rappelRopeMinusAz = top.z;
            }
            if (SewvConfig.HELI_COMBAT_DEBUG.get()) {
                LOGGER.info("[sewv heli] {}#{} rappel start unit=#{} rope={} xz={},{}",
                        this.vehicle.getName().getString(),
                        this.vehicle.getId(),
                        id,
                        plusX ? "X+" : "X-",
                        String.format("%.1f", top.x),
                        String.format("%.1f", top.z));
            }
            return;
        }
    }

    /** Advance any in-progress rope slides (committed — survives RAPPEL teardown). */
    private void rappelAdvanceDescents() {
        if (this.rappelRopeMinusId >= 0) {
            if (!advanceRope(false)) {
                this.rappelRopeMinusId = -1;
                this.rappelRopeMinusAx = Double.NaN;
                this.rappelRopeMinusAz = Double.NaN;
            }
        }
        if (this.rappelRopePlusId >= 0) {
            if (!advanceRope(true)) {
                this.rappelRopePlusId = -1;
                this.rappelRopePlusAx = Double.NaN;
                this.rappelRopePlusAz = Double.NaN;
            }
        }
    }

    /** @return true while still descending */
    private boolean advanceRope(boolean plusX) {
        int id = plusX ? this.rappelRopePlusId : this.rappelRopeMinusId;
        double ax = plusX ? this.rappelRopePlusAx : this.rappelRopeMinusAx;
        double az = plusX ? this.rappelRopePlusAz : this.rappelRopeMinusAz;
        if (!(this.unit.level().getEntity(id) instanceof AbstractUnit unit)) {
            return false;
        }
        return RappelSupport.tickDescent(unit, ax, az);
    }

    /** Wipe a committed run to IDLE, logging the gate that forced it when debug is on. */
    private void abandonRun(String reason) {
        if (this.runPhase != RunPhase.IDLE && SewvConfig.HELI_COMBAT_DEBUG.get() && this.vehicle != null) {
            LivingEntity live = this.unit.getTarget();
            // Distinguishes false loss (live enemy still in the scan cylinder) from
            // genuine end-of-fight (nothing left to re-lock).
            VehicleTargetScanGoal.RelockProbe relock =
                    VehicleTargetScanGoal.probeRelock(this.unit, this.vehicle);
            LOGGER.info("[sewv heli] {}#{} ABANDON {}→IDLE reason={} slot={} target={} sticky={} gateMiss={}/{} holdMiss={}/{} alt={} relockInRange={} relockLos={} relockId={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    this.runPhase,
                    reason,
                    this.heldWeaponSlot,
                    live != null ? live.getId() : -1,
                    this.lastRunTargetId,
                    this.runGateMisses,
                    RUN_GATE_GRACE_TICKS,
                    this.runHoldMisses,
                    RUN_GATE_GRACE_TICKS,
                    String.format("%.1f", this.vehicle.getY()),
                    relock.inRange(),
                    relock.hasLos(),
                    relock.id());
        }
        clearRun();
    }

    private void setRunPhase(RunPhase next) {
        boolean changed = this.runPhase != next;
        this.runPhase = next;
        if (changed) {
            syncPhaseDebug(true);
        } else if (this.vehicle != null && next != RunPhase.IDLE && this.vehicle.tickCount % 40 == 0) {
            // Re-broadcast so players who entered tracking range still see the label.
            syncPhaseDebug(false);
        }
    }

    private void syncPhaseDebug(boolean phaseChanged) {
        if (this.vehicle == null) return;
        this.vehicle.getPersistentData().putString(TAG_HELI_RUN_PHASE, this.runPhase.name());
        if (this.vehicle.level() instanceof ServerLevel) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.vehicle),
                    new PacketHeliRunPhase(this.vehicle.getId(), this.runPhase.ordinal()));
        }
        if (phaseChanged && SewvConfig.HELI_COMBAT_DEBUG.get()) {
            LOGGER.info("[sewv heli] {}#{} phase={} slot={} alt={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    this.runPhase,
                    this.heldWeaponSlot,
                    String.format("%.1f", this.vehicle.getY()));
        }
    }

    // Pick once per engagement; re-pick only on target change or magazine truly empty.
    // Mid-reload must NOT thrash the slot. Uses HeliArmament (not selectWeaponForTarget).
    // Mid-run: if the held magazine is empty, do NOT re-pick here — attackPassTick's
    // depleted gate enters BREAK; a re-pick to guided used to collapse the run to IDLE.
    private void updateWeaponHold(LivingEntity target) {
        int seat = this.vehicle.getSeatIndex(this.unit);
        if (seat < 0) {
            clearWeaponHold();
            return;
        }
        int tid = target.getId();
        boolean retarget = tid != this.heldTargetId;
        boolean empty = this.heldWeaponSlot >= 0 && heldWeaponDepleted(seat);
        if (empty && isFiringRunPhase(this.runPhase)) {
            this.vehicle.setWeaponIndex(seat, this.heldWeaponSlot);
            return;
        }
        if (this.heldWeaponSlot < 0 || retarget || empty) {
            boolean armor = target.getVehicle() instanceof VehicleEntity;
            int slot = HeliArmament.pickGroundWeapon(this.vehicle, seat, target);
            if (slot < 0) {
                clearWeaponHold();
                return;
            }
            if (SewvConfig.HELI_COMBAT_DEBUG.get() && slot != this.heldWeaponSlot) {
                LOGGER.info("[sewv heli] {}#{} PICK slot={}→{} armor={} target={} phase={}",
                        this.vehicle.getName().getString(),
                        this.vehicle.getId(),
                        this.heldWeaponSlot,
                        slot,
                        armor,
                        tid,
                        this.runPhase);
            }
            this.vehicle.setWeaponIndex(seat, slot);
            this.heldWeaponSlot = slot;
            this.heldTargetId = tid;
        } else {
            this.vehicle.setWeaponIndex(seat, this.heldWeaponSlot);
        }
    }

    /**
     * Fire assist + optional debug. Logs FIRED with the selected slot, or the gate
     * (skips RPM_WAIT spam — only interesting rejects and actual shots).
     */
    private void logAiFire(LivingEntity target, double coneDeg) {
        VehicleWeapons.FireGate gate = VehicleWeapons.tryAiFireAssistResult(
                this.vehicle, this.unit, target, coneDeg);
        if (!SewvConfig.HELI_COMBAT_DEBUG.get()) return;
        if (gate == VehicleWeapons.FireGate.RPM_WAIT) return;
        int seat = this.vehicle.getSeatIndex(this.unit);
        int selected = seat >= 0 ? this.vehicle.getSelectedWeapon(seat) : -1;
        if (gate == VehicleWeapons.FireGate.FIRED) {
            LOGGER.info("[sewv heli] {}#{} FIRE slot={} selected={} phase={} target={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    this.heldWeaponSlot,
                    selected,
                    this.runPhase,
                    target.getId());
        } else {
            LOGGER.info("[sewv heli] {}#{} NOFIRE gate={} slot={} selected={} phase={} target={}",
                    this.vehicle.getName().getString(),
                    this.vehicle.getId(),
                    gate,
                    this.heldWeaponSlot,
                    selected,
                    this.runPhase,
                    target.getId());
        }
    }

    private boolean heldWeaponDepleted(int seat) {
        if (seat < 0 || this.heldWeaponSlot < 0) return true;
        try {
            GunData gun = VehicleWeapons.gunData(this.vehicle, seat, this.heldWeaponSlot);
            if (gun == null) return true;
            if (gun.reloading()) return false;
            Entity supplier = this.vehicle.getAmmoSupplier();
            if (supplier == null) supplier = this.vehicle;
            return gun.currentAvailableAmmo(supplier) <= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean heldWeaponGuided() {
        if (this.heldWeaponSlot < 0) return false;
        return VehicleMissileAim.modeOfSelected(this.vehicle, this.unit) != null;
    }

    private void clearWeaponHold() {
        this.heldWeaponSlot = -1;
        this.heldTargetId = Integer.MIN_VALUE;
    }

    // Aim platform: two-axis mouse aim. Collective holds {@code holdY} (guided cruise).
    // Hover mode OFF — auto-level would keep the nose flat.
    private void aimAtTarget(LivingEntity target, double horizDist, double holdY) {
        applyCollective(withAvoidFloor(holdY));
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
        this.vehicle.setHoverMode(false);
        aimNoseOnly(target, horizDist);
        logAiFire(target, SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
    }

    // Nose onto target without touching collective / lateral sticks (ATTACK layers this on flyToward).
    private void aimNoseOnly(LivingEntity target, double horizDist) {
        this.vehicle.setHoverMode(false);
        Vec3 dir = new Vec3(target.getX() - this.vehicle.getX(), 0, target.getZ() - this.vehicle.getZ());
        if (dir.lengthSqr() > 1.0E-6) dir = dir.normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, dir));

        double targetCenterY = target.getY() + target.getBbHeight() * 0.5;
        double depressionDeg = Math.toDegrees(Math.atan2(
                this.vehicle.getY() - targetCenterY, Math.max(horizDist, 1.0)));
        float aimAttitude = (float) Mth.clamp(depressionDeg, -MAX_CLIMB_AIM_DEG, MAX_COMBAT_DIVE_DEG);
        float attitudeErr = aimAttitude - this.vehicle.getXRot();

        float mouseX = (float) Mth.clamp(-YAW_STICK_PER_DEG * 2.0 * yawErrDeg, -MAX_AIM_YAW_STICK, MAX_AIM_YAW_STICK);
        float mouseY = (float) Mth.clamp(attitudeErr * AIM_STICK_PER_DEG, -MAX_AIM_PITCH_STICK, MAX_AIM_PITCH_STICK);
        this.vehicle.mouseInput(mouseX, mouseY);
    }

    // Landing: glide-slope approach until the capture ring, then hover-mode
    // capture steered by direct velocity command (captureTick) — no pursuit
    // dynamics near the pad, so no orbiting. Ground contact near the pad
    // settles into sticky LANDED.
    private void doLanding(IHelicopterPilot pilot, BlockPos pad) {
        double surfaceY = touchdownY(pad);
        double px = pad.getX() + 0.5;
        double pz = pad.getZ() + 0.5;
        double dx = px - this.vehicle.getX();
        double dz = pz - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean grounded = this.vehicle.onGround() || this.vehicle.getY() <= surfaceY + 0.35;

        if (grounded && dist <= LAND_SETTLE_RADIUS) {
            settleLanded(pilot);
            return;
        }

        if (this.landingCapture) {
            if (grounded || dist > LAND_CAPTURE_EXIT_RADIUS
                    || this.vehicle.horizontalCollision
                    || this.vehicle.getCollisionCoolDown() > 0) {
                this.landingCapture = false; // grounded short or bounced — go around
                logLandingPhase("CAPTURE_ABORT", dist, surfaceY);
            } else {
                captureTick(surfaceY, dx, dz, dist);
                return;
            }
        } else if (!grounded && dist < LAND_CAPTURE_RADIUS
                && this.vehicle.getCollisionCoolDown() == 0
                && (dist < 1.0E-4 || this.sensor.headingClear(new Vec3(dx / dist, 0, dz / dist), dist))) {
            this.landingCapture = true;
            noteHoverMode("LANDING_CAPTURE");
            logLandingPhase("CAPTURE_ENTER", dist, surfaceY);
            captureTick(surfaceY, dx, dz, dist);
            return;
        }

        double glideY = surfaceY + Mth.clamp(dist * LAND_GLIDE_RATIO, MIN_OVER_DEST, flightAltitude());
        double clearY = AirframeSupport.highestGroundToward(
                this.vehicle, px, pz, TERRAIN_LOOKAHEAD) + MIN_OVER_DEST;
        // Same flyToward body as transit — tag for post-nose-decouple landing re-verify.
        flyToward(px, pz, Math.max(glideY, clearY), LAND_APPROACH_GAIN, "landing");
    }

    // Terminal guidance by direct velocity command: SBW's engine integrates
    // deltaMovement, so a per-tick blended velocity aimed at the pad (decaying
    // with distance) converges monotonically — no attitude pursuit, no limit
    // cycle. Hover mode keeps the hull level; downInput pins collective power
    // at its floor so the auto-throttle can't fight the commanded sink.
    private void captureTick(double surfaceY, double dx, double dz, double dist) {
        AirframeSupport.releaseInputs(this.vehicle);
        this.vehicle.setHoverMode(true);

        double speed = Math.min(CAPTURE_MAX_SPEED, dist * CAPTURE_GAIN);
        double desX = dist > 1.0E-4 ? dx / dist * speed : 0.0;
        double desZ = dist > 1.0E-4 ? dz / dist * speed : 0.0;

        double targetY = surfaceY + (dist < LAND_DESCENT_RADIUS ? 0.0 : CAPTURE_ALT);
        double desVy = Mth.clamp(
                (targetY - this.vehicle.getY()) * CAPTURE_VY_GAIN, -CAPTURE_MAX_SINK, 0.0);

        Vec3 v = this.vehicle.getDeltaMovement();
        double nvy = v.y;
        if (desVy < -0.01) {
            this.vehicle.setDownInputDown(true);
            nvy = Mth.lerp(CAPTURE_BLEND, v.y, desVy);
        }
        this.vehicle.setDeltaMovement(
                Mth.lerp(CAPTURE_BLEND, v.x, desX),
                nvy,
                Mth.lerp(CAPTURE_BLEND, v.z, desZ));
    }

    // Touchdown → sticky LANDED; the hull stays down until a new takeoff order
    // rather than immediately resuming FOLLOW/MOVE orders.
    private void settleLanded(IHelicopterPilot pilot) {
        noteHoverMode("LANDING_SETTLED");
        logLandingPhase("SETTLED", 0.0, this.vehicle.getY());
        AirframeSupport.releaseInputs(this.vehicle);
        this.vehicle.setHoverMode(false);
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        pilot.sewv$setHeliLandPos(null);
        this.landingCapture = false;
        this.avoidFloorY = Double.NaN;
    }

    // Feet-level Y the hull can actually sit at on the ordered block's column:
    // walk up the contiguous solid stack above the pick (bounded), so designating
    // the face of a wall or hillside resolves to the surface on top of it.
    private double touchdownY(BlockPos pad) {
        Level level = this.unit.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(pad.getX(), pad.getY(), pad.getZ());
        for (int i = 0; i < 32; i++) {
            p.move(Direction.UP);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return p.getY();
            }
        }
        return pad.getY() + 1.0;
    }

    // The one lateral primitive: whisker-check the bearing, point the nose at the
    // clear travel direction, and pitch only for along-track speed error while the
    // collective holds desiredY. Desired speed tapers with distance so the hull
    // decelerates onto the point. Nose is never aimed at the 2D velocity-error
    // vector — that crabs/reverse-thrusts (see heli flight-quality diagnosis).
    private void flyToward(double steerX, double steerZ, double desiredY) {
        flyToward(steerX, steerZ, desiredY, APPROACH_GAIN, null);
    }

    private void flyToward(double steerX, double steerZ, double desiredY, String caller) {
        flyToward(steerX, steerZ, desiredY, APPROACH_GAIN, caller);
    }

    private void flyToward(double steerX, double steerZ, double desiredY, double approachGain) {
        flyToward(steerX, steerZ, desiredY, approachGain, null);
    }

    private void flyToward(double steerX, double steerZ, double desiredY, double approachGain,
            @Nullable String caller) {
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);

        double dx = steerX - this.vehicle.getX();
        double dz = steerZ - this.vehicle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        Vec3 dirToDest = dist > 1.0E-4 ? new Vec3(dx / dist, 0, dz / dist) : Vec3.ZERO;

        Vec3 vel = this.vehicle.getDeltaMovement();
        double groundSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // Whiskers: fly the nearest clear bearing to the desired one, probing as
        // far ahead as current momentum demands — but never past the steering
        // point itself (ground beyond a landing pad or a destination at the foot
        // of a wall must not read as "blocked"), with a small floor so a wall
        // right on the nose still registers. A fully blocked cone means terrain
        // taller than the flight level dead ahead — answer vertically: hold and
        // pop above it (the "pitch" whisker).
        double probe = Math.min(
                WHISKER_BASE_DISTANCE + groundSpeed * WHISKER_LOOKAHEAD_TICKS,
                Math.max(dist, 4.0));
        Vec3 travelDir = this.sensor.chooseClearBearing(dirToDest, probe);
        if (travelDir == null) {
            noteAvoidFloorSet(this.vehicle.getY() + AVOID_CLIMB_STEP);
            this.avoidFloorY = this.vehicle.getY() + AVOID_CLIMB_STEP;
            noteHoverMode("WHISKER_AVOID_HOVER");
            holdHover(this.avoidFloorY);
            logFlyToward(caller, "WHISKER_BLOCKED", dirToDest, null, dist, groundSpeed, probe,
                    0.0, 0.0, 0.0, 0.0, 0.0F, vel);
            return;
        }

        if (caller != null) {
            noteHoverMode("landing".equals(caller) ? "LANDING_GLIDE" : "TRANSIT_FLY");
        }

        applyCollective(withAvoidFloor(desiredY));
        this.vehicle.setHoverMode(false); // full control authority while moving

        double desiredSpeed = Math.min(CRUISE_SPEED, dist * approachGain);
        double speedAlong = vel.x * travelDir.x + vel.z * travelDir.z;
        double speedErr = desiredSpeed - speedAlong;

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, travelDir));

        // Yaw always onto the clear path. Pitch only once roughly aligned, and only
        // from the 1D along-track speed error (accelerate / brake without crabbing).
        float attitudeCmd = 0.0F;
        String branch;
        if (Math.abs(yawErrDeg) >= ALIGN_THRESHOLD_DEG) {
            branch = "ALIGN";
        } else {
            branch = "TRACK";
            if (Math.abs(speedErr) >= VEL_ERR_DEADBAND) {
                attitudeCmd = (float) Mth.clamp(
                        speedErr * PITCH_DEG_PER_SPEED_ERR, -MAX_ATTITUDE_DEG, MAX_ATTITUDE_DEG);
            }
        }
        steerNose(forward, travelDir, attitudeCmd);
        logFlyToward(caller, branch, dirToDest, travelDir, dist, groundSpeed, probe,
                desiredSpeed, speedAlong, speedErr, yawErrDeg, attitudeCmd, vel);
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
        logHoldHoverSample(targetY);
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
        double deadband = ALT_DEADBAND;
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
            double yawErrDeg = Math.toDegrees(VehicleTargeting.signedAngleTo(forward, aim));
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
        AirframeSupport.releaseInputs(this.vehicle);
    }

    // Terrain-relative cruise level over the hull's own column.
    private double cruiseAltitudeHere() {
        return AirframeSupport.cruiseAltitudeHere(this.vehicle, flightAltitude());
    }

    // Terrain-relative cruise level for a leg toward (toX, toZ): the configured
    // offset above the HIGHEST ground between here and there, so the collective
    // starts climbing before a ridge and gives the altitude back as the land
    // falls away — instead of holding an absolute level anchored at the takeoff
    // origin into terrain it knows nothing about.
    private double cruiseAltitudeToward(double toX, double toZ) {
        return AirframeSupport.cruiseAltitudeToward(
                this.vehicle, toX, toZ, flightAltitude(), TERRAIN_LOOKAHEAD);
    }

    // The active hold height including the whisker climb floor, which decays about
    // a block per second so surplus avoidance altitude is given back gently.
    private double withAvoidFloor(double desiredY) {
        if (Double.isNaN(this.avoidFloorY)) {
            return desiredY;
        }
        this.avoidFloorY -= AVOID_FLOOR_DECAY;
        if (this.avoidFloorY <= desiredY) {
            this.avoidFloorY = Double.NaN;
            noteAvoidFloorClear(desiredY);
            return desiredY;
        }
        this.flightAvoidFloorWasActive = true;
        return this.avoidFloorY;
    }

    private void clearFlightDiag() {
        this.flightHoverMode = "";
        this.flightBranch = "";
        this.flightLastLogAt = Long.MIN_VALUE;
        this.flightTicksAlign = 0;
        this.flightTicksTrack = 0;
        this.flightTicksWhisker = 0;
        this.flightWasArriveHover = false;
        this.flightAvoidFloorWasActive = false;
    }

    private void noteHoverMode(String mode) {
        if (!SewvDiag.heliFlightVerbose()) {
            this.flightHoverMode = mode;
            this.flightWasArriveHover = "ARRIVE_HOVER".equals(mode);
            return;
        }
        if (!mode.equals(this.flightHoverMode)) {
            Vec3 vel = this.vehicle.getDeltaMovement();
            SewvDiag.flight("{}#{} mode {} -> {} pos={}/{}/{} spdXZ={} avoidFloor={}",
                    this.unit.getName().getString(), this.unit.getId(),
                    this.flightHoverMode.isEmpty() ? "-" : this.flightHoverMode, mode,
                    fmt(this.vehicle.getX()), fmt(this.vehicle.getY()), fmt(this.vehicle.getZ()),
                    fmt(Math.sqrt(vel.x * vel.x + vel.z * vel.z)),
                    Double.isNaN(this.avoidFloorY) ? "-" : fmt(this.avoidFloorY));
            // Leaving a flyToward session — dump branch dwell so a transit/landing leg is readable.
            boolean wasFly = "TRANSIT_FLY".equals(this.flightHoverMode)
                    || "LANDING_GLIDE".equals(this.flightHoverMode);
            boolean stillFly = "TRANSIT_FLY".equals(mode) || "LANDING_GLIDE".equals(mode);
            if (wasFly && !stillFly) {
                SewvDiag.flight("{}#{} branchDwell end mode={} align={} track={} whisker={}",
                        this.unit.getName().getString(), this.unit.getId(), mode,
                        this.flightTicksAlign, this.flightTicksTrack, this.flightTicksWhisker);
                this.flightTicksAlign = 0;
                this.flightTicksTrack = 0;
                this.flightTicksWhisker = 0;
                this.flightBranch = "";
            }
            this.flightHoverMode = mode;
        }
        this.flightWasArriveHover = "ARRIVE_HOVER".equals(mode);
    }

    private void noteArriveThrash() {
        if (!SewvDiag.heliFlightVerbose()) return;
        Vec3 vel = this.vehicle.getDeltaMovement();
        SewvDiag.flight("{}#{} ARRIVE_THRASH left radius={} pos={}/{}/{} spdXZ={} yaw={} xRot={}",
                this.unit.getName().getString(), this.unit.getId(),
                fmt(ARRIVE_RADIUS),
                fmt(this.vehicle.getX()), fmt(this.vehicle.getY()), fmt(this.vehicle.getZ()),
                fmt(Math.sqrt(vel.x * vel.x + vel.z * vel.z)),
                fmt(this.vehicle.getYRot()), fmt(this.vehicle.getXRot()));
    }

    /** Landing phase transitions for post-nose-decouple re-verify (observe-only). */
    private void logLandingPhase(String phase, double dist, double surfaceY) {
        if (!SewvDiag.heliFlightVerbose() || this.vehicle == null) return;
        Vec3 vel = this.vehicle.getDeltaMovement();
        SewvDiag.flight(
                "{}#{} land {} dist={} pos={}/{}/{} surfaceY={} spdXZ={} yaw={} xRot={} capture={}",
                this.unit.getName().getString(), this.unit.getId(), phase,
                fmt(dist),
                fmt(this.vehicle.getX()), fmt(this.vehicle.getY()), fmt(this.vehicle.getZ()),
                fmt(surfaceY),
                fmt(Math.sqrt(vel.x * vel.x + vel.z * vel.z)),
                fmt(this.vehicle.getYRot()), fmt(this.vehicle.getXRot()),
                this.landingCapture);
    }

    private void noteAvoidFloorSet(double floorY) {
        if (!SewvDiag.heliFlightVerbose()) {
            this.flightAvoidFloorWasActive = true;
            return;
        }
        if (!this.flightAvoidFloorWasActive) {
            SewvDiag.flight("{}#{} avoidFloor SET y={} climbStep={}",
                    this.unit.getName().getString(), this.unit.getId(),
                    fmt(floorY), fmt(AVOID_CLIMB_STEP));
        }
        this.flightAvoidFloorWasActive = true;
    }

    private void noteAvoidFloorClear(double desiredY) {
        if (!this.flightAvoidFloorWasActive) return;
        this.flightAvoidFloorWasActive = false;
        if (!SewvDiag.heliFlightVerbose()) return;
        SewvDiag.flight("{}#{} avoidFloor CLEAR desiredY={}",
                this.unit.getName().getString(), this.unit.getId(), fmt(desiredY));
    }

    private void logHoldHoverSample(double targetY) {
        if (!SewvDiag.heliFlightVerbose()) return;
        long now = this.vehicle.level().getGameTime();
        if (this.flightLastLogAt != Long.MIN_VALUE
                && now - this.flightLastLogAt < FLIGHT_LOG_INTERVAL_TICKS) {
            return;
        }
        // Only sample while in a hover mode we care about for the idle repro.
        if (!"IDLE_HOVER".equals(this.flightHoverMode)
                && !"ARRIVE_HOVER".equals(this.flightHoverMode)
                && !"WHISKER_AVOID_HOVER".equals(this.flightHoverMode)) {
            return;
        }
        this.flightLastLogAt = now;
        Vec3 vel = this.vehicle.getDeltaMovement();
        double spd = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        SewvDiag.flight(
                "{}#{} hover mode={} pos={}/{}/{} targetY={} spdXZ={} velHdg={} yaw={} xRot={} avoidFloor={}",
                this.unit.getName().getString(), this.unit.getId(),
                this.flightHoverMode,
                fmt(this.vehicle.getX()), fmt(this.vehicle.getY()), fmt(this.vehicle.getZ()),
                fmt(targetY), fmt(spd),
                spd > 1.0E-4 ? fmt(Math.toDegrees(Math.atan2(vel.z, vel.x))) : "-",
                fmt(this.vehicle.getYRot()), fmt(this.vehicle.getXRot()),
                Double.isNaN(this.avoidFloorY) ? "-" : fmt(this.avoidFloorY));
    }

    private void logFlyToward(@Nullable String caller, String branch,
            Vec3 dirToDest, @Nullable Vec3 travelDir,
            double dist, double groundSpeed, double probe,
            double desiredSpeed, double speedAlong, double speedErr,
            double yawErrDeg, float attitudeCmd, Vec3 vel) {
        switch (branch) {
            case "ALIGN" -> this.flightTicksAlign++;
            case "TRACK" -> this.flightTicksTrack++;
            case "WHISKER_BLOCKED" -> this.flightTicksWhisker++;
            default -> {
            }
        }
        if (!SewvDiag.heliFlightVerbose()) {
            this.flightBranch = branch;
            return;
        }
        long now = this.vehicle.level().getGameTime();
        boolean transition = !branch.equals(this.flightBranch);
        boolean throttle = this.flightLastLogAt == Long.MIN_VALUE
                || now - this.flightLastLogAt >= FLIGHT_LOG_INTERVAL_TICKS;
        if (!transition && !throttle) {
            this.flightBranch = branch;
            return;
        }
        this.flightLastLogAt = now;
        this.flightBranch = branch;

        String tag = caller != null ? caller : "untagged";
        double noseYaw = this.vehicle.getYRot();
        double velHdg = groundSpeed > 1.0E-4
                ? Math.toDegrees(Math.atan2(vel.z, vel.x)) : Double.NaN;
        double noseVelDeg = Double.isNaN(velHdg)
                ? Double.NaN : Mth.wrapDegrees(velHdg - noseYaw);
        double destHdg = dirToDest.lengthSqr() > 1.0E-8
                ? Math.toDegrees(Math.atan2(dirToDest.z, dirToDest.x)) : Double.NaN;
        double travelHdg = travelDir != null && travelDir.lengthSqr() > 1.0E-8
                ? Math.toDegrees(Math.atan2(travelDir.z, travelDir.x)) : Double.NaN;

        SewvDiag.flight(
                "{}#{} fly caller={} branch={} pos={}/{}/{} dist={} spd={} probe={} "
                        + "dirDest={} travel={} desSpd={} speedAlong={} speedErr={} "
                        + "yawErr={} attCmd={} noseYaw={} xRot={} velHdg={} noseVelDeg={} "
                        + "dwell[align={} track={} w={}] avoidFloor={}",
                this.unit.getName().getString(), this.unit.getId(),
                tag, branch,
                fmt(this.vehicle.getX()), fmt(this.vehicle.getY()), fmt(this.vehicle.getZ()),
                fmt(dist), fmt(groundSpeed), fmt(probe),
                fmtDeg(destHdg), fmtDeg(travelHdg),
                fmt(desiredSpeed), fmt(speedAlong), fmt(speedErr),
                fmt(yawErrDeg), fmt(attitudeCmd),
                fmt(noseYaw), fmt(this.vehicle.getXRot()),
                fmtDeg(velHdg), fmtDeg(noseVelDeg),
                this.flightTicksAlign, this.flightTicksTrack, this.flightTicksWhisker,
                Double.isNaN(this.avoidFloorY) ? "-" : fmt(this.avoidFloorY));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String fmtDeg(double v) {
        return Double.isNaN(v) ? "-" : fmt(v);
    }

    // Terrain-relative cruise offset: the pilot's own live cruise altitude (set by the takeoff
    // order from the TDT stepper, or the default for autonomous crews), hard-clamped to the 30-50
    // band the flight model is designed around. Read fresh every tick, so retrimming it airborne
    // takes effect immediately.
    private double flightAltitude() {
        int alt = (this.unit instanceof IHelicopterPilot pilot)
                ? pilot.sewv$getCruiseAltitude() : IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
        return Mth.clamp(alt, MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }

    private int surfaceBelow() {
        return AirframeSupport.surfaceBelow(this.vehicle);
    }
}
