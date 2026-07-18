package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
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
 * Drives a ground or ship hull for its crew: where to be relative to the target, how to get
 * there, and which weapon to have selected when it arrives.
 *
 * <p>Flight is {@link DriveHelicopterGoal}'s job — both goals are registered on every crew
 * and this one declines helicopters in {@link #canUse}.
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

    // Infantry standoff band, MG's effective engagement range.
    private static final double INFANTRY_TOO_CLOSE = 10.0;
    private static final double INFANTRY_TOO_FAR = 20.0;

    // Vehicle standoff: MG can't hurt armor and cannon/TOW work at range, so armor actively
    // holds the FAR ring rather than sitting anywhere in a band — a tank duel that collapses
    // to point-blank is a tank duel getting lost. A deadband around the ring keeps the hull
    // from jittering forward/back over it.
    private static final double VEHICLE_TOO_FAR = 40.0;
    private static final double VEHICLE_RING_DEADBAND = 4.0;

    // Self-preservation: a crew that has lost most of its health breaks contact rather than
    // trading to the death — pop smoke toward the threat and fall back past the standoff
    // ring, then hold at range instead of charging back in.
    private static final float PRESERVE_HEALTH_FRACTION = 0.25F; // retreat below 1/4 health
    private static final double PRESERVE_RETREAT_MARGIN = 8.0;   // fall back this far BEYOND the ring
    private static final float PRESERVE_SMOKE_CHANCE = 0.5F;

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
    private final DecoyEpisode smoke = new DecoyEpisode();
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
        if (this.hull.isHelicopter()) return false;

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
        clearRecovery();
    }

    @Override
    public void tick() {
        if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
        if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
        this.pathAge++;

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

        // The decoy input is latched vehicle state: release it on every tick that is not part
        // of a smoking retreat (preserveRetreat re-asserts it right after when the episode
        // calls for smoke), otherwise one retreat would leave the launcher volleying a fresh
        // smoke salvo every reload, forever.
        if (target == null || !isLowHealth() || !this.smoke.isDeploying()) {
            this.vehicle.setDecoyInputDown(false);
        }

        BlockPos targetPos = getTargetPos();
        if (targetPos == null) {
            stopVehicleMovement();
            clearRecovery(); // no task — nothing to be stuck against
            return;
        }

        if (target != null) {
            fightTick(target);
            return;
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
     * The combat doctrine, in precedence order: break contact when badly hurt, break a
     * stalemate when we hold a target we can't hit, otherwise hold the standoff band for the
     * target's type.
     *
     * <p>Anchored to the TARGET, not the resolved order destination — under FOLLOW/MOVE_TO/
     * formation orders those differ, and holding a standoff ring around our own commander
     * (while weapon choice tracks the actual enemy) is exactly the bug this distinction
     * avoids. Once the fight ends, the next tick resumes driving on the order.
     */
    private void fightTick(LivingEntity target) {
        BlockPos combatPos = target.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(
                combatPos.getX() + 0.5, combatPos.getY(), combatPos.getZ() + 0.5);
        double dist = Math.sqrt(distanceSq);

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        boolean isVehicleTarget = category == TargetCategory.VEHICLE;
        boolean tooFar = dist > (isVehicleTarget ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR);

        selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), target);
        fireAssistIfSpecial(target);

        if (isLowHealth()) {
            // Badly hurt — abandon the standoff, screen with smoke and break off. The breaker
            // is deliberately NOT consulted here: a retreating crew is SUPPOSED to be holding
            // fire, so its silence is success, not a stall. Letting the breaker see it would
            // drag it back onto the ring and fight preserveRetreat for the hull every tick.
            preserveRetreat(target, category);
            return;
        }

        // Sits ABOVE the doctrine because the doctrine's hold branches are exactly what freeze
        // the crew in place.
        BlockPos orbit = this.breaker.update(target, combatPos, breakerRing(isVehicleTarget));
        if (orbit != null) {
            // Going through navigateTo is deliberate: it restores the stuck detector and the
            // terrain sensor for the duration, so the breaker can't wedge the hull on the way
            // to ground it likes better.
            navigateTo(orbit, distanceSq);
        } else if (isVehicleTarget) {
            maintainVehicleStandoff(combatPos, distanceSq, dist);
        } else if (dist < INFANTRY_TOO_CLOSE) {
            // Infantry: a wide comfortable band inside the MG's effective range.
            retreatFromTarget(combatPos, (INFANTRY_TOO_CLOSE + INFANTRY_TOO_FAR) / 2.0, distanceSq);
        } else if (tooFar) {
            navigateTo(combatPos, distanceSq);
        } else {
            stopVehicleMovement();
            clearRecovery(); // holding the band on purpose — not stuck
        }
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
     * <p>Once out they stay out: they revert to ordinary SEM infantry and are simply picked up
     * again by whatever puts a unit in a seat. There is no recall, deliberately — a walk-back
     * state machine is a lot of moving parts for infantry that has already done its job.
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

        // Copied because stopRiding() mutates the passenger list underneath us.
        for (Entity passenger : List.copyOf(this.vehicle.getPassengers())) {
            if (!(passenger instanceof AbstractUnit rider) || rider == this.unit) continue;
            int seat = this.vehicle.getSeatIndex(rider);
            if (seat < 0 || crew.contains(seat)) continue;
            rider.stopRiding();
        }
    }

    /** The ring the breaker orbits for this target type — mid-band against infantry. */
    private static double breakerRing(boolean isVehicleTarget) {
        return isVehicleTarget ? VEHICLE_TOO_FAR : (INFANTRY_TOO_CLOSE + INFANTRY_TOO_FAR) / 2.0;
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
                // Boxed in by water/lava/hull obstacles on every probed bearing — hold at the
                // edge, turning in place toward the goal rather than ploughing in.
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
    private void maintainVehicleStandoff(BlockPos targetPos, double distanceSq, double dist) {
        if (dist > VEHICLE_TOO_FAR + VEHICLE_RING_DEADBAND) {
            navigateTo(targetPos, distanceSq);
        } else if (dist < VEHICLE_TOO_FAR - VEHICLE_RING_DEADBAND) {
            retreatFromTarget(targetPos, VEHICLE_TOO_FAR, distanceSq);
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
        boolean screen = this.smoke.roll(
                this.unit.level().getGameTime(), this.unit.getRandom(), PRESERVE_SMOKE_CHANCE);
        if (screen && this.vehicle.hasDecoy()) {
            this.vehicle.setDecoyInputDown(true);
        }

        BlockPos threatPos = threat.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ());
        double ringRadius = category == TargetCategory.VEHICLE ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR;
        double breakDistance = ringRadius + PRESERVE_RETREAT_MARGIN;

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
        double distance = Math.sqrt(distanceSq);
        double t = Mth.clamp((distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE), 0.0, 1.0);
        return MIN_ANGLE_RAD + (MAX_ANGLE_RAD - MIN_ANGLE_RAD) * t;
    }
}
