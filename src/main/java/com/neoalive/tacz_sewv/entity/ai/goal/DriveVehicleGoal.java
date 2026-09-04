package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

import com.neoalive.tacz_sewv.compat.AshMissileSupport;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.AmmoVoicelines;
import com.neoalive.tacz_sewv.crew.CrewRadio;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.core.StalemateBreaker;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleDriver;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;
import com.neoalive.tacz_sewv.entity.ai.sensor.OuterRingAwareness;
import com.neoalive.tacz_sewv.entity.ai.support.ArtillerySupport;
import com.neoalive.tacz_sewv.entity.ai.support.DroneSupport;
import com.neoalive.tacz_sewv.entity.ai.support.FireMissionSupport;
import com.neoalive.tacz_sewv.entity.ai.support.GuardSupport;
import com.neoalive.tacz_sewv.entity.ai.support.IdleGroupSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.ai.support.SmallArmsSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;
import com.neoalive.tacz_sewv.entity.ai.support.TreeFellingSupport;
import com.neoalive.tacz_sewv.entity.ai.support.VehicleMortarSupport;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalPosture;
import com.neoalive.tacz_sewv.invasion.CaptureOrderSupport;

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
    private static final int MAX_AT_GUNNERS = 2;

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
    /** Outer awareness ring — spots only, never setTarget. See {@link OuterRingAwareness}. */
    private final OuterRingAwareness outerRing = new OuterRingAwareness();
    /** Merges outer spots + sound cues into Facts investigate fields. */
    private final AwarenessCues awareness = new AwarenessCues();

    /** Everything about actually making the hull go somewhere. See {@link VehicleDriver}. */
    private final VehicleDriver driver;

    private VehicleEntity vehicle;

    private int weaponSwitchCooldown;
    // Which ROLE the last selection picked (VehicleWeapons.WEAPON_*), or UNCLASSIFIED.
    // Cached because getWeaponIndex() can't answer this — see selectWeaponForTarget.
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;
    /** Standoff Schmitt state: 0 hold, 1 closing, -1 opening. See {@link #maintainVehicleStandoff}. */
    private byte standoffPhase;
    /** Throttle for posture override logs (game time of last line). */
    private long lastPostureSteerLog = Long.MIN_VALUE;
    /** Previous out-of-contact plan — edge-detect SEARCH_LAST_KNOWN for investigating voicelines. */
    private Action lastIdlePlan = Action.HOLD;

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
                && (TowRecoverySupport.hasTowOrder(this.unit) || getTargetPos() != null);
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
        this.lastIdlePlan = Action.HOLD;
        this.driver.stop();
        this.vehicle.setDecoyInputDown(false);
        this.vehicle = null;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
        this.standoffPhase = 0;
        this.allyAssist.clear();
        this.driver.clear();
        this.breaker.clear();
        this.outerRing.clear();
        this.awareness.clear();
        this.brain.facts().unbind(this.unit);
        this.brain.clear();
        this.posture.clear();
        TacticalPosture.clearUnit(this.unit.getId());
    }

    @Override
    public void tick() {
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        this.driver.tickTimers();
        TreeFellingSupport.tick(this.unit, this.vehicle, this.hull);

        if (this.vehicle.getFirstPassenger() == this.unit) {
            TowRecoverySupport.tickDriverStrandedBroadcast(this.vehicle);
        }

        if (TowRecoverySupport.isTowering(this.unit, this.vehicle)) {
            towRecoveryTick();
            return;
        }

        // Spots before re-score so DISTANT_CONTACT sees this tick's outer fields. noteSpot is
        // gated on getTarget()==null; observe (inside update) still owns Memory when locked.
        Facts facts = this.brain.facts();
        this.outerRing.tick(this.unit, this.vehicle, this.awareness, facts.underOrders);
        this.awareness.tick(this.unit, this.vehicle, facts);
        // Re-read the battlefield and, on its own ~1s cadence, re-decide. Cheap on the ticks it
        // does nothing, which is most of them.
        this.brain.update(this.unit, this.vehicle, this.posture);

        Action plan = idlePlan();
        if (plan == Action.SEARCH_LAST_KNOWN && this.lastIdlePlan != Action.SEARCH_LAST_KNOWN) {
            CrewRadio.play(this.vehicle, CrewRadio.Line.INVESTIGATING);
        }
        this.lastIdlePlan = plan;

        LivingEntity target = this.unit.getTarget();

        // Runs off the same "the driver holds a target" signal fightTick does, and deliberately
        // ABOVE the destination check below: standing the squad back up happens on quiet ticks,
        // which are exactly the ticks that can have no destination at all.
        // An IFV puts its squad on the ground against ARMOUR only — see dismountSquad. Deliberately
        // NOT screened with smoke first: it would fire on every such contact, and the battlefield
        // would end up permanently fogged.
        if (target != null && this.hull.isIfv()
                && VehicleWeapons.classifyTarget(target) == TargetCategory.VEHICLE) {
            dismountSquad();
        }

        // Tank-rider ("Climb" pose) seats: a utilization fix, not an anti-armor tactic, so it
        // triggers on ANY held target rather than being armor-gated like the IFV dismount above —
        // see dismountClimbers.
        if (target != null && !this.hull.climbSeats().isEmpty()) {
            dismountClimbers();
        }

        // The decoy input is latched vehicle state: release it on every tick the crew is not
        // actively screening (preserveRetreat re-asserts it immediately after), otherwise one
        // retreat would leave the launcher volleying a fresh smoke salvo every reload, forever.
        if (target == null || this.brain.plan() != Action.DEPLOY_SMOKE) {
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
        // A MOVE_TO_POSITION click and a FOB route are NOT subject to that health exception. They
        // are a destination the player named, and the whole reason to name one while hurt is to
        // get the hull out — handing a damaged crew to fightTick made it hold a standoff ring on
        // the enemy instead, which reads on screen as a hull that keeps backing away rather than
        // driving home. Retreat is the order; fightTick's version of it goes nowhere.
        //
        // Pure combat steers off the live target inside fightTick — resolveDestination is unused
        // there, so skip getTargetPos unless a named move / area hold needs the standing dest.
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
        BlockPos destination = switch (idlePlan()) {
            case SEARCH_LAST_KNOWN -> {
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
            case IDLE_TRAVEL -> IdleGroupSupport.travelDestination(
                    this.unit, this.vehicle, this.brain.facts());
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

    /** Steer for {@code destination}, or park on station once inside the arrival ring. */
    private void driveTo(BlockPos destination) {
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
            parkOnStation();
            this.driver.clearRecovery(); // parked at destination
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
     * Parked at the destination. Under a formation order that means holding the frozen axis, so
     * the wedge points where it was pointed and every hull's frontal armor and gun face the same
     * way; any other order has no heading to hold and simply stops.
     */
    private void parkOnStation() {
        Vec3 axis = VehicleTargeting.formationForward(this.unit);
        // Only a tracked hull can pivot in place. A wheeled one would sit holding a steering
        // input it cannot act on, so it parks facing however it happened to arrive.
        if (axis == null || !this.hull.isTracked()) {
            this.driver.stop();
            return;
        }
        this.driver.faceHeading(axis);
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
     * <p>Anchored to the TARGET, not the resolved order destination — under FOLLOW/MOVE_TO/
     * formation orders those differ, and holding a standoff ring around our own commander
     * (while weapon choice tracks the actual enemy) is exactly the bug this distinction
     * avoids. Once the fight ends, the next tick resumes driving on the order.
     *
     * <p>Every branch issues steering input on every tick. SuperbWarfare ramps a tracked hull's
     * turn rate only while a steering input stays held, so an action that simply returned would
     * bring back the pivot-forever bug — {@link #stopVehicleMovement} is a real instruction, not
     * the absence of one.
     */
    private void fightTick(LivingEntity target) {
        BlockPos combatPos = target.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(
                combatPos.getX() + 0.5, combatPos.getY(), combatPos.getZ() + 0.5);
        double dist = Math.sqrt(distanceSq);

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        // The ring this crew wants to hold against this kind of target — the same number the
        // scorer measured its too-close/too-far signals against.
        double ring = Facts.preferredRange(category);

        selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
        fireAssistIfSpecial(target);

        // The stalemate breaker outranks the scorer, and must: it is the watchdog for a crew
        // holding a target it physically cannot hit, and the actions that freeze the hull are
        // exactly the ones that look correct while it happens. A bad weight in a datapack must
        // not be able to bring back the park-forever bug this exists to kill. It is skipped
        // while retreating, where silence is success rather than a stall.
        Action plan = this.brain.plan();
        long now = this.unit.level().getGameTime();
        Vec3 scoot = this.posture.scootOverrideDestination(now);
        if (scoot != null && plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE) {
            // Fire-and-maneuver: temporary dest override; fan/ORCA still gate the approach.
            this.driver.setInfantryPace(false);
            logPostureSteer(now, "scoot", scoot, plan);
            this.driver.navigateTo(BlockPos.containing(scoot), this.vehicle.distanceToSqr(scoot));
            return;
        }

        Vec3 shield = this.posture.infantryShieldPoint();
        if (shield != null && this.posture.active().contains(TacticalPosture.Tactic.INFANTRY_COVER)
                && plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE) {
            this.driver.setInfantryPace(this.posture.throttleInfantryPace());
            // Soft bias: when HOLD/ATTACK, prefer shield point over pure standoff stop.
            if (plan == Action.HOLD || plan == Action.ATTACK) {
                logPostureSteer(now, "infantryShield", shield, plan);
                this.driver.navigateTo(BlockPos.containing(shield), this.vehicle.distanceToSqr(shield));
                return;
            }
        } else {
            this.driver.setInfantryPace(false);
        }

        Vec3 peek = this.posture.peekOffset();
        if (peek != null && plan == Action.HOLD
                && this.posture.active().contains(TacticalPosture.Tactic.CORNER_PEEK)) {
            logPostureSteer(now, "keyhole", peek, plan);
            this.driver.navigateTo(BlockPos.containing(peek), this.vehicle.distanceToSqr(peek));
            return;
        }

        if (plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE) {
            BlockPos orbit = this.breaker.update(target, combatPos, ring);
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

        switch (plan) {
            case RETREAT -> this.driver.retreatFrom(combatPos, ring + PRESERVE_RETREAT_MARGIN, distanceSq);

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
                this.driver.stop();
                this.driver.clearRecovery(); // holding on purpose — not stuck
            }

            // Calling for support takes no time and moves nothing, so these keep fighting exactly
            // as ATTACK would. A crew that stopped driving to use its radio would be a crew
            // standing still in the middle of a tank battle.
            case CALL_MORTARS -> {
                requestSupport(target, FireMissionSupport.Kind.MORTAR);
                maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case CALL_TOW -> {
                requestSupport(target, FireMissionSupport.Kind.TOW);
                maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case CALL_CAS -> {
                requestSupport(target, FireMissionSupport.Kind.CAS);
                maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }
            case DELEGATE_TARGET -> {
                delegateTarget(target);
                maintainVehicleStandoff(combatPos, distanceSq, dist, category);
            }

            // Hold the standoff band for the target's type: close when beyond it, open when
            // inside it, sit still on it and let the turret work.
            case ATTACK -> maintainVehicleStandoff(combatPos, distanceSq, dist, category);
        }
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
                this.unit.position(), SewvConfig.MORTAR_RADIO_RANGE.get(), target,
                Set.of(kind));
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
     * Which way this crew works around a target. Entity-id parity, matching
     * {@link StalemateBreaker}'s own choice so the breaker's orbit and the scored flank never
     * disagree about the direction and walk the hull back and forth over the same ground.
     */
    private void logPostureSteer(long now, String kind, Vec3 dest, Action plan) {
        if (!SewvDiag.individualTacticsVerbose()) return;
        if (now - this.lastPostureSteerLog < 20L) return;
        this.lastPostureSteerLog = now;
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
                SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
    }

    /**
     * Puts everyone who isn't crew out of an IFV, so the squad fights on foot while the hull keeps
     * working its gun. Who stays is {@link HullFacts#crewSeats} — the driver and the turret.
     *
     * <p>Only against a SuperbWarfare hull, which is what {@link TargetCategory#VEHICLE} means:
     * <b>a VehicleEntity is not a LivingEntity and can never be a mob's target</b>, so the test
     * that matters is whether the thing we are shooting at is RIDING one
     * ({@link VehicleWeapons#classifyTarget}). Testing the target itself for {@code VehicleEntity}
     * would be false forever and this would never fire. Against infantry the squad stays aboard —
     * the hull's own cannon and MGs already cover that, and it is armour that makes a loaded
     * troop compartment a liability worth emptying. Addon hulls (fcp/mcsp/…) subclass
     * {@code VehicleEntity}, so they count too, which a check on the {@code superbwarfare}
     * namespace would have missed.
     *
     * <p>One or two of them draw an anti-tank launcher on the way out ({@link SmallArmsSupport}),
     * which is what makes the whole feature worth having: the squad is being put on the ground
     * precisely because the hull met armour, and a TACZ rifle cannot scratch armour. RU/US only —
     * a PMC's loadout belongs to the player who filled its inventory.
     *
     * <p>Once out they stay out: they revert to ordinary SEM infantry and are simply picked up
     * again by whatever puts a unit in a seat. There is no recall, deliberately — a walk-back
     * state machine is a lot of moving parts for infantry that has already done its job. Note
     * this survived the arrival of vehicle scavenging ({@code SeekAbandonedVehicleGoal}) only
     * because that goal takes <em>completely empty</em> hulls: the IFV they left still holds its
     * driver and gunner, so it is never a candidate and the squad cannot drift back aboard.
     *
     * <p>Runs on every combat tick rather than once, which needs no "have I done this" flag: after
     * the first pass the only passengers left are in crew seats, and that is exactly what the size
     * check short-circuits on. It also means a unit that boards a rear seat mid-fight is put back
     * out. Players are never ejected, whatever seat they took.
     */
    private void dismountSquad() {
        Set<Integer> crew = this.hull.crewSeats();
        // Each passenger holds a distinct seat, so "no more passengers than crew seats" is a
        // sound way of saying the squad is already off.
        if (this.vehicle.getPassengers().size() <= crew.size()) return;
        CrewRadio.play(this.vehicle, CrewRadio.Line.IFV); // a real dismount is happening this call

        int armed = 0;
        // Copied because stopRiding() mutates the passenger list underneath us.
        for (Entity passenger : List.copyOf(this.vehicle.getPassengers())) {
            if (!(passenger instanceof AbstractUnit rider) || rider == this.unit) continue;
            int seat = this.vehicle.getSeatIndex(rider);
            if (seat < 0 || crew.contains(seat)) continue;

            // The first man out always draws a launcher, the second rolls for it, and nobody
            // after that gets one — a squad fields one or two AT gunners, never a whole section
            // of them. issueAtWeapon answers false for a PMC (whose loadout is the player's),
            // for a unit already carrying one, and for a blank config id, so the count tracks
            // weapons actually handed out rather than attempts. That is also what keeps this
            // honest across the every-tick re-entry above: an already-armed man cannot consume
            // one of the two slots a second time.
            if (armed == 0 || (armed < MAX_AT_GUNNERS
                    && rider.getRandom().nextDouble() < SewvConfig.AT_SECOND_GUNNER_CHANCE.get())) {
                if (SmallArmsSupport.issueAtWeapon(rider)) armed++;
            }
            rider.stopRiding();
        }
    }

    /**
     * Empties out the tank-rider ("Climb" pose) seats once the driver holds any target — see
     * {@link HullFacts#climbSeats} for why "Climb" and not "no weapon" is the test. Unlike
     * {@link #dismountSquad}: triggers on ANY contact (utilization, not an anti-armor tactic), never
     * issues an AT launcher (these are spare hitchhikers, not a designated element), and is NOT
     * one-way — {@code SeekAbandonedVehicleGoal} lets any idle unit reclaim a free Climb seat once
     * things are quiet, rather than tracking and recalling the specific rider who got off.
     *
     * <p>Runs every combat tick with no "have I done this" flag, same reasoning as
     * {@link #dismountSquad}: once a seat is empty there is nobody left in it to find on the next
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
        double ring = Facts.preferredRange(category);
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
        double breakDistance = Facts.preferredRange(category) + PRESERVE_RETREAT_MARGIN;

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
        if (pick.switchedAmmoId != null) {
            AmmoVoicelines.play(this.vehicle, this.unit, pick.switchedAmmoId);
        }
        this.weaponSwitchCooldown = WEAPON_SWITCH_COOLDOWN_TICKS;
    }

    // Destination resolution — SEM order queue for PMC, current target / ally-assist for
    // RU/US — is shared with DriveHelicopterGoal. See VehicleTargeting.
    private void towRecoveryTick() {
        if (!(this.unit instanceof com.neoalive.tacz_sewv.bridge.ITowRecovery tow)) return;
        if (tow.tacz_sewv$getTowVictimId() == -1) return;

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
        // A named destination — a MOVE click or a FOB route — is not a suggestion the utility
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
            BlockPos p = IdleGroupSupport.travelDestination(this.unit, this.vehicle, this.brain.facts());
            if (p != null) return p;
        }
        return VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
    }

    /** Debug-forced idle modes steer from hull NBT before the scorer adopts IDLE_* . */
    private Action idlePlan() {
        if (IdleGroupSupport.isDebugDrive(this.vehicle)) {
            byte mode = IdleGroupSupport.modeOf(this.vehicle);
            if (mode == IdleGroupSupport.MODE_HOLD) return Action.IDLE_HOLD;
            if (mode == IdleGroupSupport.MODE_TRAVEL) return Action.IDLE_TRAVEL;
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
