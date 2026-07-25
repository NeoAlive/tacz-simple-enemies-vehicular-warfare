package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.utility.Action;
import com.neoalive.tacz_sewv.entity.ai.utility.Facts;
import com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain;
import com.neoalive.tacz_sewv.util.CrewRadio;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Drives a ground hull for its crew: where to be relative to the target, how to get there, and
 * which weapon to have selected when it arrives.
 *
 * <p>Flight is {@link DriveHelicopterGoal}'s job and floating is {@link DriveShipGoal}'s — all
 * three goals are registered on every crew and this one declines helicopters and ships in
 * {@link #canUse}.
 *
 * <p>The work this goal delegates rather than does: terrain and obstacle probing
 * ({@link GroundTerrainSensor}), the recovery from a crew that holds a target it cannot hit
 * ({@link StalemateBreaker}), weapon doctrine ({@link VehicleWeapons}) and destination
 * resolution ({@link VehicleTargeting}).
 */
public class DriveVehicleGoal extends Goal {

    // Angle thresholds, tight when close, laxer when far (from SuperbRecruitz)
    private static final double MIN_ANGLE_RAD = Math.toRadians(3.0);
    private static final double MAX_ANGLE_RAD = Math.toRadians(22.5);
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 20.0;

    // How far off the formation axis a parked hull tolerates before correcting. Without a
    // deadband the hull hunts across the exact bearing forever, since it can only turn in
    // discrete input-held steps.
    private static final double FACING_DEADBAND_RAD = Math.toRadians(8.0);

    // The standoff rings themselves now live in Facts.preferredRange / Facts.rangeDeadband,
    // because the scorer needs the same numbers to decide whether we are too close or too far
    // and two copies would drift. Infantry is a 10-20 band (15 ± 5); armor holds the far ring
    // at 40 ± 4, because a tank duel that collapses to point-blank is a tank duel getting lost.

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

    // Reversing only opens distance while the target sits inside this frontal cone; beyond
    // it, backing up moves the hull sideways or INTO the target.
    private static final double REVERSE_FACING_CONE_RAD = Math.toRadians(75.0);

    // Pathfinding throttles: A* over the vehicle's block volume is the most expensive thing
    // this goal does, so a still-valid path is reused instead of recomputed on a fixed timer.
    private static final int PATH_RECALC_COOLDOWN = 20;        // min ticks between searches
    private static final int PATH_FAIL_COOLDOWN = 60;          // back off after a failed search
    private static final int MAX_PATH_AGE_TICKS = 100;         // force a refresh even if "valid"
    private static final double PATH_TARGET_DRIFT_SQ = 9.0;    // target moved >3 blocks → refresh
    private static final double PATH_ABANDON_DRIFT_SQ = 256.0; // dest jumped >16 blocks → repath now
    // How close (squared) the hull must be to a waypoint to treat it as reached and aim at
    // the next one. Re-evaluated from the live position every tick, so the aimed waypoint
    // stays put while the hull turns in place instead of jittering.
    private static final double NODE_REACHED_SQ = 9.0;
    // 32 horizontal keeps the chunk snapshot at 5×5 chunks instead of 9×9; targets beyond it
    // still get a partial path that walks us closer. Ground vehicles never need a
    // 64-block-tall search volume either.
    private static final int PATH_SEARCH_RANGE = 32;
    private static final int PATH_SEARCH_VERTICAL = 16;

    // Stuck recovery: if the hull neither moves nor turns for this long while it is being
    // told to drive, it is wedged on terrain — reverse and swing the tail out for a moment,
    // then repath. Rotation counts as progress so a slow turn-in-place is never mistaken for
    // being stuck.
    private static final int STUCK_TICKS_THRESHOLD = 40;      // 2s of no movement AND no rotation
    private static final double STUCK_MOVE_EPSILON_SQ = 0.04; // <0.2 block moved = no headway
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;  // <1° turned = no rotation
    private static final int UNSTICK_DURATION = 16;           // reverse-and-swing for ~0.8s

    private final AbstractUnit unit;
    private final HullFacts hull = new HullFacts();
    private final GroundTerrainSensor sensor;
    private final StalemateBreaker breaker;
    /** Decides what this crew does about its target. See {@link #fightTick}. */
    private final TacticalBrain brain = new TacticalBrain();
    // Mutual support scanner (idle crew reinforces an allied crew in combat), shared with
    // DriveHelicopterGoal via VehicleTargeting.
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    private final GroundVehicleNodeEvaluator nodeEvaluator = new GroundVehicleNodeEvaluator();
    private final PathFinder pathFinder = new PathFinder(this.nodeEvaluator, 512);

    private VehicleEntity vehicle;

    private Path currentPath;
    private int pathRecalcCooldown;
    private int pathAge;
    private BlockPos lastPathTarget;

    private Vec3 lastStuckPos;
    private float lastStuckYaw;
    private int stuckTicks;
    private int unstickTicksLeft;
    private boolean unstickSwingLeft;

    private int weaponSwitchCooldown;
    // Which ROLE the last selection picked (VehicleWeapons.WEAPON_*), or UNCLASSIFIED.
    // Cached because getWeaponIndex() can't answer this — see selectWeaponForTarget.
    private int selectedRole = VehicleWeapons.UNCLASSIFIED;

    public DriveVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.sensor = new GroundTerrainSensor(unit);
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

        this.vehicle = v;
        this.sensor.attach(v);
        this.breaker.attach(v);
        return getTargetPos() != null; // only drive if there's somewhere to go
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getVehicle() == this.vehicle
                && this.vehicle != null
                && this.vehicle.getFirstPassenger() == this.unit
                && !this.vehicle.isWreck()
                && getTargetPos() != null;
    }

    // The stuck detector, retreat-episode detection and the steering ramp all assume one
    // tick() per game tick; vanilla only ticks running goals every OTHER tick.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        // The decoy input is latched vehicle state (stopVehicleMovement doesn't touch it), so
        // a crew leaving mid-retreat must let go of it here or the launcher keeps volleying
        // smoke forever.
        stopVehicleMovement();
        this.vehicle.setDecoyInputDown(false);
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.pathRecalcCooldown = 0;
        this.selectedRole = VehicleWeapons.UNCLASSIFIED;
        this.allyAssist.clear();
        this.sensor.clear();
        this.breaker.clear();
        this.brain.clear();
        clearRecovery();
    }

    @Override
    public void tick() {
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
        this.pathAge++;

        // Re-read the battlefield and, on its own ~1s cadence, re-decide. Cheap on the ticks it
        // does nothing, which is most of them.
        this.brain.update(this.unit, this.vehicle);

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

        BlockPos targetPos = getTargetPos();
        if (targetPos == null) {
            stopVehicleMovement();
            clearRecovery(); // no task — nothing to be stuck against
            return;
        }

        // A cruise is the one task a contact does not take the wheel away from: the route is the
        // player's standing instruction and the crew fights from it, on the move. Everything the
        // fight needs that is NOT movement still runs — weapon choice and the fire assist — and the
        // hull then carries straight on to its leg below. A badly hurt crew is the exception: that
        // is not "engaging", it is dying, and preserveRetreat inside fightTick still wins.
        if (target != null) {
            if (PatrolSupport.isCruising(this.unit) && !isLowHealth()) {
                selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
                fireAssistIfSpecial(target);
            } else {
                fightTick(target);
                return;
            }
        }

        double distanceSq = this.vehicle.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);

        // Arrival is measured HORIZONTALLY, though distanceSq stays 3D for navigateTo (which only
        // feeds it to the steering ramp). A formation slot's Y is a terrain probe, so a hull
        // parked on a rise a few blocks above its slot must still read as arrived — against the
        // tight formation tolerance a 3D test would leave it grinding at the slot forever.
        double dx = targetPos.getX() + 0.5 - this.vehicle.getX();
        double dz = targetPos.getZ() + 0.5 - this.vehicle.getZ();
        double arrive = VehicleTargeting.arrivalDistance(this.unit, this.vehicle);
        if (dx * dx + dz * dz > arrive * arrive) {
            navigateTo(targetPos, distanceSq);
        } else {
            parkOnStation();
            clearRecovery(); // parked at destination
        }
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
            stopVehicleMovement();
            return;
        }
        faceHeading(axis);
    }

    /**
     * Turn in place onto {@code dir}, stopping once inside the deadband.
     *
     * <p>Deliberately NOT {@link #holdAtEdge}: that one FORCES a left turn when already aligned,
     * because it is scanning for a way past an obstacle and its rotation is what keeps
     * {@link #updateStuck} from firing a blind unstick reverse. Sharing it here would leave the
     * whole formation slowly pirouetting in place. Nothing is needed to keep the stuck detector
     * quiet here — it only runs inside {@link #navigateTo}, and the parked branch clears it.
     */
    private void faceHeading(Vec3 dir) {
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, dir);
        if (Math.abs(angle) < FACING_DEADBAND_RAD) {
            stopVehicleMovement();
            return;
        }
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);
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
        if (plan != Action.RETREAT && plan != Action.DEPLOY_SMOKE) {
            BlockPos orbit = this.breaker.update(target, combatPos, ring);
            if (orbit != null) {
                this.brain.force(breakerGoesLeft() ? Action.FLANK_LEFT : Action.FLANK_RIGHT,
                        this.unit.level().getGameTime());
                // Going through navigateTo is deliberate: it restores the stuck detector and the
                // terrain sensor for the duration, so the breaker can't wedge the hull on the way
                // to ground it likes better.
                navigateTo(orbit, distanceSq);
                return;
            }
        }

        switch (plan) {
            case RETREAT -> retreatFromTarget(combatPos, ring + PRESERVE_RETREAT_MARGIN, distanceSq);

            // Smoke is a screened withdrawal, not a standalone puff: the launcher fires along the
            // turret vector (already tracking the threat) while the hull falls back behind it.
            case DEPLOY_SMOKE -> preserveRetreat(target, category);

            case ADVANCE -> navigateTo(combatPos, distanceSq);

            case FLANK_LEFT -> navigateTo(
                    VehicleTargeting.computeStandoffPoint(this.vehicle, combatPos, ring, FLANK_ARC_RAD),
                    distanceSq);
            case FLANK_RIGHT -> navigateTo(
                    VehicleTargeting.computeStandoffPoint(this.vehicle, combatPos, ring, -FLANK_ARC_RAD),
                    distanceSq);

            case HOLD -> {
                stopVehicleMovement();
                clearRecovery(); // holding on purpose — not stuck
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
                java.util.Set.of(kind));
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
     * All order-driven movement funnels through here (approach, MOVE_TO_POSITION,
     * FOLLOW/formations, retreat's drive branch, the breaker's orbit).
     *
     * <p>The governing rule, learned the hard way: while there is somewhere to go, drive EVERY
     * tick and never release the steering inputs. SuperbWarfare ramps a tracked hull's turn
     * rate only while left/right stays held (holdTick); the instant the inputs are released
     * the rate collapses back to a crawl. The old path-authoritative version stopped the hull
     * on any tick the pathfinder had no fresh route, which reset that ramp constantly and left
     * the tank pivoting in place forever. So the pathfinder is purely advisory: we steer toward
     * its next waypoint when it has one, and straight at the destination when it doesn't — but
     * we always steer.
     */
    private void navigateTo(BlockPos dest, double distanceSq) {
        // Wedged on terrain: back up and swing the tail for a moment, then repath. Inputs stay
        // engaged throughout, so this never stalls the steering ramp.
        if (this.unstickTicksLeft > 0) {
            this.unstickTicksLeft--;
            this.vehicle.setForwardInputDown(false);
            this.vehicle.setBackInputDown(true);
            this.vehicle.setLeftInputDown(this.unstickSwingLeft);
            this.vehicle.setRightInputDown(!this.unstickSwingLeft);
            return;
        }

        if (updateStuck()) {
            // Alternate the swing direction each time so we don't wedge the same way.
            this.unstickSwingLeft = !this.unstickSwingLeft;
            this.unstickTicksLeft = UNSTICK_DURATION;
            this.stuckTicks = 0;
            this.currentPath = null;      // the route we were on led into the wall
            this.pathRecalcCooldown = 0;  // let it repath the instant we're free
            return;
        }

        driveGroundVehicle(getSteerTarget(dest), distanceSq);
    }

    /**
     * The waypoint to steer at: the pathfinder's next reachable node when it has a usable
     * route, otherwise the destination itself. Never returns null — a missing path means
     * "steer straight", not "stop", because stopping kills the turn ramp.
     */
    private BlockPos getSteerTarget(BlockPos dest) {
        double targetDriftSq = this.lastPathTarget == null
                ? Double.MAX_VALUE
                : this.lastPathTarget.distSqr(dest);
        boolean pathStale = this.currentPath == null
                || this.currentPath.isDone()
                || this.pathAge > MAX_PATH_AGE_TICKS
                || targetDriftSq > PATH_TARGET_DRIFT_SQ;
        // A far jump in destination (order change, retreat flip) means the route in hand points
        // somewhere we no longer want to go, so ignore the throttle and repath immediately
        // rather than coast toward the stale goal for ~20 ticks.
        boolean destJumped = targetDriftSq > PATH_ABANDON_DRIFT_SQ;

        // Refresh on the throttle only; between refreshes keep following the path in hand (a
        // route to where the target was a few blocks ago is still a fine approximation) so
        // steering stays continuous.
        if (pathStale && (this.pathRecalcCooldown <= 0 || destJumped)) {
            recomputePath(dest);
            this.lastPathTarget = dest;
            this.pathAge = 0;
            // Terrain won't have changed next tick — back off harder after a failed search.
            this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
        }

        // Consume every node we've already reached (measured from the LIVE hull position),
        // then aim at the first one still ahead. Re-deriving this from position each tick —
        // instead of advancing once per tick unconditionally — is what keeps the aimed
        // waypoint fixed while the hull turns in place, rather than marching down the path and
        // swinging the steer angle around.
        while (this.currentPath != null && !this.currentPath.isDone()) {
            BlockPos node = this.currentPath.getNextNodePos();
            double nodeDistSq = this.vehicle.distanceToSqr(
                    node.getX() + 0.5, this.vehicle.getY(), node.getZ() + 0.5);
            if (nodeDistSq >= NODE_REACHED_SQ) return node;
            this.currentPath.advance();
        }
        return dest; // no usable path (or path exhausted) — steer straight at the goal
    }

    private void recomputePath(BlockPos target) {
        try {
            BlockPos origin = this.vehicle.blockPosition();
            PathNavigationRegion region = new PathNavigationRegion(
                    this.unit.level(),
                    origin.offset(-PATH_SEARCH_RANGE, -PATH_SEARCH_VERTICAL, -PATH_SEARCH_RANGE),
                    origin.offset(PATH_SEARCH_RANGE, PATH_SEARCH_VERTICAL, PATH_SEARCH_RANGE));
            // PathFinder.findPath() calls nodeEvaluator.prepare()/done() itself.
            this.currentPath = this.pathFinder.findPath(
                    region, this.unit, Set.of(target), PATH_SEARCH_RANGE, 1, 1.0F);
        } catch (Exception e) {
            this.currentPath = null;
        }
    }

    /**
     * True once the hull has gone STUCK_TICKS_THRESHOLD ticks without either moving or turning
     * while being told to drive. Rotation counts as progress, so a legitimate (even slow)
     * turn-in-place is never flagged — only a hull truly pinned on terrain.
     */
    private boolean updateStuck() {
        Vec3 pos = this.vehicle.position();
        float yaw = this.vehicle.getYRot();
        boolean moved = this.lastStuckPos == null
                || pos.distanceToSqr(this.lastStuckPos) > STUCK_MOVE_EPSILON_SQ
                || Math.abs(Mth.degreesDifference(yaw, this.lastStuckYaw)) > STUCK_YAW_EPSILON_DEG;
        if (moved) {
            this.lastStuckPos = pos;
            this.lastStuckYaw = yaw;
            this.stuckTicks = 0;
            return false;
        }
        return ++this.stuckTicks > STUCK_TICKS_THRESHOLD;
    }

    /**
     * Drop stuck/unstick state. Called whenever the goal isn't actively driving (no task,
     * holding the standoff band, parked) so a fresh drive starts clean.
     */
    private void clearRecovery() {
        this.stuckTicks = 0;
        this.unstickTicksLeft = 0;
        this.lastStuckPos = null;
    }

    private void driveGroundVehicle(BlockPos targetPos, double distanceSq) {
        Vec3 desired = new Vec3(
                targetPos.getX() - this.vehicle.getX(),
                0,
                targetPos.getZ() - this.vehicle.getZ()
        ).normalize();

        boolean avoidance = this.sensor.enabled();
        Vec3 steer = desired;
        if (avoidance) {
            steer = this.sensor.chooseClearBearing(desired);
            if (steer == null) {
                // Boxed in on every probed bearing — hold at the edge, turning in place toward the
                // goal rather than ploughing in.
                //
                // The route is dropped on the way out because the fan is centred on the bearing to
                // the next PATH NODE: if every approach to it is fouled, the route we are on is the
                // thing that is wrong, and holding at the edge cannot fix it. Rotation counts as
                // progress to updateStuck, so nothing else would ever repath this hull — it would
                // pivot at the wall for good. The recalc cooldown bounds how often that costs a
                // search.
                this.currentPath = null;
                holdAtEdge(desired);
                return;
            }
        }

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, steer);
        double angleThreshold = getRotationStopAngle(distanceSq);
        // Only translate when the direction the hull would actually move (its facing) is itself
        // clear — while it is still swinging toward the chosen detour bearing the nose may
        // still point at the hazard.
        boolean facingClear = !avoidance
                || this.sensor.headingClear(horizontalFacing(forward), this.sensor.lookahead());

        if (Math.abs(angle) < angleThreshold) {
            if (facingClear) {
                moveVehicleForward();
            } else {
                holdAtEdge(steer);
            }
        } else {
            this.vehicle.setLeftInputDown(angle > 0);
            this.vehicle.setRightInputDown(angle < 0);
            this.vehicle.setForwardInputDown(!this.hull.isTracked() && facingClear);
            this.vehicle.setBackInputDown(false);
        }
    }

    /**
     * Turn in place toward {@code dir} with no forward/back input. When already nearly aligned
     * (the hazard is dead ahead), force a consistent turn so the hull keeps rotating — this
     * both scans for an opening and keeps updateStuck (rotation counts as progress) from
     * firing a blind unstick reverse.
     */
    private void holdAtEdge(Vec3 dir) {
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, dir);
        boolean left = Math.abs(angle) < 0.05 || angle > 0;
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(left);
        this.vehicle.setRightInputDown(!left);
    }

    /**
     * Hold an armored target at the far standoff ring: close in when beyond it, open the
     * distance back out when inside it, and hold when on it. This is what stops two hulls from
     * creeping into a point-blank standstill where the cannon/TOW can't be brought to bear —
     * the deadband gives the ring width so the hull settles instead of dithering forward and
     * back across the exact radius.
     */
    private void maintainVehicleStandoff(BlockPos targetPos, double distanceSq, double dist,
                                         TargetCategory category) {
        double ring = Facts.preferredRange(category);
        double deadband = Facts.rangeDeadband(category);
        if (dist > ring + deadband) {
            navigateTo(targetPos, distanceSq);
        } else if (dist < ring - deadband) {
            retreatFromTarget(targetPos, ring, distanceSq);
        } else {
            stopVehicleMovement(); // on the ring — hold and let the turret work
            clearRecovery();
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
    static boolean isLowHealth(VehicleEntity vehicle) {
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
            stopVehicleMovement();
            clearRecovery();
            return;
        }
        retreatFromTarget(threatPos, breakDistance, distanceSq);
    }

    /**
     * Open distance back out to {@code retreatRadius}. Only reverse when the target is actually
     * in front (gun and front armor stay on it while distance grows). Anywhere else, reversing
     * is wrong — e.g. target behind the hull after driving past it — so pathfind forward to a
     * standoff point at the radius instead.
     */
    private void retreatFromTarget(BlockPos targetPos, double retreatRadius, double distanceSq) {
        Vec3 toTarget = new Vec3(
                targetPos.getX() + 0.5 - this.vehicle.getX(),
                0,
                targetPos.getZ() + 0.5 - this.vehicle.getZ()
        ).normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angleToTarget = VehicleTargeting.signedAngleTo(forward, toTarget);

        boolean canReverse = Math.abs(angleToTarget) <= REVERSE_FACING_CONE_RAD;
        // Don't back into water or lava. If the ground behind the hull is a hazard, pathfind
        // forward to a standoff point instead of reversing blindly.
        if (canReverse && this.sensor.enabled()) {
            Vec3 behind = new Vec3(-forward.x, 0, -forward.z).normalize();
            if (!this.sensor.headingClear(behind, this.sensor.lookahead())) canReverse = false;
        }

        if (canReverse) {
            reverseFromTarget(angleToTarget, distanceSq);
        } else {
            // The standoff point is pathfound to via the node evaluator, so it still respects
            // hazards like the water margin. Ring math is shared with the flight goal.
            navigateTo(VehicleTargeting.computeStandoffPoint(this.vehicle, targetPos, retreatRadius),
                    distanceSq);
        }
    }

    /** Keep facing the target so the turret stays on it, but drive in reverse to open distance. */
    private void reverseFromTarget(double angle, double distanceSq) {
        boolean aligned = Math.abs(angle) < getRotationStopAngle(distanceSq);
        this.vehicle.setLeftInputDown(!aligned && angle > 0);
        this.vehicle.setRightInputDown(!aligned && angle < 0);
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
    }

    private void moveVehicleForward() {
        this.vehicle.setForwardInputDown(true);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    }

    private void stopVehicleMovement() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
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
        this.selectedRole = VehicleWeapons.selectWeaponForTarget(this.vehicle, seatIndex, target);
        this.weaponSwitchCooldown = SewvConfig.WEAPON_SWITCH_COOLDOWN_TICKS.get();
    }

    // Destination resolution — SEM order queue for PMC, current target / ally-assist for
    // RU/US — is shared with DriveHelicopterGoal. See VehicleTargeting.
    private BlockPos getTargetPos() {
        return VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
    }

    private static Vec3 horizontalFacing(Vector3f forward) {
        return new Vec3(forward.x, 0, forward.z).normalize();
    }

    private double getRotationStopAngle(double distanceSq) {
        return Mth.clampedLerp(MIN_ANGLE_RAD, MAX_ANGLE_RAD,
                Mth.inverseLerp(Math.sqrt(distanceSq), MIN_DISTANCE, MAX_DISTANCE));
    }
}
