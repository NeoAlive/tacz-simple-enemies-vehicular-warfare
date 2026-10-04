package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
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
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.bridge.IAiFireTracker;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleMissileAim;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.support.AirLod;
import com.neoalive.tacz_sewv.entity.ai.support.AirframeSupport;
import com.neoalive.tacz_sewv.entity.ai.support.DecoyEpisode;
import com.neoalive.tacz_sewv.entity.ai.support.HeliArmament;
import com.neoalive.tacz_sewv.entity.ai.support.RappelSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.heli.HeliFlight;
import com.neoalive.tacz_sewv.heli.HeliRuntime;
import com.neoalive.tacz_sewv.heli.HeliTrace;
import com.neoalive.tacz_sewv.heli.guidance.Envelope;
import com.neoalive.tacz_sewv.heli.guidance.FirePhase;
import com.neoalive.tacz_sewv.heli.guidance.ModeSelector;
import com.neoalive.tacz_sewv.heli.guidance.OrderKind;
import com.neoalive.tacz_sewv.heli.guidance.ProcedureId;
import com.neoalive.tacz_sewv.heli.guidance.Situation;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketHeliRunPhase;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.util.ChunkTicket;

/**
 * Pilot of an AI-crewed SuperbWarfare helicopter. Since the physics rework this goal no longer
 * touches a stick: SBW's {@code helicopterEngine} is replaced for the hull by our own rigid body,
 * rotor model and cascaded controller ({@code heli.*}), and this goal is the layer above them.
 * Each tick it:
 * <ol>
 * <li>keeps the order bookkeeping it always did: forced land and rappel, sticky LANDED, takeoff,
 *     the RU/US takeoff normalisation, chunk tickets, flares, weapon hold and fire assist, rappel
 *     ropes;</li>
 * <li>does every world read guidance needs (destination, terrain-relative altitudes, pads, target)
 *     and writes them into a {@link Situation};</li>
 * <li>asks the pure {@link ModeSelector} for a procedure and hands both to the hull's
 *     {@link HeliRuntime}, which flies it from inside the hull's own tick.</li>
 * </ol>
 *
 * <p>Combat is the selector's attack rows: FireStill (guided weapon on armour), FireLoop and
 * FireRun, chosen from the summary this goal fills (target category, line of sight, motion, weapon,
 * envelope flags, the runtime's engage cycle). The run phase shown on the overlay and read by the
 * scan goals is the active procedure's. The fire assist only shoots inside the procedure's fire
 * window. The public static API below (tags, {@link RunPhase}, forced-order and rappel flags,
 * {@link #inFiringRun}) is unchanged, because packets, commands, the scan goals and the overlay all
 * depend on it.
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
    /** Player Land from TDT/map — cleared on touchdown; outranks FOLLOW/combat until then. */
    public static final String TAG_FORCED_LAND = "sewv:heli_forced_land";
    /** Player Rappel from TDT — cleared when the sequence finishes. */
    public static final String TAG_FORCED_RAPPEL = "sewv:heli_forced_rappel";
    /** Hull backup for the landing block (pilot NBT is authoritative when present). */
    public static final String TAG_LAND_PAD = "sewv:heli_land_pad";

    public static void setForcedLand(VehicleEntity v, BlockPos pad) {
        if (v == null) return;
        v.getPersistentData().putBoolean(TAG_FORCED_LAND, true);
        v.getPersistentData().putLong(TAG_LAND_PAD, pad.asLong());
    }

    public static void clearForcedLand(VehicleEntity v) {
        if (v == null) return;
        v.getPersistentData().remove(TAG_FORCED_LAND);
        v.getPersistentData().remove(TAG_LAND_PAD);
    }

    public static void setForcedRappel(VehicleEntity v) {
        if (v == null) return;
        v.getPersistentData().putBoolean(TAG_FORCED_RAPPEL, true);
        setRappelRequested(v, true);
    }

    public static void clearForcedRappel(VehicleEntity v) {
        if (v == null) return;
        v.getPersistentData().remove(TAG_FORCED_RAPPEL);
    }

    @Nullable
    private static BlockPos resolveLandPad(IHelicopterPilot pilot, VehicleEntity v) {
        BlockPos pad = pilot.sewv$getHeliLandPos();
        if (pad != null) return pad;
        if (v.getPersistentData().contains(TAG_LAND_PAD)) {
            return BlockPos.of(v.getPersistentData().getLong(TAG_LAND_PAD));
        }
        return null;
    }

    /**
     * True while this hull's pilot is in INGRESS/ATTACK/BREAK/REPOSITION. Written to the hull's
     * persistent data every phase change so mounted-lock goals can read it without holding a goal
     * reference. IDLE / RAPPEL / missing tag = not in a firing run.
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

    /**
     * Horizontal standoff so that a nose depression of {@code maxDepressionDeg} points at a
     * target {@code heightAboveTarget} below the hold altitude, floored at {@code minStandoff}.
     * Pure geometry — no world access. When height <= 0 (target at/above hold), returns the floor.
     */
    public static double guidedStandoffRing(double heightAboveTarget, double maxDepressionDeg, double minStandoff) {
        return Envelope.standoffRing(heightAboveTarget, Math.toRadians(maxDepressionDeg), minStandoff);
    }

    /** Hull NBT: the XZ (BlockPos long) a RU/US patrol loop is centred on, written when a pilot first claims the hull. */
    public static final String TAG_PATROL_ANCHOR = "sewv:heli_patrol_anchor";

    private static final double ALT_DEADBAND = 2.5;

    // --- Cruise altitude (terrain-relative) ---
    // The terrain offset is the pilot's TDT altitude hard-clamped to this band.
    private static final double MIN_FLIGHT_ALT = 30.0;
    private static final double MAX_FLIGHT_ALT = 50.0;
    // Never hold below destination + this (e.g. stay above the followed player).
    private static final double MIN_OVER_DEST = 12.0;
    // How far ahead along a leg the highest ground is looked for.
    private static final double TERRAIN_LOOKAHEAD = 48.0;

    // --- Rappel ---
    // Terrain-relative AGL — not an offset from current altitude.
    private static final double RAPPEL_HOVER_AGL = 10.0;
    /** Last-resort exit if a rappel never completes (debug left on, etc.). */
    private static final long RAPPEL_TIMEOUT_TICKS = 6000L;
    private static final double RAPPEL_STABLE_XZ = 1.0;
    /** RU/US combat insert: enemy must be within this horizontal range. */
    private static final double RAPPEL_INSERT_RADIUS = 64.0;
    /** Ticks holding an in-range enemy before dropping — not first-contact insta-rappel. */
    private static final int RAPPEL_ENGAGE_DEBOUNCE_TICKS = 40;
    /** After any rappel ends, don't autonomous-retrigger while still in the same scrap. */
    private static final int RAPPEL_AUTONOMOUS_COOLDOWN_TICKS = 200;

    // --- Landing ---
    private static final double LAND_SETTLE_RADIUS = 6.5;
    /** Settle radius for a helipad specifically: a wide radius let a hull graze a tree short of it. */
    private static final double HELIPAD_SETTLE_RADIUS = 2.25;
    /** Height above the highest ground on the leg that the run-in to a pad is flown at. */
    private static final double TRANSIT_AGL = 24.0;

    // --- Combat geometry ---
    /** Weapon reach handed to the attack envelopes: guided missiles, everything else. */
    private static final double GUIDED_RANGE = 160.0, UNGUIDED_RANGE = 100.0;
    /** Below this target ground speed it is STATIC, above the second FAST, m/s. */
    private static final double MOTION_STATIC = 0.5, MOTION_FAST = 8.0;
    /** groundRef ring radii around the target, and its refresh period. */
    private static final double[] GROUND_RING = {32.0, 64.0};
    private static final long GROUND_REF_TTL = 10L;

    private static final float DECOY_HEALTH_FRACTION = 0.5F;
    private static final float PRESERVE_DECOY_CHANCE = 0.5F;

    private final AbstractUnit unit;
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    private final HullFacts hull = new HullFacts();
    private final DecoyEpisode flares = new DecoyEpisode();
    // Held on the airframe so it keeps flying with no player nearby (config-gated).
    private final ChunkTicket chunkTicket = new ChunkTicket();

    private VehicleEntity vehicle;
    private HeliRuntime runtime;
    /** Physical seat weapon slot held for this engagement, or -1 if none. */
    private int heldWeaponSlot = -1;
    /** Network id of the target the hold was taken against. */
    private int heldTargetId = Integer.MIN_VALUE;
    private RunPhase runPhase = RunPhase.IDLE;
    /** Where an idle or fighting hull holds station; NaN = take the current position next tick. */
    private double holdX = Double.NaN, holdZ = Double.NaN;
    private boolean holdForCombat;
    /** Cached groundRef around the current target. */
    private double groundRef = Double.NaN;
    private long groundRefAt = Long.MIN_VALUE;
    private int groundRefTarget = -1;

    /** XZ locked on RAPPEL entry. */
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

    /** When true, transit/land/rappel only — no combat (unarmed troop-lift hulls). */
    private final boolean transportOnly;

    public DriveHelicopterGoal(AbstractUnit unit) {
        this(unit, false);
    }

    DriveHelicopterGoal(AbstractUnit unit, boolean transportOnly) {
        this.unit = unit;
        this.transportOnly = transportOnly;
        this.setFlags(EnumSet.noneOf(Flag.class)); // flying doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) flies — same driver/commander model as the ground goal.
        if (v.getFirstPassenger() != this.unit) return false;

        this.hull.attach(v);
        if (!this.hull.isHelicopter()) return false;
        boolean transport = com.neoalive.tacz_sewv.compat.NpcVehicleOverrides.isTransportHeli(v);
        if (this.transportOnly != transport) return false;

        this.vehicle = v;
        // Run whenever mounted in a helicopter, even with no destination: a helicopter
        // must be actively controlled to hold station, so "idle" means "hover", not "off".
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.unit.getVehicle() != this.vehicle
                || this.vehicle == null
                || this.runtime == null
                || this.vehicle.getFirstPassenger() != this.unit
                || this.vehicle.isWreck()
                || !this.hull.isHelicopter()) {
            return false;
        }
        boolean transport = com.neoalive.tacz_sewv.compat.NpcVehicleOverrides.isTransportHeli(this.vehicle);
        return this.transportOnly == transport;
    }

    // Guidance must be refreshed every game tick (the runtime treats a snapshot older than a second
    // as a dead pilot); vanilla only ticks running goals every OTHER tick unless this is overridden.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        // A freshly boarded PMC helicopter sitting on the ground stays parked (sticky LANDED) until
        // an explicit takeoff order. RU/US crews take no player flight orders and lift off instead
        // (see the normalisation in tick()). Spawned PMC crews are unaffected: TankSpawner issues
        // TAKEOFF before their first AI tick.
        if (this.vehicle != null && this.vehicle.onGround()
                && this.unit instanceof PmcUnitEntity
                && this.unit instanceof IHelicopterPilot pilot
                && pilot.sewv$getHeliCommand() == IHelicopterPilot.HELI_CMD_NONE) {
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        }
        this.runtime = this.vehicle == null ? null : HeliFlight.attach(this.vehicle, this.unit);
        if (this.vehicle != null && !this.vehicle.getPersistentData().contains(TAG_PATROL_ANCHOR)) {
            this.vehicle.getPersistentData().putLong(TAG_PATROL_ANCHOR, this.vehicle.blockPosition().asLong());
        }
    }

    @Override
    public void stop() {
        if (this.vehicle != null) {
            AirframeSupport.releaseInputs(this.vehicle);
            AirframeSupport.clearDecoy(this.vehicle);
            setRappelRequested(this.vehicle, false);
            clearForcedRappel(this.vehicle);
            // A pending Land is deliberately NOT cleared: pad and flag are persistent, so a
            // reload or a crew change resumes the approach instead of silently dropping it.
            this.chunkTicket.release(this.vehicle);
            HeliFlight.detach(this.vehicle);
        }
        this.vehicle = null;
        this.runtime = null;
        clearWeaponHold();
        clearHold();
        this.committed = null;
        if (this.runPhase != RunPhase.IDLE) setRunPhase(RunPhase.IDLE);
        clearRappelState();
        this.allyAssist.clear();
    }

    @Override
    public void tick() {
        if (HeliFlight.stale(this.runtime)) {
            HeliRuntime fresh = HeliFlight.attach(this.vehicle, this.unit);
            if (fresh != null) this.runtime = fresh;
        }
        HudNotify.watchPmcVehicle(this.unit, this.vehicle);
        IHelicopterPilot pilot = (this.unit instanceof IHelicopterPilot p) ? p : null;
        int command = pilot != null ? pilot.sewv$getHeliCommand() : IHelicopterPilot.HELI_CMD_NONE;
        // Sticky LANDED on the deck: no ticket. Airborne / takeoff / landing keep the follow.
        boolean parked = command == IHelicopterPilot.HELI_CMD_LANDED && this.vehicle.onGround();
        AirframeSupport.updateChunkLoading(this.chunkTicket, this.vehicle,
                SewvConfig.HELI_CHUNK_LOADING.get() && !parked);

        // A burning airframe keeps popping flares all the way down.
        AirframeSupport.updateDecoy(this.vehicle, this.unit, this.flares,
                DECOY_HEALTH_FRACTION, PRESERVE_DECOY_CHANCE);

        // Hostile RU/US crews take no player flight orders and never idle parked: any grounded
        // resting state resolves to an immediate takeoff.
        if (pilot != null && !(this.unit instanceof PmcUnitEntity)
                && this.vehicle.onGround()
                && (command == IHelicopterPilot.HELI_CMD_NONE
                    || command == IHelicopterPilot.HELI_CMD_LANDED)) {
            command = IHelicopterPilot.HELI_CMD_TAKEOFF;
            pilot.sewv$setHeliCommand(command);
        }

        // Committed rope slides finish even if RAPPEL tears down mid-descent.
        rappelAdvanceDescents();

        Situation sit = new Situation();
        sit.time = this.vehicle.level().getGameTime() / 20.0;
        sit.pilotId = this.unit.getId();
        ProcedureId active = this.runtime.activeId();
        if (active != null) sit.active = active;
        fillCommon(sit);

        if (!forcedOrder(pilot, sit)) {
            if (command == IHelicopterPilot.HELI_CMD_LANDED) {
                sit.order = OrderKind.LANDED;
            } else {
                if (command == IHelicopterPilot.HELI_CMD_TAKEOFF) {
                    double climbTo = cruiseAltitudeHere();
                    if (this.vehicle.getY() >= climbTo - ALT_DEADBAND && !this.vehicle.onGround()) {
                        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
                    } else {
                        sit.order = OrderKind.TAKEOFF;
                        sit.climbTo = climbTo;
                    }
                }
                if (sit.order == OrderKind.NONE) {
                    maybeAutonomousRappel();
                    duty(sit);
                }
            }
        }
        // Engine out: in the air the selector autorotates whatever the order; on the ground a hull
        // that cannot start again (failed, or no fuel) is parked rather than left asking to fly.
        HeliState.Engine engine = this.runtime.state().engine;
        boolean engineDead = engine == HeliState.Engine.FAILED
                || (engine == HeliState.Engine.OFF && this.vehicle.getEnergy() <= 0);
        if (this.vehicle.onGround()) {
            if (engineDead) sit.order = OrderKind.LANDED;
        } else if ((engine == HeliState.Engine.OFF || engine == HeliState.Engine.FAILED)
                && this.runtime.activeId() != ProcedureId.PARK) { // a parked hull's onGround can flicker
            sit.engineOut = true;
        }
        if (sit.targetValid) {
            Airframe af = this.runtime.airframe();
            double g = this.runtime.gravity();
            sit.windCeilingOk = Envelope.windOk(af, g, Airframe.RHO0, 0.0); // no wind source in-world (plan O8)
            sit.yawStandoffOk = Envelope.yawOk(af, g, Airframe.RHO0, sit, this.vehicle.getX(), this.vehicle.getZ());
        }
        this.runtime.setGuidance(sit, ModeSelector.select(sit));
        if (this.runPhase != RunPhase.RAPPEL) {
            RunPhase phase = switch (this.runtime.firePhase()) {
                case INGRESS -> RunPhase.INGRESS;
                case ATTACK -> RunPhase.ATTACK;
                case BREAK -> RunPhase.BREAK;
                case REPOSITION -> RunPhase.REPOSITION;
                case NONE -> RunPhase.IDLE;
            };
            if (phase != this.runPhase) setRunPhase(phase);
        }
    }

    /** Summary and geometry every tick needs, whatever the order: health, engage state, altitudes, patrol. */
    private void fillCommon(Situation sit) {
        float max = this.vehicle.getMaxHealth();
        sit.healthFrac = max > 0.0F ? this.vehicle.getHealth() / max : 1.0;
        sit.evadeHealth = this.runtime.airframe().evadeHealth;
        sit.engageCycle = this.runtime.engageCycle();
        sit.inFiringRun = this.runtime.activeId() == ProcedureId.FIRE_RUN;
        sit.autonomous = !(this.unit instanceof PmcUnitEntity);
        Vec3 dm = this.vehicle.getDeltaMovement();
        sit.hullVx = dm.x * 20.0;
        sit.hullVz = dm.z * 20.0;
        double[] ahead = this.runtime.lookahead();
        sit.cruiseY = ahead != null ? cruiseAltitudeToward(ahead[0], ahead[1]) : cruiseAltitudeHere();
        sit.groundBelow = surfaceBelow();
        if (this.vehicle.getPersistentData().contains(TAG_PATROL_ANCHOR)) {
            BlockPos anchor = BlockPos.of(this.vehicle.getPersistentData().getLong(TAG_PATROL_ANCHOR));
            sit.anchorX = anchor.getX() + 0.5;
            sit.anchorZ = anchor.getZ() + 0.5;
        }
        sit.seed = this.vehicle.getUUID().getMostSignificantBits() ^ this.vehicle.getUUID().getLeastSignificantBits();
        sit.engageRadius = SewvConfig.HELI_ENGAGE_RADIUS.get();
        sit.minStandoff = SewvConfig.HELI_MIN_STANDOFF.get();
        sit.fireCone = Math.toRadians(fireConeDeg());
    }

    /** The target half of the summary and geometry, for the attack rows and procedures. */
    private void fillTarget(Situation sit, LivingEntity target) {
        sit.targetValid = true;
        sit.targetId = target.getId();
        // Geometry is anchored on the hull a mounted target rides (where SBW's own missile aim points
        // too): the crew of one tank are several metres apart, and a hand-off between them must not
        // move a FireStill station or a FireLoop centre.
        Entity mover = target.getVehicle() != null ? target.getVehicle() : target;
        sit.targetX = mover.getX();
        sit.targetY = mover.getY();
        sit.targetZ = mover.getZ();
        Vec3 tv = mover.getDeltaMovement();
        sit.targetVx = tv.x * 20.0;
        sit.targetVy = mover.onGround() ? 0.0 : tv.y * 20.0;
        sit.targetVz = tv.z * 20.0;
        VehicleEntity ride = target.getVehicle() instanceof VehicleEntity v ? v : null;
        sit.targetHullId = ride != null ? ride.getId() : -1;
        if (ride != null && isAirframe(ride)) {
            sit.targetCategory = Situation.TargetCategory.AIR;
        } else if (VehicleWeapons.classifyTarget(target) == VehicleWeapons.TargetCategory.VEHICLE) {
            sit.targetCategory = Situation.TargetCategory.VEHICLE;
        } else {
            sit.targetCategory = Situation.TargetCategory.INFANTRY;
        }
        sit.targetDistance = Math.hypot(sit.targetX - this.vehicle.getX(), sit.targetZ - this.vehicle.getZ());
        sit.targetLos = this.unit.getSensing().hasLineOfSight(target);
        double speed = Math.hypot(sit.targetVx, sit.targetVz);
        sit.targetMotion = speed < MOTION_STATIC ? Situation.TargetMotion.STATIC
                : speed < MOTION_FAST ? Situation.TargetMotion.SLOW : Situation.TargetMotion.FAST;
        int seat = this.vehicle.getSeatIndex(this.unit);
        sit.armed = !this.transportOnly && this.heldWeaponSlot >= 0;
        sit.ammoFrac = sit.armed && heldWeaponDepleted(seat) ? 0.0 : 1.0;
        sit.weaponGuided = sit.armed && HeliArmament.isGuidedProjectile(
                HeliArmament.readSignals(this.vehicle, seat, this.heldWeaponSlot).projectileId());
        sit.noseAim = sit.armed && (!sit.weaponGuided || noseBeamRider());
        sit.weaponRange = sit.weaponGuided ? GUIDED_RANGE : UNGUIDED_RANGE;
        sit.groundRef = groundRefAround(target);
        sit.aimDy = mover.getBbHeight() / 2.0;
        if (sit.armed) {
            sit.projSpeed = this.vehicle.getProjectileVelocity(this.unit) * 20.0;
            sit.projGravity = this.vehicle.getProjectileGravity(this.unit) * 400.0;
            if (sit.noseAim) {
                Vec3 aim = aimPoint(target);
                sit.aimX = aim.x;
                sit.aimY = aim.y;
                sit.aimZ = aim.z;
            }
        }
    }

    private static boolean isAirframe(VehicleEntity v) {
        EngineType t = HullFacts.engineType(v);
        return t == EngineType.HELICOPTER || t == EngineType.AIRCRAFT;
    }

    /**
     * groundRef for attack geometry (plan 4.0): the highest WORLD_SURFACE (trees count) at the
     * target and on two rings round it, where stations and run legs are flown. Never sync-loads;
     * refreshed every {@link #GROUND_REF_TTL} ticks or on a new target.
     */
    private double groundRefAround(LivingEntity target) {
        long now = this.vehicle.level().getGameTime();
        if (target.getId() == this.groundRefTarget && now - this.groundRefAt < GROUND_REF_TTL) return this.groundRef;
        Level level = this.vehicle.level();
        int best = AirframeSupport.surfaceAtLoaded(level, target.getBlockX(), target.getBlockZ());
        if (best == Integer.MIN_VALUE) best = Mth.floor(target.getY());
        for (double r : GROUND_RING) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                int h = AirframeSupport.surfaceAtLoaded(level, Mth.floor(target.getX() + r * Math.sin(a)),
                        Mth.floor(target.getZ() + r * Math.cos(a)));
                if (h != Integer.MIN_VALUE) best = Math.max(best, h);
            }
        }
        this.groundRef = best;
        this.groundRefAt = now;
        this.groundRefTarget = target.getId();
        return best;
    }

    /**
     * Normal duty: fight a live target (the selector picks the attack procedure from what is filled
     * here; the fire assist shoots inside the procedure's fire window), else fly an ordered
     * destination, else hold station where the hull is (or patrol, for RU/US).
     */
    private void duty(Situation sit) {
        // An RU/US hull with troops in its weaponless seats flies no attack procedure: it patrols,
        // its door guns still fire on SBW's own seat loop, and the insert drops the troops when the
        // patrol brings it near the fight. A hull whose every seat is armed (mi_28) has no such seat
        // and fights as before.
        boolean carrying = !(this.unit instanceof PmcUnitEntity) && RappelSupport.hasEligiblePassenger(this.vehicle);
        LivingEntity target = this.transportOnly || carrying ? null : committedTarget(this.unit.getTarget());
        boolean pinned = flightPinnedByOrder();
        sit.underOrders = pinned;
        if (target != null) {
            updateWeaponHold(target);
            // A pinned flight path doesn't ground the guns: canShoot still gates ammo, CEASE_FIRE,
            // line of fire and smoke. The attack procedure's window gates the assist on top.
            if (this.runtime.fireWindow()) {
                fireAssist(target);
            } else if (HeliTrace.tracing(this.vehicle)) {
                HeliTrace.noteFire(this.vehicle, false, "WINDOW", VehicleWeapons.boresightAngleDeg(this.vehicle, this.unit, target),
                        effectiveConeDeg(),
                        !((IAiFireTracker) this.vehicle).tacz_sewv$lineOfFireBlocked(this.unit, target));
            }
        } else {
            clearWeaponHold();
        }

        if (target != null && !pinned) {
            fillTarget(sit, target);
            holdHere(true);
            sit.holdX = this.holdX;
            sit.holdZ = this.holdZ;
            sit.holdY = Math.max(cruiseAltitudeHere(), target.getY() + MIN_OVER_DEST);
            return;
        }

        BlockPos dest = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        // resolveDestination answers an RU/US crew's own target as its destination; a carrying hull
        // patrols instead of flying to it (an objective ahead of the target, e.g. a capture, stays).
        LivingEntity seen = this.unit.getTarget();
        if (carrying && dest != null && seen != null && dest.equals(seen.blockPosition())) dest = null;
        if (dest != null) {
            clearHold();
            double px = dest.getX() + 0.5, pz = dest.getZ() + 0.5;
            sit.hasDestination = true;
            sit.destX = px;
            sit.destZ = pz;
            sit.destY = orderAltitude(px, pz, dest, false);
            sit.destDistance = Math.hypot(px - this.vehicle.getX(), pz - this.vehicle.getZ());
            sit.holdX = px;
            sit.holdZ = pz;
            sit.holdY = orderAltitude(px, pz, dest, true);
            return;
        }
        holdHere(false);
        sit.holdX = this.holdX;
        sit.holdZ = this.holdZ;
        sit.holdY = cruiseAltitudeHere();
    }

    /** Latch the station on first use, so a hold is a fixed point rather than wherever the hull drifted. */
    private void holdHere(boolean combat) {
        if (Double.isNaN(this.holdX) || this.holdForCombat != combat) {
            this.holdX = this.vehicle.getX();
            this.holdZ = this.vehicle.getZ();
            this.holdForCombat = combat;
        }
    }

    private void clearHold() {
        this.holdX = Double.NaN;
        this.holdZ = Double.NaN;
    }

    /**
     * Land and rappel, ahead of anything else this goal can do. They stay inside this goal rather
     * than a separate priority-0 one: a second goal would have to make this one stand down, and
     * {@code stop()} then releases the chunk ticket and the rappel flag mid-order.
     *
     * @return true when the order owns the tick.
     */
    private boolean forcedOrder(@Nullable IHelicopterPilot pilot, Situation sit) {
        if (pilot == null) return false;
        boolean playerLand = this.vehicle.getPersistentData().getBoolean(TAG_FORCED_LAND)
                || pilot.sewv$getHeliCommand() == IHelicopterPilot.HELI_CMD_LANDING;
        boolean playerRappel = this.vehicle.getPersistentData().getBoolean(TAG_FORCED_RAPPEL);
        boolean rappelling = playerRappel || isRappelRequested(this.vehicle) || this.runPhase == RunPhase.RAPPEL;
        if (!playerLand && !rappelling) return false;

        if (playerLand || playerRappel) {
            this.unit.setTarget(null);
            clearWeaponHold();
        }

        // Land outranks rappel if both are somehow armed.
        if (playerLand) {
            BlockPos pad = resolveLandPad(pilot, this.vehicle);
            if (pad == null) {
                clearForcedLand(this.vehicle);
                pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_NONE);
                return false;
            }
            // Re-assert every tick: nothing else may retask the hull mid-approach.
            pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDING);
            pilot.sewv$setHeliLandPos(pad);
            landing(pilot, pad, sit);
            return true;
        }

        // Nothing to rope down from on the deck; clear rather than leave the order latched.
        if (this.vehicle.onGround() && this.runPhase != RunPhase.RAPPEL) {
            clearForcedRappel(this.vehicle);
            setRappelRequested(this.vehicle, false);
            return false;
        }
        if (rappelTick(sit)) return true;
        clearForcedRappel(this.vehicle);
        return false;
    }

    /**
     * Landing guidance: the pad, the Y the hull rests at, and a run-in altitude clear of the
     * highest ground on the leg. Settling (grounded inside the settle radius) is decided here and
     * hands the hull to PARK.
     */
    private void landing(IHelicopterPilot pilot, BlockPos pad, Situation sit) {
        double surfaceY = touchdownY(pad);
        double px = pad.getX() + 0.5, pz = pad.getZ() + 0.5;
        double dist = Math.hypot(px - this.vehicle.getX(), pz - this.vehicle.getZ());
        boolean grounded = this.vehicle.onGround() || this.vehicle.getY() <= surfaceY + 0.35;
        // A helipad is a precise target; the wide field radius let a hull graze a tree short of it.
        boolean helipad = this.unit.level() instanceof ServerLevel sl
                && com.neoalive.tacz_sewv.airport.HelipadRegistry.get(sl).contains(pad);
        if (grounded && dist <= (helipad ? HELIPAD_SETTLE_RADIUS : LAND_SETTLE_RADIUS)) {
            settleLanded(pilot);
            sit.order = OrderKind.LANDED;
            return;
        }
        sit.order = OrderKind.LAND;
        sit.padX = px;
        sit.padZ = pz;
        sit.touchdownY = surfaceY;
        sit.transitY = Math.max(surfaceY + TRANSIT_AGL,
                AirframeSupport.highestGroundToward(this.vehicle, px, pz, TERRAIN_LOOKAHEAD) + TRANSIT_AGL);
    }

    /**
     * Hover / transit Y for an ordered destination. TDT cruise (AGL, clamped) is always the floor.
     * A commander in a helicopter is matched so follow and formation stay on their altitude; on
     * foot the dest+clearance rule keeps the rotor off their head.
     */
    private double orderAltitude(double px, double pz, BlockPos dest, boolean arrived) {
        double cruise = arrived ? cruiseAltitudeHere() : cruiseAltitudeToward(px, pz);
        if (this.unit instanceof PmcUnitEntity pmc) {
            OrderType order = pmc.getOrder();
            if (order == OrderType.FOLLOW_COMMANDER
                    || order == OrderType.FORM_WEDGE
                    || order == OrderType.FORM_COLUMN) {
                VehicleEntity leader = VehicleTargeting.commanderHelicopter(pmc);
                if (leader != null) {
                    return Math.max(cruise, leader.getY());
                }
            }
        }
        return Math.max(cruise, dest.getY() + MIN_OVER_DEST);
    }

    // True when the pilot's current SEM order explicitly owns the flight path. HOLD_POSITION and
    // CEASE_FIRE pin too: a crew ordered to hold parks and watches rather than being dragged across
    // the map by a retaliation target.
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

    // --- Weapons ---------------------------------------------------------------------------------

    // Pick once per engagement; re-pick only on target change or magazine truly empty. Mid-reload
    // must NOT thrash the slot. HeliArmament, not selectWeaponForTarget (that latches rockets).
    private void updateWeaponHold(LivingEntity target) {
        int seat = this.vehicle.getSeatIndex(this.unit);
        if (seat < 0) {
            clearWeaponHold();
            return;
        }
        int tid = target.getId();
        boolean retarget = tid != this.heldTargetId;
        boolean empty = this.heldWeaponSlot >= 0 && heldWeaponDepleted(seat);
        if (this.heldWeaponSlot < 0 || retarget || empty) {
            int slot = HeliArmament.pickGroundWeapon(this.vehicle, seat, target);
            if (slot < 0) {
                clearWeaponHold();
                return;
            }
            if (ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG) && slot != this.heldWeaponSlot) {
                LOGGER.info("[sewv heli] {}#{} PICK slot={}->{} target={}",
                        this.vehicle.getName().getString(), this.vehicle.getId(), this.heldWeaponSlot, slot, tid);
            }
            this.vehicle.setWeaponIndex(seat, slot);
            this.heldWeaponSlot = slot;
            this.heldTargetId = tid;
        } else {
            this.vehicle.setWeaponIndex(seat, this.heldWeaponSlot);
        }
    }

    /**
     * The pilot's wire-guided missile rides the HULL's nose line, not a turret (WireGuideMissileEntity:
     * an aircraft's own pilot gets {@code getViewVector}, everyone else the barrel; javap). Fired with
     * the nose 25 deg above the target, the way a hovering FireStill holds it, the missile flies level
     * over the target or, nose slightly down, into the ground short of it. So the pilot fires one only
     * with the nose on the target, inside SBW's own 4 deg AI gate, with no NPC cone floor.
     */
    private static final double NOSE_AIM_CONE_DEG = 4.0;

    /**
     * The pilot's held weapon leaves along the nose: unguided cannon and rockets as much as the
     * wire-guided missile above. The 35 deg NPC cone floor exists for turrets with splash at tank
     * ranges; on a nose-fixed gun it fired rounds 20-29 deg high at 60-100 m (a live trace: 14 shots,
     * no hits). These fire only with the nose within SBW's own 4 deg AI gate.
     */
    private boolean noseAimed() {
        if (this.vehicle.getFirstPassenger() != this.unit || this.heldWeaponSlot < 0) return false;
        return noseBeamRider() || !HeliArmament.isGuidedProjectile(
                HeliArmament.readSignals(this.vehicle, this.vehicle.getSeatIndex(this.unit), this.heldWeaponSlot).projectileId());
    }

    private boolean noseBeamRider() {
        return this.vehicle.getFirstPassenger() == this.unit
                && VehicleMissileAim.modeOfSelected(this.vehicle, this.unit) == VehicleMissileAim.AimMode.BEAM_RIDER;
    }

    /** The cone the fire assist actually applies this tick (trace column). */
    private double effectiveConeDeg() {
        return noseAimed() ? NOSE_AIM_CONE_DEG : VehicleWeapons.npcAssistConeDeg(fireConeDeg());
    }

    private double fireConeDeg() {
        double base = EasyMode.aiFireAssistConeDeg();
        double floor = com.neoalive.tacz_sewv.compat.NpcVehicleOverrides.heliConeFloorDeg(this.vehicle);
        return Math.max(base, floor);
    }

    private void fireAssist(LivingEntity target) {
        // A nose-aimed weapon is judged against its lead point (target motion over the time of flight,
        // plus drop), the same point the bunt pitches and yaws the nose onto.
        Vec3 aim = noseAimed() ? aimPoint(target) : null;
        VehicleWeapons.FireGate gate = aim != null
                ? VehicleWeapons.tryAiFireAssistResult(this.vehicle, this.unit, target, aim, NOSE_AIM_CONE_DEG, false)
                : VehicleWeapons.tryAiFireAssistResult(this.vehicle, this.unit, target, fireConeDeg());
        if (HeliTrace.tracing(this.vehicle)) {
            HeliTrace.noteFire(this.vehicle, gate == VehicleWeapons.FireGate.FIRED, gate.name(),
                    VehicleWeapons.boresightAngleDeg(this.vehicle, this.unit, target, aim),
                    effectiveConeDeg(),
                    !((IAiFireTracker) this.vehicle).tacz_sewv$lineOfFireBlocked(this.unit, target));
        }
        if (!ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG) || gate == VehicleWeapons.FireGate.RPM_WAIT) return;
        LOGGER.info("[sewv heli] {}#{} {} slot={} target={}", this.vehicle.getName().getString(),
                this.vehicle.getId(), gate == VehicleWeapons.FireGate.FIRED ? "FIRE" : "NOFIRE gate=" + gate,
                this.heldWeaponSlot, target.getId());
    }

    /** Keep a firing pass on the target it began on (Phase 6) while that target is alive and in reach. */
    private static final double COMMIT_RANGE = GUIDED_RANGE + 40.0;
    @Nullable
    private LivingEntity committed;

    /**
     * Target commitment for the length of a pass. SEM's scan and retaliation goals swap a crew's target
     * every few seconds in a mixed fight; a run re-aimed at every swap never reaches its window (a
     * live trace: 30 target changes in 70 s, no shot). During INGRESS/ATTACK the committed target is
     * re-asserted; the swap takes effect when the pass ends. A veto (setTarget refused) releases it.
     */
    @Nullable
    private LivingEntity committedTarget(@Nullable LivingEntity current) {
        FirePhase ph = this.runtime.firePhase();
        boolean inPass = ph == FirePhase.INGRESS || ph == FirePhase.ATTACK;
        LivingEntity c = this.committed;
        if (inPass && c != null && c != current && c.isAlive() && !c.isRemoved()
                && c.distanceToSqr(this.vehicle) < COMMIT_RANGE * COMMIT_RANGE) {
            this.unit.setTarget(c);
            if (this.unit.getTarget() == c) return c;
        }
        this.committed = current;
        return current;
    }

    /** Where the held weapon must be pointed to meet {@code target}: lead over the time of flight, plus drop. */
    private Vec3 aimPoint(LivingEntity target) {
        Entity mover = target.getVehicle() != null ? target.getVehicle() : target;
        Vec3 c = mover.getBoundingBox().getCenter(), dm = mover.getDeltaMovement(), from = this.vehicle.getShootPos(this.unit, 1.0F);
        double vp = this.vehicle.getProjectileVelocity(this.unit) * 20.0, gp = this.vehicle.getProjectileGravity(this.unit) * 400.0;
        Vector3d l = Envelope.aimPoint(new Vector3d(from.x, from.y, from.z), new Vector3d(c.x, c.y, c.z),
                new Vector3d(dm.x * 20.0, mover.onGround() ? 0.0 : dm.y * 20.0, dm.z * 20.0), vp, gp);
        return new Vec3(l.x, l.y, l.z);
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

    private void clearWeaponHold() {
        this.heldWeaponSlot = -1;
        this.heldTargetId = Integer.MIN_VALUE;
    }

    // --- Rappel ----------------------------------------------------------------------------------

    /**
     * RU/US combat insertion: bring troops to a fight, then drop them near it. Fires when all of:
     * eligible cargo aboard; a live target within {@link #RAPPEL_INSERT_RADIUS}; that contact held
     * for {@link #RAPPEL_ENGAGE_DEBOUNCE_TICKS}; hull at least half health; past the post-rappel
     * cooldown.
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
            this.rappelEngageSince = Long.MIN_VALUE;
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) {
            this.rappelEngageSince = Long.MIN_VALUE;
            return;
        }
        double dx = target.getX() - this.vehicle.getX();
        double dz = target.getZ() - this.vehicle.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq > RAPPEL_INSERT_RADIUS * RAPPEL_INSERT_RADIUS) {
            this.rappelEngageSince = Long.MIN_VALUE;
            return;
        }
        if (this.rappelEngageSince == Long.MIN_VALUE) {
            this.rappelEngageSince = now;
            return;
        }
        if (now - this.rappelEngageSince < RAPPEL_ENGAGE_DEBOUNCE_TICKS) return;

        this.rappelEngageSince = Long.MIN_VALUE;
        setRappelRequested(this.vehicle, true);
        if (ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG)) {
            LOGGER.info("[sewv heli] {}#{} autonomous rappel (target=#{} dist={})",
                    this.vehicle.getName().getString(), this.vehicle.getId(), target.getId(),
                    String.format(java.util.Locale.ROOT, "%.0f", Math.sqrt(distSq)));
        }
    }

    /** One RAPPEL sequence tick. {@code true} = keep holding station; {@code false} = teardown done. */
    private boolean rappelTick(Situation sit) {
        boolean requested = isRappelRequested(this.vehicle);
        if (!requested && this.runPhase == RunPhase.RAPPEL) {
            exitRappel("debug-off");
            return false;
        }
        if (requested && this.runPhase != RunPhase.RAPPEL) enterRappel();
        if (this.runPhase != RunPhase.RAPPEL) return false;

        long now = this.unit.level().getGameTime();
        if (now - this.rappelStartedAt >= RAPPEL_TIMEOUT_TICKS) {
            exitRappel("timeout");
            return false;
        }

        sit.order = OrderKind.RAPPEL;
        sit.lockX = this.rappelLockX;
        sit.lockZ = this.rappelLockZ;
        sit.rappelY = surfaceBelow() + RAPPEL_HOVER_AGL;

        if (!rappelHoverStable()) {
            this.rappelStableAt = Long.MIN_VALUE;
            return true;
        }
        if (this.rappelStableAt == Long.MIN_VALUE) this.rappelStableAt = now;
        if (now - this.rappelStableAt < RappelSupport.SETTLE_TICKS) return true;

        rappelStartEligible();
        // Done when nobody eligible remains aboard and both ropes are clear.
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

    private void enterRappel() {
        this.rappelLockX = this.vehicle.getX();
        this.rappelLockZ = this.vehicle.getZ();
        this.rappelStartedAt = this.unit.level().getGameTime();
        this.rappelStableAt = Long.MIN_VALUE;
        this.rappelAtIssued = 0;
        setRunPhase(RunPhase.RAPPEL);
    }

    /** Clear the request flag, then exit the phase. Active rope slides keep advancing until they land. */
    private void exitRappel(String reason) {
        setRappelRequested(this.vehicle, false);
        clearForcedRappel(this.vehicle);
        if (ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG) && this.vehicle != null) {
            LOGGER.info("[sewv heli] {}#{} rappel teardown reason={} ropesIdle={}",
                    this.vehicle.getName().getString(), this.vehicle.getId(), reason, rappelRopesIdle());
        }
        if (this.runPhase == RunPhase.RAPPEL) setRunPhase(RunPhase.IDLE);
        this.rappelLockX = Double.NaN;
        this.rappelLockZ = Double.NaN;
        this.rappelStartedAt = Long.MIN_VALUE;
        this.rappelStableAt = Long.MIN_VALUE;
        this.rappelEngageSince = Long.MIN_VALUE;
        this.rappelAtIssued = 0;
        this.rappelAutonomousCooldownUntil = this.unit.level().getGameTime() + RAPPEL_AUTONOMOUS_COOLDOWN_TICKS;
    }

    private void clearRappelState() {
        this.rappelLockX = Double.NaN;
        this.rappelLockZ = Double.NaN;
        this.rappelStartedAt = Long.MIN_VALUE;
        this.rappelStableAt = Long.MIN_VALUE;
    }

    /** Kick eligible cargo onto free ropes (one per side). Pilot/gunners stay aboard. */
    private void rappelStartEligible() {
        if (this.rappelRopeMinusId < 0) tryStartRope(false);
        if (this.rappelRopePlusId < 0) tryStartRope(true);
    }

    private void tryStartRope(boolean plusX) {
        for (Entity passenger : List.copyOf(this.vehicle.getPassengers())) {
            if (!RappelSupport.isRappelEligible(this.vehicle, passenger)) continue;
            if (!(passenger instanceof AbstractUnit rider)) continue;
            int id = rider.getId();
            if (id == this.rappelRopeMinusId || id == this.rappelRopePlusId) continue;

            // Same AT issue seam as DriveVehicleGoal.dismountSquad — first always, second rolls.
            if (this.rappelAtIssued == 0 || (this.rappelAtIssued < EasyMode.maxAtGunners()
                    && rider.getRandom().nextDouble() < EasyMode.atSecondGunnerChance())) {
                if (SmallArmsSupport.issueAtWeapon(rider)) this.rappelAtIssued++;
            }

            Vec3 top = RappelSupport.startRope(rider, this.vehicle, plusX);
            if (plusX) {
                this.rappelRopePlusId = id;
                this.rappelRopePlusAx = top.x;
                this.rappelRopePlusAz = top.z;
            } else {
                this.rappelRopeMinusId = id;
                this.rappelRopeMinusAx = top.x;
                this.rappelRopeMinusAz = top.z;
            }
            return;
        }
    }

    /** Advance any in-progress rope slides (committed — survives RAPPEL teardown). */
    private void rappelAdvanceDescents() {
        if (this.rappelRopeMinusId >= 0 && !advanceRope(false)) {
            this.rappelRopeMinusId = -1;
            this.rappelRopeMinusAx = Double.NaN;
            this.rappelRopeMinusAz = Double.NaN;
        }
        if (this.rappelRopePlusId >= 0 && !advanceRope(true)) {
            this.rappelRopePlusId = -1;
            this.rappelRopePlusAx = Double.NaN;
            this.rappelRopePlusAz = Double.NaN;
        }
    }

    /** @return true while still descending */
    private boolean advanceRope(boolean plusX) {
        int id = plusX ? this.rappelRopePlusId : this.rappelRopeMinusId;
        double ax = plusX ? this.rappelRopePlusAx : this.rappelRopeMinusAx;
        double az = plusX ? this.rappelRopePlusAz : this.rappelRopeMinusAz;
        if (!(this.unit.level().getEntity(id) instanceof AbstractUnit rider)) return false;
        return RappelSupport.tickDescent(rider, ax, az);
    }

    private void setRunPhase(RunPhase next) {
        boolean changed = this.runPhase != next;
        this.runPhase = next;
        if (this.vehicle == null) return;
        this.vehicle.getPersistentData().putString(TAG_HELI_RUN_PHASE, this.runPhase.name());
        if (this.vehicle.level() instanceof ServerLevel) {
            NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.vehicle),
                    new PacketHeliRunPhase(this.vehicle.getId(), this.runPhase.ordinal()));
        }
        if (changed && ClientConfig.flag(ClientConfig.HELI_COMBAT_DEBUG)) {
            LOGGER.info("[sewv heli] {}#{} phase={}", this.vehicle.getName().getString(), this.vehicle.getId(), this.runPhase);
        }
    }

    // --- Landing helpers ---------------------------------------------------------------------------

    // Touchdown -> sticky LANDED; the hull stays down until a new takeoff order.
    private void settleLanded(IHelicopterPilot pilot) {
        pilot.sewv$setHeliCommand(IHelicopterPilot.HELI_CMD_LANDED);
        pilot.sewv$setHeliLandPos(null);
        clearForcedLand(this.vehicle);
    }

    // Feet-level Y the hull can actually sit at on the ordered block's column: walk up the
    // contiguous solid stack above the pick (bounded).
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

    // --- Altitudes -------------------------------------------------------------------------------

    // Terrain-relative cruise level over the hull's own column.
    private double cruiseAltitudeHere() {
        return AirframeSupport.cruiseAltitudeHere(this.vehicle, flightAltitude());
    }

    // Terrain-relative cruise level for a leg toward (toX, toZ): the offset above the HIGHEST
    // ground between here and there.
    private double cruiseAltitudeToward(double toX, double toZ) {
        boolean far = AirLod.farTransit(this.vehicle, SewvConfig.HELI_FAR_LOD_BLOCKS.get(),
                this.unit.getTarget() == null);
        return AirframeSupport.cruiseAltitudeToward(
                this.vehicle, toX, toZ, flightAltitude(), TERRAIN_LOOKAHEAD, AirLod.groundTtl(far));
    }

    // The pilot's own live cruise altitude, hard-clamped to the 30-50 band.
    private double flightAltitude() {
        int alt = (this.unit instanceof IHelicopterPilot pilot)
                ? pilot.sewv$getCruiseAltitude() : IHelicopterPilot.DEFAULT_CRUISE_ALTITUDE;
        return Mth.clamp(alt, MIN_FLIGHT_ALT, MAX_FLIGHT_ALT);
    }

    private int surfaceBelow() {
        return AirframeSupport.surfaceBelow(this.vehicle);
    }
}
