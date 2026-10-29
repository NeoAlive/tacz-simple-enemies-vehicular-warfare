package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.compat.AshMissileSupport;
import com.neoalive.tacz_sewv.compat.TripodCompat;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.command.Assignment;
import com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment;
import com.neoalive.tacz_sewv.entity.ai.command.TaskedDestination;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.StalemateBreaker;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleDriver;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.cover.BattlePosition;
import com.neoalive.tacz_sewv.entity.ai.formation.GroundSnap;
import com.neoalive.tacz_sewv.entity.ai.march.March;
import com.neoalive.tacz_sewv.entity.ai.march.MarchRegistry;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;
import com.neoalive.tacz_sewv.entity.ai.support.AirLod;
import com.neoalive.tacz_sewv.entity.ai.support.ArtillerySupport;
import com.neoalive.tacz_sewv.entity.ai.support.BailOutSupport;
import com.neoalive.tacz_sewv.entity.ai.support.DismountSequence;
import com.neoalive.tacz_sewv.entity.ai.support.DroneSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.IdleGroupSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressOrders;
import com.neoalive.tacz_sewv.entity.ai.support.SuppressiveFire;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.ai.support.TreeFellingSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleMortarSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalPosture;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalScale;
import com.neoalive.tacz_sewv.invasion.CaptureOrderSupport;
import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Drives a ground hull for its crew: where to be relative to the target, how to get there, and
 * which weapon to have selected when it arrives.
 *
 * <p>Flight is {@link DriveHelicopterGoal}'s job and floating is {@link DriveShipGoal}'s — all
 * three goals are registered on every crew and this one declines helicopters and ships in
 * {@link #canUse}.
 *
 * <p>This class decides and dispatches; it drives nothing itself. Steering, pathfinding and
 * stuck recovery are {@link VehicleDriver}; what to do about a target is
 * {@link com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain}; the recovery from a crew
 * holding a target it cannot hit is {@link StalemateBreaker}; weapon doctrine is
 * {@link VehicleWeapons} and destination resolution is {@link VehicleTargeting}.
 */
public class DriveVehicleGoal extends Goal {

    private static final int WEAPON_SWITCH_COOLDOWN_TICKS = 5;

    // The standoff rings themselves now live in Facts.preferredRange / Facts.rangeDeadband,
    // because the scorer needs the same numbers to decide whether we are too close or too far
    // and two copies would drift. Infantry is a 10-20 band (15 ± 5); armor holds the far ring
    // at 40 ± 8, because a tank duel that collapses to point-blank is a tank duel getting lost.

    // Self-preservation: a crew breaking contact falls back past the standoff ring rather than
    // to it, so the retreat actually opens distance instead of stopping where it started.
    private static final double PRESERVE_RETREAT_MARGIN = 8.0;

    // The health fraction at which a hull is written off — used by PatrolSupport's mutual
    // support and by the ally-assist scan. The crew's OWN decision to break off is no longer a
    // threshold at all: it is the retreat action outscoring the rest (see TacticalBrain).
    private static final float PRESERVE_HEALTH_FRACTION = 0.25F;

    // How many of a dismounting squad may carry an anti-tank launcher. Hard-capped rather than
    // configurable: the point is a couple of AT men supporting riflemen, and a squad that is
    // ALL launchers is a different (and much sillier) unit.
    // How far around the ring a deliberate flank aims for. Matches StalemateBreaker's own orbit
    // step: far enough to reach genuinely different ground and a different facing on the target,
    // but not so far that the arc sweeps through the enemy on the way round.
    private static final double FLANK_ARC_RAD = Math.toRadians(60.0);

    private final AbstractUnit unit;
    private final HullFacts hull = new HullFacts();
    private final StalemateBreaker breaker;
    /** Decides what this crew does about its target. See {@link #fightTick}. */
    private final TacticalBrain brain = new TacticalBrain();
    /** Individual tactics posture (cover / scoot / ambush). Soft biases only under orders. */
    private final TacticalPosture posture = new TacticalPosture();
    // Mutual support scanner (idle crew reinforces an allied crew in combat), shared with
    // DriveHelicopterGoal via VehicleTargeting.
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();
    /** Merges foliage contacts + heard gunfire into Facts investigate fields. */
    private final AwarenessCues awareness = new AwarenessCues();
    /** Whether this crew already reached its command-tier role point. See {@link #taskedDestination}. */
    private final TaskedDestination.Latch taskLatch = new TaskedDestination.Latch();
    /** Last tasked-destination state logged, so the individual debug line fires on change only. */
    private boolean taskDriving;
    /** Game time the run-start smoke volley's decoy hold ends; {@link Long#MIN_VALUE} = none open. */
    private long smokeUntil = Long.MIN_VALUE;
    /** Long enough for SBW to register the held decoy input and fire one volley. */
    private static final long RUN_SMOKE_TICKS = 10;
    /** Blocks past the hull's half-width at which a posture waypoint counts as reached. */
    private static final double POSTURE_ARRIVE_PAD = 3.0;
    /**
     * A marching hull steers at a point this far down the road from its slot, never at the slot: the slot moves,
     * and steering at a moving point the hull has nearly reached reads as arrived — park, fall behind, lunge.
     */
    private static final double MARCH_CARROT = 10.0;
    /** Speed cap as a multiple of the march pace: crawl when ahead of the slot, hurry when behind it. */
    private static final double MARCH_PACE_MIN = 0.4;
    private static final double MARCH_PACE_MAX = 1.8;

    /** Everything about actually making the hull go somewhere. See {@link VehicleDriver}. */
    private final VehicleDriver driver;
    /** IFV squad dismount: halt, staggered rear-corner exit, scramble. See {@link #maybeBeginDismount}. */
    private final DismountSequence dismount = new DismountSequence();

    private VehicleEntity vehicle;

    private int weaponSwitchCooldown;
    // Which ROLE the last selection picked (VehicleWeapons.WEAPON_*), or UNCLASSIFIED.
    // Cached because getWeaponIndex() can't answer this — see selectWeaponForTarget.
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;
    /** Standoff Schmitt state: 0 hold, 1 closing, -1 opening. See {@link #maintainVehicleStandoff}. */
    private byte standoffPhase;
    /** Backing off a tripod: released only past standoff + margin, so an idle hull can't hover on the line. */
    private boolean tripodRetreat;
    /** Throttle for posture override logs (game time of last line). */
    private long lastPostureSteerLog = Long.MIN_VALUE;
    /** Previous scored/idle plan — edge-detect RU/US order_dispatch. */
    private Action lastSpokenPlan = Action.HOLD;
    /** Plan waiting out the min-hold window before order_dispatch. */
    @javax.annotation.Nullable private Action pendingDispatchPlan;
    private long pendingDispatchSince = Long.MIN_VALUE;
    private static final int PLAN_DISPATCH_HOLD_TICKS = 30;

    public DriveVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.driver = new VehicleDriver(unit, this.hull);
        this.breaker = new StalemateBreaker(unit);
        this.setFlags(EnumSet.noneOf(Flag.class)); // driving doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) drives — enforces the driver/commander model.
        if (v.getFirstPassenger() != this.unit) return false;

        this.hull.attach(v);
        // Flight and water hulls have their own goals; a fixed-wing plane must be excluded here too,
        // or this ground goal and DrivePlaneGoal both fire on it (isHelicopter() is false for planes).
        if (this.hull.isHelicopter() || this.hull.isPlane() || this.hull.isShip()) return false;
        // Visible to the command tier even while this goal is stopped — see CommandCoordinator.SEATED_DRIVERS.
        com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator.noteSeatedDriver(this.unit.getId());
        // ASH Sapsan (and similar): ManMissileSystemGoal owns MOVE while engaging.
        if (this.hull.isMissileSystem() && AshMissileSupport.shouldEngage(this.unit)) return false;
        // Artillery: ManArtilleryGoal lays and fires — do not close on the designation.
        if (this.hull.isArtillery() && ArtillerySupport.hasFireWork(this.unit)) return false;
        // FCP vehicle mortar: park while the gunner lays/fires.
        if (VehicleMortarSupport.shouldPark(v)) return false;

        this.vehicle = v;
        this.driver.attach(v);
        this.breaker.attach(v);
        if (TowRecoverySupport.hasTowOrder(this.unit)) return true;
        if (seekCoverWantsDrive()) return true;
        if (SuppressOrders.active(this.unit, this.unit.level().getGameTime()) != null) return true;
        return getTargetPos() != null; // only drive if there's somewhere to go
    }

    @Override
    public boolean canContinueToUse() {
        if (this.hull.isMissileSystem() && AshMissileSupport.shouldEngage(this.unit)) return false;
        if (this.hull.isArtillery() && ArtillerySupport.hasFireWork(this.unit)) return false;
        if (this.vehicle != null && VehicleMortarSupport.shouldPark(this.vehicle)) return false;
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                // A started dismount is committed, like a rope slide: the squad finishes getting
                // out even if the target that triggered it dies mid-sequence.
                && (this.dismount.active()
                        || TowRecoverySupport.hasTowOrder(this.unit)
                        || seekCoverWantsDrive()
                        || SuppressOrders.active(this.unit, this.unit.level().getGameTime()) != null
                        || getTargetPos() != null);
    }

    // The stuck detector, retreat-episode detection and the steering ramp all assume one
    // tick() per game tick; vanilla only ticks running goals every OTHER tick.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        // The decoy input is latched vehicle state (releasing the steering inputs doesn't touch
        // it), so a crew leaving mid-retreat must let go of it here or the launcher keeps
        // volleying smoke forever.
        this.lastSpokenPlan = Action.HOLD;
        this.pendingDispatchPlan = null;
        this.pendingDispatchSince = Long.MIN_VALUE;
        this.dismount.abort(this.unit.level().getGameTime());
        this.driver.stop();
        this.vehicle.setDecoyInputDown(false);
        this.vehicle = null;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
        this.standoffPhase = 0;
        this.allyAssist.clear();
        this.driver.clear();
        this.breaker.clear();
        // taskLatch / taskDriving deliberately survive a stop: the goal stops whenever the crew is briefly
        // between targets with nowhere to go, and resetting them re-ran the run start (log line + smoke volley)
        // on the same point. The latch forgets on its own when the role, side or point changes.
        this.smokeUntil = Long.MIN_VALUE;
        this.awareness.clear();
        this.brain.facts().unbind(this.unit);
        this.brain.clear();
        this.posture.clear();
        TacticalPosture.clearUnit(this.unit.getId());
    }

    /**
     * {@code groundFarLodBlocks}: an idle hull (no target, no standing order) with no player within the radius
     * runs cheaper — lowest path priority and doubled sensor/tree intervals. Cheapest test first; the
     * player check is AirLod's 10-tick cached sample.
     */
    private boolean farIdle() {
        double lod;
        try {
            lod = SewvConfig.GROUND_FAR_LOD_BLOCKS.get();
        } catch (Throwable unbaked) {
            return false;
        }
        if (lod <= 0.0 || this.unit.getTarget() != null) return false;
        if (AirLod.nearPlayers(this.vehicle, lod)) return false;
        return !VehicleTargeting.underStandingOrder(this.unit);
    }

    @Override
    public void tick() {
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        // A run-start smoke window (see taskedDestination) is closed here, ahead of every early return, so a
        // posture or tow branch taking the tick can never leave the latched decoy input held.
        if (this.smokeUntil != Long.MIN_VALUE && this.unit.level().getGameTime() >= this.smokeUntil) {
            this.vehicle.setDecoyInputDown(false);
            this.smokeUntil = Long.MIN_VALUE;
        }
        this.driver.tickTimers();
        // The speed cap is re-asserted by whichever branch wants it, every tick; nothing inherits last tick's.
        this.driver.setSpeedCap(Double.NaN);
        boolean farIdle = farIdle();
        this.driver.setFarIdle(farIdle);
        TreeFellingSupport.tick(this.unit, this.vehicle, this.hull, farIdle);

        if (this.vehicle.getFirstPassenger() == this.unit) {
            TowRecoverySupport.tickDriverStrandedBroadcast(this.vehicle);
        }

        if (TowRecoverySupport.isTowering(this.unit, this.vehicle)) {
            // Tow early-return skips the per-tick decoy release below — release here or a smoke
            // latch from before the tow order keeps volleying forever.
            this.vehicle.setDecoyInputDown(false);
            towRecoveryTick();
            return;
        }

        // Spots before re-score so DISTANT_CONTACT sees this tick's outer fields. noteSpot is
        // gated on getTarget()==null; observe (inside update) still owns Memory when locked.
        // facts.underOrders here may be one Facts-refresh stale; acceptable. clearFacts must
        // never wipe Memory.
        this.awareness.tick(this.unit, this.vehicle, this.brain.facts());
        // Re-read the battlefield and, on its own ~1s cadence, re-decide. Cheap on the ticks it
        // does nothing, which is most of them.
        this.brain.update(this.unit, this.vehicle, this.posture);
        HudNotify.watchPmcVehicle(this.unit, this.vehicle);

        LivingEntity target = this.unit.getTarget();

        // Tripod Invasion: RU/US never sit inside a tripod's standoff. Ahead of the scorer and every
        // posture steer, like the stalemate breaker: no datapack weight may park a hull under a heat ray.
        if (tripodStandoffTick(target)) return;

        // An IFV halts and puts its squad out against ARMOUR. Ahead of the posture steers on
        // purpose: once the halt has started, nothing may drive the hull off until the last man
        // is out. Deliberately NOT screened with smoke first: it would fire on every such contact,
        // and the battlefield would end up permanently fogged.
        if (this.dismount.active() || maybeBeginDismount(target)) {
            dismountTick(target);
            return;
        }

        // The player's Suppress Area order outranks the crew's own posture and plan, short of
        // self-defence (see SuppressOrders).
        if (orderedSuppressTick()) return;

        // Posture hard steers on the main tick so area-hold / lost-target still honour them.
        if (trySteerSeekCover()) return;
        if (trySteerPostureManeuvers()) return;

        Action plan = idlePlan();
        maybePlanDispatch(plan);

        // Tank-rider ("Climb" pose) seats: a utilization fix, not an anti-armor tactic, so it
        // triggers on ANY held target rather than being armor-gated like the IFV dismount above —
        // see dismountClimbers.
        if (target != null && !this.hull.climbSeats().isEmpty()) {
            dismountClimbers();
        }

        // The decoy input is latched vehicle state: release it on every tick the crew is not
        // actively screening (preserveRetreat re-asserts it immediately after), otherwise one
        // retreat would leave the launcher volleying a fresh smoke salvo every reload, forever.
        if ((target == null || this.brain.plan() != Action.DEPLOY_SMOKE)
                && this.unit.level().getGameTime() >= this.smokeUntil) {
            this.vehicle.setDecoyInputDown(false);
        }

        // An area task (patrol / S&D / sweep / cruise) does not yield the wheel to a contact:
        // the ordered ground is the player's standing instruction and the crew fights from it.
        // Everything the fight needs that is NOT movement still runs — weapon choice and the fire
        // assist — and the hull then carries straight on to its leg below. A badly hurt crew is
        // the exception: that is not "engaging", it is dying, and preserveRetreat inside fightTick
        // still wins. (Same shape as the old cruise-only exception; extended so Sweep & Advance
        // / S&D / patrol stop abandoning the area to chase every nearby mob.)
        //
        // A MOVE click, FOB route, FOLLOW, and FORM are NOT subject to that health exception. They
        // are locomotion the player still owns, and the whole reason to keep them while hurt is to
        // get the hull out with the commander — handing a damaged crew to fightTick made it hold a
        // standoff ring on the enemy instead. Retreat is the order; fightTick's version goes nowhere.
        //
        // Pure combat steers off the live target inside fightTick — resolveDestination is unused
        // there, so skip getTargetPos unless an ordered move / area hold needs the standing dest.
        if (target != null) {
            boolean captureHold = CaptureOrderSupport.holdsCourseThroughContact(this.unit);
            boolean orderedMove = VehicleTargeting.holdsOrderedMove(this.unit);
            // Command-tier play (envelopment / flank / BoF): honour the assignment in fightTick
            // so invasion fleets still get advanced tactics; capture destination resumes via
            // resolveDestination when the contact ends. Untasked capture crews hold the approach.
            boolean tasked = com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment.of(this.unit.getId()) != null;
            boolean areaHold = (PatrolSupport.holdsCourseThroughContact(this.unit)
                    || (captureHold && !tasked)) && !isLowHealth();
            if (!orderedMove && !areaHold) {
                fightTick(target);
                return;
            }

            BlockPos targetPos = getTargetPos();
            if (targetPos == null) {
                this.driver.stop();
                this.driver.clearRecovery();
                return;
            }
            if (captureHold && this.unit.level() instanceof ServerLevel sl
                    && sl.getGameTime() % 40L == 0L) {
                com.neoalive.tacz_sewv.debug.SewvDiag.invasion(
                        "captureHoldUnderFire unit={} target={} dest={}",
                        this.unit.getId(), target.getId(), targetPos);
            }
            selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
            fireAssistIfSpecial(target);
            standDownTick(targetPos);
            return;
        }

        BlockPos targetPos = getTargetPos();
        if (targetPos == null) {
            this.driver.stop();
            this.driver.clearRecovery(); // no task — nothing to be stuck against
            return;
        }

        standDownTick(targetPos);
    }

    /**
     * Out of contact: carry out whatever the crew has decided to do with its quiet time.
     *
     * <p>The counterpart to {@link #fightTick}, and the reason a crew with nothing to shoot at is
     * not simply a parked hull. The plan can only ever be one of the no-target actions — the
     * scorer's feasibility gate makes the two sets disjoint — and every one of them still ends in
     * a real steering instruction.
     *
     * <p>{@code standing} is where {@link VehicleTargeting#resolveDestination} says to go: an
     * order, a patrol leg, or the idle wander. While a player order stands, that is the only
     * option the scorer is offered, so a crew can never wander off an instruction.
     */
    private void standDownTick(BlockPos standing) {
        // Same rule as getTargetPos: a MOVE click or a FOB route is worked as given. HOLD would
        // park the hull short of it and the three substituting plans would send it somewhere else
        // entirely — which is how a crew ordered home ended up drifting back toward the fight.
        if (VehicleTargeting.holdsOrderedMove(this.unit)) {
            driveTo(standing);
            return;
        }
        if (idlePlan() == Action.IDLE_TRAVEL && marchTick()) return;
        if (idlePlan() == Action.SUPPRESS && suppressTick()) return;
        // A line crew with nothing in its sights yet still walks the line: the advance is the group's decision.
        if (inCohesiveLine() && marchTick()) return;
        BlockPos destination = switch (idlePlan()) {
            // SUPPRESS lands here only when the gun cannot rake the spot after all (claim lost, gunner
            // gone): go and look instead, rather than sit out the plan.
            case SEARCH_LAST_KNOWN, SUPPRESS -> {
                BlockPos seen = this.brain.facts().memory.lastEnemyPos;
                yield seen != null ? seen : standing;
            }
            case REGROUP -> {
                AbstractUnit ally = this.brain.facts().nearestAlly;
                yield ally != null ? ally.blockPosition() : standing;
            }
            case HOLD -> null;
            case IDLE_HOLD -> IdleGroupSupport.holdDestination(
                    this.unit, this.vehicle, this.brain.facts());
            case IDLE_TRAVEL -> null; // a march that ended between the plan and this tick: hold until re-scored
            // PATROL, and anything the scorer somehow let through: work the standing destination.
            default -> standing;
        };

        if (destination == null) {
            this.driver.stop();
            this.driver.clearRecovery(); // holding on purpose — not stuck
            return;
        }
        driveTo(destination);
    }

    /**
     * Put fire on the player's ordered point: hold the hull and work the machine gun on it. False with
     * no live order, in self-defence, or when no seat can fire on it, so the tick goes on as normal.
     */
    private boolean orderedSuppressTick() {
        long now = this.unit.level().getGameTime();
        BlockPos point = SuppressOrders.active(this.unit, now);
        if (point == null || SuppressOrders.selfDefence(this.unit, SuppressOrders.CREW_SELF_DEFENCE)) return false;
        int seat = SuppressiveFire.machineGunSeat(this.vehicle, now);
        if (!SuppressiveFire.tick(this.unit, this.vehicle, seat, point, now, true)) return false;
        // This early return skips the per-tick decoy release below.
        if (now >= this.smokeUntil) this.vehicle.setDecoyInputDown(false);
        this.driver.stop();
        this.driver.clearRecovery(); // holding on purpose — not stuck
        return true;
    }

    /**
     * Hold and rake the spot the target vanished from ({@link SuppressiveFire}). The hold is a real
     * instruction every tick, like every other branch. False once the window has passed or the gun
     * cannot rake it, which hands the tick to the search fallback.
     */
    private boolean suppressTick() {
        Facts facts = this.brain.facts();
        BlockPos spot = facts.memory.lastEnemyPos;
        long now = this.unit.level().getGameTime();
        if (spot == null || Facts.ticksSince(facts.memory.lastContactTick, now) > SuppressiveFire.WINDOW_TICKS) {
            return false;
        }
        if (!SuppressiveFire.tick(this.unit, this.vehicle, facts.suppressSeat, spot, now, false)) return false;
        this.driver.stop();
        this.driver.clearRecovery(); // holding on purpose — not stuck
        return true;
    }

    /**
     * Keep this hull's place in its march. Moving: steer down the road ahead of the slot with the speed held to
     * the march pace (faster when behind, slower when ahead), so the column moves at a walk however fast its
     * hulls are. Waiting: a straggler closes onto its slot, everyone else stops where they stand. Every branch
     * issues input.
     *
     * @return false when this crew is not marching a hull slot, so the caller falls back to its plan
     */
    private boolean marchTick() {
        MarchRegistry.Slot slot = MarchRegistry.slotFor(this.unit.getId(), this.unit.level().getGameTime());
        if (slot == null || !slot.hull()) return false;
        if (slot.paused()) {
            if (slot.caughtUp(this.vehicle.getX(), this.vehicle.getZ())) {
                // Not the one being waited for: stand where it is. Re-closing onto the exact slot and squaring up
                // on every pause is what had the whole column shuffling on the spot.
                this.driver.stop();
                this.driver.clearRecovery(); // waiting for the column — not stuck
            } else {
                BlockPos dest = BlockPos.containing(slot.x(),
                        GroundSnap.groundY(this.unit.level(), slot.x(), slot.z(), this.vehicle.getY()), slot.z());
                this.driver.navigateTo(dest, this.vehicle.distanceToSqr(Vec3.atBottomCenterOf(dest)));
            }
            return true;
        }
        double lag = slot.lag(this.vehicle.getX(), this.vehicle.getZ());
        this.driver.setSpeedCap(slot.pace()
                * Math.max(MARCH_PACE_MIN, Math.min(MARCH_PACE_MAX, 1.0 + lag / slot.spacing())));
        double cx = slot.x() + slot.fx() * MARCH_CARROT;
        double cz = slot.z() + slot.fz() * MARCH_CARROT;
        BlockPos carrot = BlockPos.containing(cx, GroundSnap.groundY(this.unit.level(), cx, cz, this.vehicle.getY()), cz);
        this.driver.navigateTo(carrot, this.vehicle.distanceToSqr(Vec3.atBottomCenterOf(carrot)));
        return true;
    }

    private boolean inCohesiveLine() {
        return MarchRegistry.kindOf(this.unit.getId()) == March.Kind.COHESIVE_LINE;
    }

    /** Steer for {@code destination}, or park on station once inside the arrival ring. */
    private void driveTo(BlockPos destination) {
        driveTo(destination, null);
    }

    /**
     * As {@link #driveTo(BlockPos)}, arriving onto {@code facing} (null: the formation axis, if
     * any — today's only arrival heading). Existing call sites use the 1-arg form; this overload is
     * the seam for a caller that wants an arrival heading of its own.
     */
    private void driveTo(BlockPos destination, Vec3 facing) {
        double distanceSq = this.vehicle.distanceToSqr(
                destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);

        // Arrival is measured HORIZONTALLY, though distanceSq stays 3D for navigateTo (which only
        // feeds it to the steering ramp). A formation slot's Y is a terrain probe, so a hull
        // parked on a rise a few blocks above its slot must still read as arrived — against the
        // tight formation tolerance a 3D test would leave it grinding at the slot forever.
        double dx = destination.getX() + 0.5 - this.vehicle.getX();
        double dz = destination.getZ() + 0.5 - this.vehicle.getZ();
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.vehicle);
        if (dx * dx + dz * dz > arrive * arrive) {
            this.driver.navigateTo(destination, distanceSq);
        } else {
            parkOnStation(facing);
            this.driver.clearRecovery(); // parked at destination (the driver's arrival state survives it)
            tryPromoteReachGuard();
        }
    }

    /**
     * REACH_GUARD: after MOVE parks, flip to HOLD_POSITION. No other arrival uses this path.
     */
    private void tryPromoteReachGuard() {
        if (!GuardSupport.isReaching(this.unit)) return;
        if (!(this.unit instanceof PmcUnitEntity pmc)) return;
        GuardSupport.clearReach(pmc);
        pmc.setOrder(OrderType.HOLD_POSITION);
    }

    /**
     * RU/US order_dispatch on scored plan change. Tier B: requires the new plan to hold
     * {@link #PLAN_DISPATCH_HOLD_TICKS} before speaking so ATTACK↔FLANK thrash does not spam.
     */
    private void maybePlanDispatch(Action plan) {
        if (!(this.unit instanceof RUunitEntity) && !(this.unit instanceof USunitEntity)) return;
        if (this.vehicle == null || plan == null) return;
        long now = this.unit.level().getGameTime();
        if (plan == this.lastSpokenPlan) {
            this.pendingDispatchPlan = null;
            this.pendingDispatchSince = Long.MIN_VALUE;
            return;
        }
        if (plan != this.pendingDispatchPlan) {
            this.pendingDispatchPlan = plan;
            this.pendingDispatchSince = now;
            return;
        }
        if (now - this.pendingDispatchSince < PLAN_DISPATCH_HOLD_TICKS) return;
        CrewRadio.play(this.vehicle, CrewRadio.Line.ORDER_DISPATCH_PLAN);
        this.lastSpokenPlan = plan;
        this.pendingDispatchPlan = null;
        this.pendingDispatchSince = Long.MIN_VALUE;
    }

    /**
     * Parked at the destination. Under a formation order that means holding the frozen axis, so
     * the wedge points where it was pointed and every hull's frontal armor and gun face the same
     * way; any other order has no heading to hold and simply stops. A tracked hull pivots onto it;
     * a wheeled one shuffles onto it (bounded) — see {@link VehicleDriver#holdStation}.
     */
    private void parkOnStation(Vec3 facing) {
        this.driver.holdStation(facing != null ? facing : VehicleTargeting.formationForward(this.unit));
    }

    /**
     * Carry out whatever the crew has decided to do about its target.
     *
     * <p>This is the Action layer and nothing else: the choice was made by {@link TacticalBrain}
     * from {@link com.neoalive.tacz_sewv.entity.ai.utility.Facts} and the commander's
     * {@link com.neoalive.tacz_sewv.entity.ai.utility.Doctrine}, and no tactical reasoning happens
     * here beyond the geometry each action needs. Adding a behaviour means adding an
     * {@link Action} and a weight block, not another branch in a chain.
     *
     * <p>Anchored to the TARGET, not the resolved order destination — FREE_FIRE / ATTACK and
     * other fight-owning orders must not hold a standoff around a non-enemy point. FOLLOW / FORM /
     * MOVE never reach here while healthy: {@link VehicleTargeting#holdsOrderedMove} keeps them on
     * the standing destination with fire assist only.
     *
     * <p>Every branch issues steering input on every tick. SuperbWarfare ramps a tracked hull's
     * turn rate only while a steering input stays held, so an action that simply returned would
     * bring back the pivot-forever bug — {@link #stopVehicleMovement} is a real instruction, not
     * the absence of one.
     */
    private void fightTick(LivingEntity target) {
        maybePlanDispatch(this.brain.plan());

        BlockPos combatPos = target.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(
                combatPos.getX() + 0.5, combatPos.getY(), combatPos.getZ() + 0.5);
        double dist = Math.sqrt(distanceSq);

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        // The ring this crew wants to hold against this kind of target — the same number the
        // scorer measured its too-close/too-far signals against.
        double ring = Facts.preferredRange(this.unit, target, category);

        selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
        fireAssistIfSpecial(target);

        // The stalemate breaker outranks the scorer, and must: it is the watchdog for a crew
        // holding a target it physically cannot hit, and the actions that freeze the hull are
        // exactly the ones that look correct while it happens. A bad weight in a datapack must
        // not be able to bring back the park-forever bug this exists to kill. It is skipped
        // while retreating, where silence is success rather than a stall.
        Action plan = this.brain.plan();

        if (plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE) {
            BlockPos orbit = this.breaker.update(target, combatPos, ring);
            // A crew on a battle position answers the breaker by moving to its alternate, not by orbiting into the
            // open; with no alternate left the orbit runs exactly as before, so the watchdog still always wins.
            if (orbit != null && this.posture.answerBreaker(this.unit.level().getGameTime())
                    && occupyBattlePosition(target)) {
                return;
            }
            if (orbit != null) {
                this.brain.force(breakerGoesLeft() ? Action.FLANK_LEFT : Action.FLANK_RIGHT,
                        this.unit.level().getGameTime());
                // Going through navigateTo is deliberate: it restores the stuck detector and the
                // terrain sensor for the duration, so the breaker can't wedge the hull on the way
                // to ground it likes better.
                this.driver.navigateTo(orbit, distanceSq);
                return;
            }
        }

        // CohesiveLine: the group's play is the advance, so the line owns this hull's movement — its place in the
        // line, at the march pace, so the files behind keep up — while the turret fights whatever it chose. Gated
        // on ADVANCE alone, the hulls held ATTACK/HOLD on contact, never formed the line, and the line stalled.
        // Breaking off is still the crew's call: a retreat or a smoke screen is carried out as itself.
        if (inCohesiveLine() && plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE && marchTick()) return;

        // Command-tier role point for the action the scorer chose (flank mark, withdraw point, …). Drive
        // there first; once reached, the action's own geometry below takes over.
        BlockPos tasked = taskedDestination(plan);
        if (tasked != null) {
            this.driver.navigateTo(tasked, this.vehicle.distanceToSqr(
                    tasked.getX() + 0.5, tasked.getY(), tasked.getZ() + 0.5));
            return;
        }

        switch (plan) {
            case RETREAT -> this.driver.retreatFrom(combatPos,
                    ring + TacticalScale.of(PRESERVE_RETREAT_MARGIN), distanceSq);

            // Smoke is a screened withdrawal, not a standalone puff: the launcher fires along the
            // turret vector (already tracking the threat) while the hull falls back behind it.
            case DEPLOY_SMOKE -> preserveRetreat(target, category);

            case ADVANCE -> this.driver.navigateTo(combatPos, distanceSq);

            case FLANK_LEFT -> this.driver.navigateTo(
                    VehicleTargeting.computeStandoffPoint(this.vehicle, combatPos, ring, FLANK_ARC_RAD),
                    distanceSq);
            case FLANK_RIGHT -> this.driver.navigateTo(
                    VehicleTargeting.computeStandoffPoint(this.vehicle, combatPos, ring, -FLANK_ARC_RAD),
                    distanceSq);

            case HOLD -> {
                if (!occupyBattlePosition(target)) holdTaskedStation();
                this.driver.clearRecovery(); // holding on purpose — not stuck
            }

            // Calling for support takes no time and moves nothing, so these keep fighting exactly
            // as ATTACK would. A crew that stopped driving to use its radio would be a crew
            // standing still in the middle of a tank battle.
            case CALL_MORTARS -> {
                requestSupport(target, FireMissionSupport.Kind.MORTAR);
                if (!occupyBattlePosition(target)) maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case CALL_TOW -> {
                requestSupport(target, FireMissionSupport.Kind.TOW);
                if (!occupyBattlePosition(target)) maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case CALL_CAS -> {
                requestSupport(target, FireMissionSupport.Kind.CAS);
                if (!occupyBattlePosition(target)) maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case DELEGATE_TARGET -> {
                delegateTarget(target);
                if (!occupyBattlePosition(target)) maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }

            // Hold the standoff band for the target's type: close when beyond it, open when
            // inside it, sit still on it and let the turret work.
            case ATTACK -> {
                if (!occupyBattlePosition(target)) maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
        }
    }

    /**
     * Fight from the latched battle position ({@link TacticalPosture#battlePosition}): drive there, then hold it with
     * the front armour on the target while the turret works. False when there is none, and the caller keeps its own
     * geometry. Every branch issues input, like the rest of the dispatch.
     */
    private boolean occupyBattlePosition(LivingEntity target) {
        Vec3 bp = this.posture.battlePosition();
        if (bp == null) return false;
        double dx = bp.x - this.vehicle.getX();
        double dz = bp.z - this.vehicle.getZ();
        double arrive = BattlePosition.arriveRadius(this.vehicle);
        if (dx * dx + dz * dz > arrive * arrive) {
            this.driver.navigateTo(BlockPos.containing(bp), this.vehicle.distanceToSqr(bp));
            return true;
        }
        Vec3 facing = new Vec3(target.getX() - this.vehicle.getX(), 0.0, target.getZ() - this.vehicle.getZ());
        this.driver.holdStation(facing.lengthSqr() > 1.0E-4 ? facing.normalize() : null);
        this.driver.clearRecovery(); // on station — not stuck
        return true;
    }

    /**
     * HOLD on a play role's slot squares up to the role's facing — a firing line that parked at random angles
     * would show the enemy flanks and rears. Without a published facing it is the plain stop it always was.
     * {@code holdStation} is an input every tick (it stops once aligned), so this branch never goes silent.
     */
    private void holdTaskedStation() {
        CrewAssignment.Snapshot assign = CrewAssignment.of(this.unit.getId());
        if (assign != null && assign.hasFacing() && TaskedDestination.pointFor(assign, Action.HOLD) != null) {
            this.driver.holdStation(new Vec3(assign.facingX(), 0.0, assign.facingZ()));
        } else {
            this.driver.stop();
        }
    }

    /**
     * The published play role's point, if the chosen action is that role's action and the crew is not on it
     * yet; else null. Y is the hull's own, the same convention as {@link VehicleTargeting#computeStandoffPoint}.
     */
    @javax.annotation.Nullable
    private BlockPos taskedDestination(Action plan) {
        CrewAssignment.Snapshot assign = CrewAssignment.of(this.unit.getId());
        double[] point = TaskedDestination.pointFor(assign, plan);
        boolean drive = point != null && this.taskLatch.shouldDrive(assign.role(), assign.flankSide(),
                point[0], point[1], this.vehicle.getX(), this.vehicle.getZ());
        if (drive != this.taskDriving) {
            this.taskDriving = drive;
            // A flank run or a withdrawal starts behind one smoke volley — once per run, never a standing screen.
            // (drive implies a non-null assignment: pointFor answers null without one.)
            boolean screenedRun = drive && (assign.role() == Assignment.Role.WITHDRAW
                    || (assign.role() == Assignment.Role.MANEUVER && assign.flankSide() != null));
            if (screenedRun && this.brain.facts().smokeReady) {
                this.vehicle.setDecoyInputDown(true);
                this.smokeUntil = this.unit.level().getGameTime() + RUN_SMOKE_TICKS;
                SewvDiag.crew("{}#{} run smoke role={}", this.unit.getType().toShortString(), this.unit.getId(),
                        assign.role());
            }
            SewvDiag.crew("{}#{} tasked dest {} role={} side={} plan={} point={}",
                    this.unit.getType().toShortString(), this.unit.getId(), drive ? "START" : "DONE",
                    assign == null ? "-" : assign.role(), assign == null ? "-" : assign.flankSide(), plan.key,
                    point == null ? "-" : String.format("%.0f,%.0f", point[0], point[1]));
        }
        return drive ? BlockPos.containing(point[0], this.vehicle.getY(), point[1]) : null;
    }

    /**
     * Radio the target in to whoever behind us can reach it.
     *
     * <p>The cooldown stamp is written whether or not anyone answered: a crew that finds nothing
     * listening must not retry every tick, and the facts it decided on are a second old anyway.
     */
    private void requestSupport(LivingEntity target, FireMissionSupport.Kind kind) {
        Facts facts = this.brain.facts();
        facts.memory.lastSupportTick = this.unit.level().getGameTime();
        FireMissionSupport.callFireMission(this.unit.level(), facts.faction, facts.owner,
                this.unit.position(), EasyMode.mortarRadioRange(), target,
                Set.of(kind));
        // Caller voice: PMC air / mortar-or-tow pools. RU/US have no clips yet (playSupport no-ops).
        CrewRadio.playSupport(this.vehicle, kind);
    }

    /**
     * Hand our target to friendlies that have none.
     *
     * <p>Reuses the drone relay wholesale — "tell nearby friendlies about this enemy" is the same
     * job whether the eyes were a drone's or a tank commander's, and it already only writes to
     * units holding no target of their own, so it can never pull an ally off its own fight.
     */
    private void delegateTarget(LivingEntity target) {
        if (!(this.unit.level() instanceof ServerLevel server)) return;
        this.brain.facts().memory.lastSupportTick = server.getGameTime();
        // Same radius the crew counted its allies over, so it can only hand the target to
        // friendlies it actually knows are there.
        DroneSupport.broadcastTarget(server, this.unit, target, this.unit.position(),
                SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get());
    }

    /**
     * Manual arm or a live cover / scoot / peek / shield waypoint keeps this goal runnable
     * even with no wander dest / target.
     */
    private boolean seekCoverWantsDrive() {
        long now = this.unit.level().getGameTime();
        if (this.posture.seekCoverDestination(now) != null) return true;
        if (this.posture.scootOverrideDestination(now) != null) return true;
        if (this.posture.peekOffset(now) != null) return true;
        return this.posture.infantryShieldPoint(now) != null;
    }

    /**
     * Drive to the SEEK_COVER waypoint from the main tick. Independent of {@code getTarget()} —
     * posture may arm from memory / debug while the combat lock is already gone.
     *
     * @return true if this tick's steering was consumed
     */
    private boolean trySteerSeekCover() {
        long now = this.unit.level().getGameTime();
        Vec3 seekCover = this.posture.seekCoverDestination(now);
        if (seekCover == null) return false;
        // Player-owned locomotion (MOVE / FOB / FOLLOW / FORM) still wins — SEEK_COVER is
        // break-contact, not an order override.
        if (VehicleTargeting.holdsOrderedMove(this.unit)) return false;

        Action plan = this.brain.plan();
        if (plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE
                && this.posture.seekCoverForcesRetreat()) {
            this.brain.force(Action.RETREAT, now);
            plan = Action.RETREAT;
        }
        // Always navigate while the waypoint is live — do not wait for the scorer to agree.
        if (plan == Action.DEPLOY_SMOKE && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
            this.brain.facts().memory.lastSmokeTick = now;
        }
        this.driver.setInfantryPace(false);
        logPostureSteer(now, "seekCover", seekCover, plan);
        steerPostureTo(seekCover);
        return true;
    }

    /**
     * Drive to a posture waypoint, or hold on it once there. {@code navigateTo} has no arrival test (only a speed
     * ramp), and with {@code pathDestQuantum} snapping the point a hull standing on it can never quite reach it:
     * it kept steering at a point under its own tracks, the bearing flipped every few ticks, and the hull rotated
     * back and forth in place — the infantry-shield point, 6 blocks off a squad the tank already sat beside, did
     * this to whole pursuit groups.
     */
    private void steerPostureTo(Vec3 point) {
        double dx = point.x - this.vehicle.getX();
        double dz = point.z - this.vehicle.getZ();
        double arrive = this.vehicle.getBbWidth() * 0.5 + POSTURE_ARRIVE_PAD;
        if (dx * dx + dz * dz <= arrive * arrive) {
            this.driver.stop();
            this.driver.clearRecovery(); // on station — not stuck
            return;
        }
        this.driver.navigateTo(BlockPos.containing(point), this.vehicle.distanceToSqr(point));
    }

    /**
     * Scoot / infantry shield / corner peek — same main-tick placement as SEEK_COVER so area-hold
     * and brief target drops do not abort an already-armed maneuver.
     */
    private boolean trySteerPostureManeuvers() {
        if (VehicleTargeting.holdsOrderedMove(this.unit)) return false;
        Action plan = this.brain.plan();
        if (plan == Action.RETREAT || plan == Action.DEPLOY_SMOKE) return false;
        long now = this.unit.level().getGameTime();

        Vec3 scoot = this.posture.scootOverrideDestination(now);
        if (scoot != null) {
            this.driver.setInfantryPace(false);
            logPostureSteer(now, "scoot", scoot, plan);
            steerPostureTo(scoot);
            return true;
        }

        Vec3 shield = this.posture.infantryShieldPoint(now);
        if (shield != null
                && this.posture.active().contains(TacticalPosture.Tactic.INFANTRY_COVER)
                && !taskedToMove()) {
            this.driver.setInfantryPace(this.posture.throttleInfantryPace());
            // Soften plan gate: area-hold often isn't HOLD/ATTACK but the shield point is still valid.
            logPostureSteer(now, "infantryShield", shield, plan);
            steerPostureTo(shield);
            return true;
        }
        this.driver.setInfantryPace(false);

        Vec3 peek = this.posture.peekOffset(now);
        if (peek != null
                && this.posture.active().contains(TacticalPosture.Tactic.CORNER_PEEK)
                && (plan == Action.HOLD || plan == Action.ATTACK || plan == Action.PATROL
                        || plan == Action.IDLE_HOLD)) {
            logPostureSteer(now, "keyhole", peek, plan);
            steerPostureTo(peek);
            return true;
        }
        return false;
    }

    /**
     * A play has this crew moving (flank, bound, pursuit, withdrawal). Screening the local rifle squad would pin
     * it beside them and the group's maneuver would never happen, so the infantry shield yields to it.
     */
    private boolean taskedToMove() {
        CrewAssignment.Snapshot assign = CrewAssignment.of(this.unit.getId());
        return assign != null
                && (assign.role() == Assignment.Role.MANEUVER || assign.role() == Assignment.Role.WITHDRAW);
    }

    /**
     * Which way this crew works around a target. Entity-id parity, matching
     * {@link StalemateBreaker}'s own choice so the breaker's orbit and the scored flank never
     * disagree about the direction and walk the hull back and forth over the same ground.
     */
    private void logPostureSteer(long now, String kind, Vec3 dest, Action plan) {
        // MIN_VALUE guard: now - Long.MIN_VALUE overflows negative, which silenced this log forever.
        if (this.lastPostureSteerLog != Long.MIN_VALUE && now - this.lastPostureSteerLog < 20L) return;
        this.lastPostureSteerLog = now;
        if (!SewvDiag.individualTacticsVerbose()) return;
        SewvDiag.posture(
                "steer unit={}#{} vehicle={}#{} kind={} plan={} dest={},{},{}",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                kind, plan,
                String.format("%.1f", dest.x), String.format("%.1f", dest.y), String.format("%.1f", dest.z));
    }

    private boolean breakerGoesLeft() {
        return (this.unit.getId() & 1) == 0;
    }

    /**
     * The special is a guided missile whose lofted firing solution can't pass SBW's 4°
     * straight-line gate at ring range — fire it ourselves within the configured wider cone
     * (guidance corrects the loft). Cannon/MG keep firing through SBW's native precise gate.
     *
     * <p>Gated on the ROLE the selection actually chose, never on getWeaponIndex(): that
     * returns a PHYSICAL slot, and comparing it to a role id only appears to work because
     * SBW's stock hulls happen to list ["Cannon","MachineGun","Missile"] in role order. On a
     * hull that doesn't — fcp:bmp1u is ["Cannon","Konkurs","Coax"] — it fired the assist at
     * whatever sat in slot 2 (the COAX) while the ATGM in slot 1 never fired, so it never
     * reloaded, so specialReady() stayed true, so the cannon was never re-selected: a crew
     * locked onto a missile it could not launch.
     */
    private void fireAssistIfSpecial(LivingEntity target) {
        if (this.selectedRole != VehicleWeapons.WEAPON_SPECIAL) return;
        VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                EasyMode.aiFireAssistConeDeg());
    }

    /**
     * Today's trigger, cheapest test first: an IFV ({@link HullFacts#isIfv}, cached) carrying more
     * passengers than crew seats (each passenger holds a distinct seat, so that means somebody is
     * in the back), holding a target that rides armour. Everything about the sequence itself —
     * who gets out, where, the AT issue, why there is no recall — is {@link DismountSequence}.
     */
    private boolean maybeBeginDismount(LivingEntity target) {
        if (!this.hull.isIfv()) return false;
        if (this.vehicle.getPassengers().size() <= this.hull.crewSeats().size()) return false;
        if (target == null || VehicleWeapons.classifyTarget(target) != TargetCategory.VEHICLE) return false;
        return this.dismount.begin(this.unit, this.vehicle, this.hull.crewSeats(), target);
    }

    /**
     * A halted hull putting its squad out. Every steering input released every tick — an
     * instruction, not the absence of one — while the turret keeps working: the same "fight from
     * where you stand" the Call actions use.
     */
    private void dismountTick(LivingEntity target) {
        this.driver.stop();
        this.driver.clearRecovery();
        // The decoy is latched; a DEPLOY_SMOKE plan interrupted by the halt must not keep volleying.
        if (this.unit.level().getGameTime() >= this.smokeUntil) {
            this.vehicle.setDecoyInputDown(false);
        }
        if (target != null) {
            selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
            fireAssistIfSpecial(target);
        }
        this.dismount.tick(this.unit, this.vehicle);
    }

    /**
     * Empties out the tank-rider ("Climb" pose) seats once the driver holds any target — see
     * {@link HullFacts#climbSeats} for why "Climb" and not "no weapon" is the test. Unlike the IFV
     * dismount ({@link DismountSequence}): no halt and no staggered exit — riders cling to the
     * outside and there is no door to sequence. It also triggers on ANY contact (utilization, not an anti-armor tactic), never
     * issues an AT launcher (these are spare hitchhikers, not a designated element), and is NOT
     * one-way — {@code SeekAbandonedVehicleGoal} lets any idle unit reclaim a free Climb seat once
     * things are quiet, rather than tracking and recalling the specific rider who got off.
     *
     * <p>Runs every combat tick with no "have I done this" flag: once a seat is empty there is nobody left in it to find on the next
     * pass, so re-running costs one more iteration over a short passenger list.
     */
    private void dismountClimbers() {
        Set<Integer> climb = this.hull.climbSeats();
        for (Entity passenger : List.copyOf(this.vehicle.getPassengers())) {
            if (!(passenger instanceof AbstractUnit rider) || rider == this.unit) continue;
            int seat = this.vehicle.getSeatIndex(rider);
            if (!climb.contains(seat)) continue;
            rider.stopRiding();
        }
    }

    /**
     * RU/US hull inside a tripod's standoff ({@link TripodCompat}): drive out to standoff + margin,
     * fighting on the way. An emplacement cannot drive, so its crew bails and walks out
     * ({@code TripodStandoffGoal}); the scavenge goals will not send them back to it.
     */
    private boolean tripodStandoffTick(LivingEntity target) {
        double radius = TripodCompat.standoff() + (this.tripodRetreat ? TripodCompat.MARGIN : 0.0);
        Entity tripod = TripodCompat.threatening(this.unit, this.vehicle, radius);
        this.tripodRetreat = tripod != null;
        if (tripod == null) return false;

        if (HullFacts.engineType(this.vehicle) == EngineType.FIXED) {
            for (Entity rider : List.copyOf(this.vehicle.getPassengers())) {
                if (rider instanceof AbstractUnit crew && TripodCompat.appliesStandoff(crew)) {
                    BailOutSupport.triggerVehicleBail(crew, this.vehicle);
                }
            }
            return true;
        }
        if (target != null) {
            selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
            fireAssistIfSpecial(target);
        }
        BlockPos from = tripod.blockPosition();
        this.driver.retreatFrom(from, TripodCompat.standoff() + TripodCompat.MARGIN,
                this.vehicle.distanceToSqr(from.getX() + 0.5, from.getY(), from.getZ() + 0.5));
        return true;
    }

    /**
     * Hold an armored target at the far standoff ring: close in when beyond it, open the
     * distance back out when inside it, and hold when on it. This is what stops two hulls from
     * creeping into a point-blank standstill where the cannon/TOW can't be brought to bear —
     * the deadband gives the ring width so the hull settles instead of dithering forward and
     * back across the exact radius.
     *
     * <p>Armor uses a Schmitt trigger: leaving the hold band needs more error than entering it,
     * so tracked overshoot does not flip both duelists into mutual reverse.
     */
    private void maintainVehicleStandoff(BlockPos targetPos, double distanceSq, double dist,
                                         TargetCategory category) {
        double ring = Facts.preferredRange(this.unit, this.unit.getTarget(), category);
        double band = Facts.rangeDeadband(category);
        double leave = category == TargetCategory.VEHICLE ? band + 4.0 : band;

        if (this.standoffPhase == 0) {
            if (dist > ring + leave) this.standoffPhase = 1;
            else if (dist < ring - leave) this.standoffPhase = -1;
        } else if (this.standoffPhase > 0) {
            if (dist <= ring + band) this.standoffPhase = 0;
        } else {
            if (dist >= ring - band) this.standoffPhase = 0;
        }

        if (this.standoffPhase > 0) {
            this.driver.navigateTo(targetPos, distanceSq);
        } else if (this.standoffPhase < 0) {
            this.driver.retreatFrom(targetPos, ring, distanceSq);
        } else {
            this.driver.stop(); // on the ring — hold and let the turret work
            this.driver.clearRecovery();
        }
    }

    private boolean isLowHealth() {
        return isLowHealth(this.vehicle);
    }

    /**
     * True once the hull is below the self-preservation health threshold. Vehicle health only
     * falls in combat (repairs happen out of contact), so this is monotonic — no flicker
     * around the threshold to guard against.
     *
     * <p>Shared rather than private because {@link PatrolSupport#assistPos} triggers a patrol's
     * mutual support off the same notion of "badly hurt" that this goal retreats on. A second
     * threshold of its own would be one more number to keep in step with this one.
     */
    public static boolean isLowHealth(VehicleEntity vehicle) {
        float max = vehicle.getMaxHealth();
        return max > 0.0F && vehicle.getHealth() < max * PRESERVE_HEALTH_FRACTION;
    }

    /**
     * Self-preservation once badly hurt: screen with smoke toward the threat and fall back past
     * the standoff ring, then hold at range rather than re-engaging. The smoke is fired by
     * raising the decoy input — the vehicle's own tick launches it along the turret vector
     * (already tracking the threat), and the launcher's ready/reload gating means holding the
     * input just fires each volley as it comes back up.
     */
    private void preserveRetreat(LivingEntity threat, TargetCategory category) {
        // No coin flip any more: whether to screen at all was already decided by the scorer, and
        // the plan's minimum duration is what keeps the latch held for a whole episode instead of
        // stuttering. Holding the input simply fires each volley as the launcher comes back up.
        if (this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
            this.brain.facts().memory.lastSmokeTick = this.unit.level().getGameTime();
        }

        BlockPos threatPos = threat.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ());
        double breakDistance = Facts.preferredRange(this.unit, threat, category) + TacticalScale.of(PRESERVE_RETREAT_MARGIN);

        if (Math.sqrt(distanceSq) > breakDistance) {
            // Clear of the ring — far enough to be safe. Hold here (still smoking) so we
            // neither sprint away forever nor charge back into the standoff.
            this.driver.stop();
            this.driver.clearRecovery();
            return;
        }
        this.driver.retreatFrom(threatPos, breakDistance, distanceSq);
    }

    /**
     * Ground doctrine (slot roles, cannon/special alternation, and the AP/HE/grapeshot pick
     * for the target) lives in {@link VehicleWeapons}; only the switch cooldown lives here —
     * and it matters more than it looks, because the ammo switch resets the gun's reload
     * timers. The flight goal uses its own random-cycle doctrine instead.
     *
     * <p>The chosen role is cached rather than re-derived: it stays valid between selections
     * (nothing else writes the weapon index for this seat), and re-deriving would mean
     * re-running the whole slot classification every tick just to learn what selection already
     * knew.
     */
    private void selectWeaponForTarget(int seatIndex, LivingEntity target) {
        if (seatIndex < 0 || this.weaponSwitchCooldown > 0) return;
        VehicleWeapons.WeaponSelection pick = VehicleWeapons.selectWeaponForTarget(
                this.vehicle, seatIndex, target, this.unit);
        this.selectedRole = pick.role;
        this.weaponSwitchCooldown = WEAPON_SWITCH_COOLDOWN_TICKS;
    }

    // Destination resolution — SEM order queue for PMC, current target / ally-assist for
    // RU/US — is shared with DriveHelicopterGoal. See VehicleTargeting.
    private void towRecoveryTick() {
        if (!(this.unit instanceof com.neoalive.tacz_sewv.bridge.ITowRecovery tow)) return;
        if (tow.tacz_sewv$getTowVictimId() == -1) return;
        // Stale order on a FIXED / plane / mortar / SPH — drop it rather than steering inert inputs.
        if (!TowRecoverySupport.isTowTowerCandidate(this.vehicle)) {
            TowRecoverySupport.clearOrder(this.unit, this.vehicle);
            return;
        }

        VehicleEntity victim = TowRecoverySupport.resolveTowVictim(this.unit, tow);
        if (victim == null) {
            if (TowRecoverySupport.towVictimGraceActive(tow)) {
                this.driver.stop();
                return;
            }
            TowRecoverySupport.clearOrder(this.unit, this.vehicle);
            return;
        }

        // Same gate as the main tick path: fightTick assumes a live target (blockPosition on
        // the first line). A hurt tower with nobody locked just keeps towing.
        if (isLowHealth()) {
            LivingEntity target = this.unit.getTarget();
            if (target != null) {
                fightTick(target);
                return;
            }
        }

        TowRecoverySupport.steerTower(this.unit, this.vehicle, victim, this.driver);
        TowRecoverySupport.tickCompletion(this.unit, this.vehicle, victim);
    }

    private BlockPos getTargetPos() {
        // A named destination — MOVE / FOB / FOLLOW / FORM — is not a suggestion the utility
        // layer gets to improve on. The three plans below all substitute a destination of their
        // own (the last place an enemy was seen, an idle hold, an idle leg), and any of them
        // winning while an order stands sends the hull back towards the fight it was told to
        // leave. resolveDestination already reads the route/order first, so this only has to stop
        // the plans from getting in front of it.
        if (VehicleTargeting.holdsOrderedMove(this.unit)) {
            return VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        }
        Action plan = idlePlan();
        if (plan == Action.SEARCH_LAST_KNOWN) {
            BlockPos seen = this.brain.facts().memory.lastEnemyPos;
            if (seen != null) return seen;
        }
        if (plan == Action.IDLE_HOLD) {
            BlockPos p = IdleGroupSupport.holdDestination(this.unit, this.vehicle, this.brain.facts());
            if (p != null) return p;
        } else if (plan == Action.IDLE_TRAVEL) {
            BlockPos p = marchSlotPos();
            if (p != null) return p;
        }
        if (inCohesiveLine()) {
            BlockPos p = marchSlotPos();
            if (p != null) return p;
        }
        BlockPos standing = VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
        return standing != null ? standing : idleTaskPos();
    }

    /**
     * Where the command tier has tasked this idle crew, read without the brain: the march slot, else the idle
     * hold's centre. This is what wakes a stopped goal — a stopped goal's brain has been cleared to HOLD and
     * would never otherwise name a destination, so a crew that once parked stayed parked while the rest of its
     * group marched off without it. Map lookups only: canUse can afford it. Once running, the plan decides.
     */
    @javax.annotation.Nullable
    private BlockPos idleTaskPos() {
        BlockPos march = marchSlotPos();
        if (march != null) return march;
        CrewAssignment.Snapshot a = CrewAssignment.of(this.unit.getId());
        if (a != null && a.role() == Assignment.Role.IDLE_HOLD && a.hasDest()) {
            return BlockPos.containing(a.destX(), this.vehicle.getY(), a.destZ());
        }
        return null;
    }

    @javax.annotation.Nullable
    private BlockPos marchSlotPos() {
        MarchRegistry.Slot slot = MarchRegistry.slotFor(this.unit.getId(), this.unit.level().getGameTime());
        return slot != null && slot.hull() ? BlockPos.containing(slot.x(), this.vehicle.getY(), slot.z()) : null;
    }

    /** Debug-forced idle modes steer from hull NBT before the scorer adopts IDLE_* . */
    private Action idlePlan() {
        if (IdleGroupSupport.isDebugDrive(this.vehicle)) {
            if (MarchRegistry.kindOf(this.unit.getId()) == March.Kind.IDLE_TRAVEL) return Action.IDLE_TRAVEL;
            if (IdleGroupSupport.modeOf(this.vehicle) == IdleGroupSupport.MODE_HOLD) return Action.IDLE_HOLD;
            var assign = com.neoalive.tacz_sewv.entity.ai.command.CrewAssignment.of(this.unit.getId());
            if (assign != null) {
                if (assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.IDLE_TRAVEL) {
                    return Action.IDLE_TRAVEL;
                }
                if (assign.role() == com.neoalive.tacz_sewv.entity.ai.command.Assignment.Role.IDLE_HOLD) {
                    return Action.IDLE_HOLD;
                }
            }
        }
        return this.brain.plan();
    }

}
