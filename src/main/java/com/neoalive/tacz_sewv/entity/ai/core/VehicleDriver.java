package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.debug.PathingPerf;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundMobility;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import com.neoalive.tacz_sewv.entity.ai.sensor.GroundTerrainSensor;
import com.neoalive.tacz_sewv.entity.ai.support.RepairLockSupport;

/**
 * Makes a ground hull go where it is told. Knows nothing about why.
 *
 * <p>This is the whole of the Action layer's locomotion: pathfinding, obstacle avoidance, the
 * steering ramp, and the recovery from being wedged on terrain. {@link DriveVehicleGoal} decides
 * <em>where</em> — from {@link com.neoalive.tacz_sewv.entity.ai.utility.TacticalBrain} — and calls
 * one of these to get there. Split out because the two are genuinely different jobs with no shared
 * state beyond the hull, and having them in one class meant a 900-line file where a tactical
 * question and an A* throttle sat in the same scroll.
 *
 * <p><b>The governing rule, learned the hard way: while there is somewhere to go, drive EVERY tick
 * and never release the steering inputs.</b> SuperbWarfare ramps a tracked hull's turn rate only
 * while left/right stays held; the instant the inputs are released the rate collapses back to a
 * crawl. So every method here ends with inputs set, and {@link #stop()} is a real instruction
 * rather than the absence of one.
 */
public final class VehicleDriver {

    // Angle thresholds, tight when close, laxer when far (from SuperbRecruitz)
    private static final double MIN_ANGLE_RAD = Math.toRadians(3.0);
    private static final double MAX_ANGLE_RAD = Math.toRadians(22.5);
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 20.0;

    // How far off a held bearing a parked hull tolerates before correcting. Without a deadband the
    // hull hunts across the exact bearing forever, since it can only turn in discrete held steps.
    private static final double FACING_DEADBAND_RAD = Math.toRadians(8.0);

    // Reversing only opens distance while the target sits inside this frontal cone; beyond it,
    // backing up moves the hull sideways or INTO the target.
    private static final double REVERSE_FACING_CONE_RAD = Math.toRadians(75.0);

    // Pathfinding throttles: A* over the vehicle's block volume is the most expensive thing this
    // does, so a still-valid path is reused instead of recomputed on a fixed timer.
    private static final int PATH_RECALC_COOLDOWN = 20;        // min ticks between searches
    private static final int PATH_FAIL_COOLDOWN = 60;          // back off after a failed search
    private static final int MAX_PATH_AGE_TICKS = 100;         // force a refresh even if "valid"
    private static final double PATH_TARGET_DRIFT_SQ = 9.0;    // target moved >3 blocks → refresh
    private static final double PATH_ABANDON_DRIFT_SQ = 256.0; // dest jumped >16 blocks → repath now
    // How close (squared) the hull must be to a waypoint to treat it as reached and aim at the
    // next one. Re-evaluated from the live position every tick, so the aimed waypoint stays put
    // while the hull turns in place instead of jittering.
    private static final double NODE_REACHED_SQ = 9.0;
    // 32 horizontal keeps the chunk snapshot at 5×5 chunks instead of 9×9; targets beyond it still
    // get a partial path that walks us closer. Ground vehicles never need a 64-block-tall volume.
    private static final int PATH_SEARCH_RANGE = 32;
    private static final int PATH_SEARCH_VERTICAL = 16;

    // Stuck recovery: if the hull neither moves nor turns for this long while it is being told to
    // drive, it is wedged on terrain — straight reverse for a moment, then repath. No left/right
    // swing: alternating sides on a high-centered pit edge just rocks the hull in place (the
    // photo's side-to-side twitch). Rotation counts as progress so a slow turn-in-place is never
    // mistaken for being stuck.
    private static final int STUCK_TICKS_THRESHOLD = 40;      // 2s of no movement AND no rotation
    private static final double STUCK_MOVE_EPSILON_SQ = 0.04; // <0.2 block moved = no headway
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;  // <1° turned = no rotation
    private static final int UNSTICK_DURATION = 24;           // straight reverse ~1.2s (matches bank-lip)
    private static final int UNSTICK_COOLDOWN = 60;           // sit out ~3s before stuck can re-fire

    // Bank-lip faceplant: dry-over-deep-water center + full map blocked, with no
    // positional progress. Distinct from ordinary stuck (which ignores rotation) and from the wet
    // escape hatch (isInWater / amphibious). ~2s of full-fan rejection before reversing off the lip.
    private static final int BANK_LIP_BLOCK_TICKS = 40;
    private static final int BANK_LIP_REVERSE_DURATION = 24; // ~1.2s straight reverse off the lip

    // Hull-fan faceplant: the whisker fan is fully blocked (any reason — terrain, another
    // vehicle, an on-foot ally standing in the way), with nowhere to turn. Parallel to bank-lip —
    // not merged, since bank-lip's dry-over-deep-water case gets first refusal.
    //
    // Deliberately NOT gated on a block-duration threshold or a "hull-dominated" reason filter,
    // and deliberately has no in-place pivot-and-wait fallback (an earlier "holdAtEdge" turned in
    // place hoping an opening would appear, with a hysteresis band and a flip counter to keep it
    // from rocking). Both were live-tested and both leaked: a wedged hull either sat pinned
    // turning one way forever (no flips to count, so no escalation ever fired) or, once an
    // escalation existed, still visibly wiggled while the counter accumulated. A blocked fan is
    // tried against a safe retreat (see armFanReverse) the INSTANT it happens — never blind, it
    // still probes -desired/+25/-25 and only backs into a heading that tests clear first — and if
    // no retreat is safe either, the hull simply stops and re-probes next tick. No pivoting, no
    // hysteresis state, no counter: the only two outcomes are "back away" or "hold still", both
    // of which converge properly once the obstruction (terrain, traffic, a knot of allies) clears.
    private static final int HULL_FAN_REVERSE_DURATION = 24;
    private static final double[] HULL_FAN_RETREAT_OFFSETS_DEG = {0.0, 25.0, -25.0};

    // Submerged failsafe: bypasses the sensor/pathfinder entirely, direct steer to nearest land.
    private static final int SUBMERGED_ESCAPE_RADIUS = 8;

    // Same bare angle>0 sign-test bug, third location: driveFaceAndReverse's facing correction
    // has only a symmetric deadband (FACING_DEADBAND_RAD), no directional memory, so a hull that
    // can't cleanly back away (colliding with terrain while reversing) flip-flops its facing
    // turn every tick while setBackInputDown stays held the whole time — "wiggle and also goes
    // backwards", live-tested as a distinct occurrence from the pure-pivot case above.
    private static final double REVERSE_FACE_HYSTERESIS_RAD = Math.toRadians(15.0);

    private final AbstractUnit unit;
    private final HullFacts hull;
    private final GroundTerrainSensor sensor;
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
    private int unstickCooldown;
    private BlockPos lastLoggedSteerTarget;
    private BlockPos lastPathNode;

    private int bankLipFanBlockedTicks;
    private int bankLipReverseTicksLeft;
    private Vec3 lastBankLipPos;
    private Vec3 bankLipReverseAway; // horizontal direction to keep facing while reversing

    private int hullFanReverseTicksLeft;
    private Vec3 hullFanFaceDesired; // face the fouled goal / obstacle while reversing

    // 0 = undecided, >0 = committed left, <0 = committed right — see driveFaceAndReverse.
    private int reverseFaceTurn;

    // 0 = undecided, >0 = committed left, <0 = committed right — see retreatFrom. Separate from
    // reverseFaceTurn: a live combat retreat and a stuck-recovery reverse are different episodes
    // and must not share commitment state.
    private int retreatTurn;

    /** When true, forward throttle is duty-cycled ~half (infantry-cover pace). */
    private boolean infantryPace;

    public VehicleDriver(AbstractUnit unit, HullFacts hull) {
        this.unit = unit;
        this.hull = hull;
        this.sensor = new GroundTerrainSensor(unit);
    }

    public void setInfantryPace(boolean pace) {
        this.infantryPace = pace;
    }

    public void attach(VehicleEntity v) {
        this.vehicle = v;
        this.sensor.attach(v);
    }

    /** Age the pathfinding throttles. Call once per game tick before any steering. */
    public void tickTimers() {
        if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
        this.pathAge++;
    }

    /**
     * Drive toward {@code dest}.
     *
     * <p>The pathfinder is purely advisory: we steer toward its next waypoint when it has one, and
     * straight at the destination when it doesn't — but we always steer. An earlier
     * path-authoritative version stopped the hull on any tick the pathfinder had no fresh route,
     * which reset the turn ramp constantly and left the tank pivoting in place forever.
     */
    public void navigateTo(BlockPos dest, double distanceSq) {
        if (RepairLockSupport.isLocked(this.vehicle)) { stop(); return; }
        if (checkSubmergedFailsafe()) return;
        dest = com.neoalive.tacz_sewv.compat.ExterminationPodAvoidance.adjust(this.vehicle, dest);
        // Bank-lip reverse: face the blocked destination (usually into the water) and reverse off
        // the overhang. Abort if SBW reports wet — that is the existing escape-hatch case, not
        // this recovery.
        if (this.bankLipReverseTicksLeft > 0) {
            if (this.vehicle.isInWater()) {
                SewvDiag.waterEvent("bankLip reverse ABORT wet unit={}#{} vehicle={}#{} pos={}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        this.vehicle.blockPosition());
                this.bankLipReverseTicksLeft = 0;
                this.bankLipFanBlockedTicks = 0;
            } else {
                this.bankLipReverseTicksLeft--;
                driveFaceAndReverse(this.bankLipReverseAway);
                if (this.bankLipReverseTicksLeft == 0) {
                    this.currentPath = null;
                    this.pathRecalcCooldown = 0;
                    this.bankLipFanBlockedTicks = 0;
                    SewvDiag.waterEvent("bankLip reverse END unit={}#{} vehicle={}#{} pos={} — resume pathing",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            this.vehicle.blockPosition());
                }
                return;
            }
        }

        // Hull-fan reverse: face the fouled desired bearing and reverse along a pre-cleared
        // retreat. Separate gate from bank-lip — see armFanReverse.
        if (this.hullFanReverseTicksLeft > 0) {
            this.hullFanReverseTicksLeft--;
            driveFaceAndReverse(this.hullFanFaceDesired);
            if (this.hullFanReverseTicksLeft == 0) {
                this.currentPath = null;
                this.pathRecalcCooldown = 0;
                SewvDiag.pathingEvent("hullFan reverse END unit={}#{} vehicle={}#{} pos={} — resume pathing",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        this.vehicle.blockPosition());
            }
            return;
        }

        // Wedged on terrain: straight reverse (same shape as bank-lip), then repath. Inputs stay
        // engaged throughout, so this never stalls the steering ramp.
        if (this.unstickTicksLeft > 0) {
            this.unstickTicksLeft--;
            this.vehicle.setForwardInputDown(false);
            this.vehicle.setBackInputDown(true);
            this.vehicle.setLeftInputDown(false);
            this.vehicle.setRightInputDown(false);
            if (this.unstickTicksLeft == 0) {
                this.unstickCooldown = UNSTICK_COOLDOWN;
            }
            return;
        }

        // After an unstick, do not immediately plough forward into the same snag — and do not
        // re-arm stuck recovery until the cooldown elapses. If the reverse already freed us,
        // resume normal steering.
        if (this.unstickCooldown > 0) {
            this.unstickCooldown--;
            Vec3 pos = this.vehicle.position();
            boolean freed = this.lastStuckPos != null
                    && pos.distanceToSqr(this.lastStuckPos) > STUCK_MOVE_EPSILON_SQ * 4.0;
            if (!freed) {
                stop();
                return;
            }
        } else if (updateStuck()) {
            this.unstickTicksLeft = UNSTICK_DURATION;
            this.stuckTicks = 0;
            if (SewvDiag.groundPathingVerbose()) {
                SewvDiag.pathing("stuck unit={}#{} vehicle={}#{} pos={} yaw={} -> unstick reverse dropPath",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        this.vehicle.blockPosition(), this.vehicle.getYRot());
            }
            this.currentPath = null;      // the route we were on led into the wall
            this.pathRecalcCooldown = 0;  // let it repath the instant we're free
            return;
        }

        driveGroundVehicle(getSteerTarget(dest), distanceSq);
    }

    /**
     * Open the distance back out to {@code retreatRadius}.
     *
     * <p>Only reverses when the target is actually in front, so the gun and the front armor stay on
     * it while the distance grows. Anywhere else — a target behind the hull after driving past it —
     * reversing is wrong, so it pathfinds forward to a standoff point instead.
     *
     * <p>The left/right choice while reversing carries the same hysteresis as
     * {@link #driveFaceAndReverse} and for the same reason: this runs during a live combat
     * break-off, not just stuck recovery, and a bare angle sign test wiggles whenever the target
     * (which can itself be moving) sits close to dead-ahead — reported as visible wiggling while
     * reversing even with no terrain obstruction involved. {@link #retreatTurn} is its own field,
     * separate from {@link #reverseFaceTurn}: a combat retreat and a stuck-recovery reverse are
     * different episodes and must not share commitment state.
     */
    public void retreatFrom(BlockPos targetPos, double retreatRadius, double distanceSq) {
        if (RepairLockSupport.isLocked(this.vehicle)) { stop(); return; }
        if (checkSubmergedFailsafe()) return;
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
            // Keep facing the target so the turret stays on it, but drive in reverse.
            boolean aligned = Math.abs(angleToTarget) < getRotationStopAngle(distanceSq);
            boolean left;
            if (this.retreatTurn > 0) {
                left = angleToTarget > -REVERSE_FACE_HYSTERESIS_RAD;
            } else if (this.retreatTurn < 0) {
                left = angleToTarget > REVERSE_FACE_HYSTERESIS_RAD;
            } else {
                left = angleToTarget > 0;
            }
            this.retreatTurn = aligned ? 0 : (left ? 1 : -1);
            this.vehicle.setLeftInputDown(!aligned && left);
            this.vehicle.setRightInputDown(!aligned && !left);
            this.vehicle.setForwardInputDown(false);
            this.vehicle.setBackInputDown(true);
        } else {
            this.retreatTurn = 0;
            // The standoff point is pathfound to via the node evaluator, so it still respects
            // over-ford-depth water. Ring math is shared with the flight goal.
            navigateTo(VehicleTargeting.computeStandoffPoint(this.vehicle, targetPos, retreatRadius),
                    distanceSq);
        }
    }

    /**
     * Turn in place onto {@code dir}, stopping once inside the deadband. Used both to hold a
     * formation heading and, from {@link #driveGroundVehicle}, to keep turning toward an
     * already-chosen clear {@code steer} while the hull's own facing hasn't caught up to it yet —
     * a plain deadband is correct there since {@code steer} is known-good, not a scan for one.
     */
    public void faceHeading(Vec3 dir) {
        if (RepairLockSupport.isLocked(this.vehicle)) { stop(); return; }
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, dir);
        if (Math.abs(angle) < FACING_DEADBAND_RAD) {
            stop();
            return;
        }
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);
    }

    /** Release every steering input. An instruction to hold, not the absence of one. */
    public void stop() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    }

    /**
     * Drop stuck/unstick state. Called whenever the goal isn't actively driving (no task, holding
     * the standoff band, parked) so a fresh drive starts clean.
     */
    public void clearRecovery() {
        this.stuckTicks = 0;
        this.unstickTicksLeft = 0;
        this.unstickCooldown = 0;
        this.lastStuckPos = null;
        this.bankLipFanBlockedTicks = 0;
        this.bankLipReverseTicksLeft = 0;
        this.lastBankLipPos = null;
        this.bankLipReverseAway = null;
        this.hullFanReverseTicksLeft = 0;
        this.hullFanFaceDesired = null;
        this.reverseFaceTurn = 0;
        this.retreatTurn = 0;
    }

    /** Forget the hull entirely, for a crew leaving its seat. */
    public void clear() {
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.lastLoggedSteerTarget = null;
        this.lastPathNode = null;
        this.pathRecalcCooldown = 0;
        this.infantryPace = false;
        this.sensor.clear();
        clearRecovery();
    }

    /**
     * The waypoint to steer at: the pathfinder's next reachable node when it has a usable route,
     * otherwise the destination itself. Never returns null — a missing path means "steer straight",
     * not "stop", because stopping kills the turn ramp.
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
        // somewhere we no longer want to go, so ignore the throttle and repath immediately rather
        // than coast toward the stale goal for ~20 ticks.
        boolean destJumped = targetDriftSq > PATH_ABANDON_DRIFT_SQ;

        // Refresh on the throttle only; between refreshes keep following the path in hand (a route
        // to where the target was a few blocks ago is still a fine approximation) so steering
        // stays continuous.
        if (pathStale && (this.pathRecalcCooldown <= 0 || destJumped)) {
            if (SewvDiag.groundPathingVerbose()) {
                SewvDiag.pathing("repath START unit={}#{} vehicle={}#{} dest={} stale={} done={} age={} cooldown={} driftSq={} destJumped={} pathNull={}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        dest,
                        pathStale,
                        this.currentPath != null && this.currentPath.isDone(),
                        this.pathAge,
                        this.pathRecalcCooldown,
                        targetDriftSq,
                        destJumped,
                        this.currentPath == null);
            }
            long t0 = System.nanoTime();
            recomputePath(dest);
            PathingPerf.pathNanos += System.nanoTime() - t0;
            PathingPerf.pathCalls++;
            this.lastPathTarget = dest;
            this.pathAge = 0;
            // Terrain won't have changed next tick — back off harder after a failed search.
            this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
            if (SewvDiag.groundPathingVerbose()) {
                SewvDiag.pathing("repath RESULT unit={}#{} vehicle={}#{} found={} nextNode={} nextIndex={} nodeCount={} cooldown={}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        this.currentPath != null,
                        nextNode(this.currentPath),
                        nextIndex(this.currentPath),
                        nodeCount(this.currentPath),
                        this.pathRecalcCooldown);
            }
        }

        // Consume every node we've already reached (measured from the LIVE hull position), then aim
        // at the first one still ahead. Re-deriving this from position each tick — instead of
        // advancing once per tick unconditionally — is what keeps the aimed waypoint fixed while the
        // hull turns in place, rather than marching down the path and swinging the steer angle.
        while (this.currentPath != null && !this.currentPath.isDone()) {
            BlockPos node = this.currentPath.getNextNodePos();
            double nodeDistSq = this.vehicle.distanceToSqr(
                    node.getX() + 0.5, this.vehicle.getY(), node.getZ() + 0.5);
            if (nodeDistSq >= NODE_REACHED_SQ) {
                notePathNode(node);
                logSteerTarget("pathNode", node);
                return node;
            }
            if (SewvDiag.groundPathingVerbose()) {
                SewvDiag.pathing("path advance unit={}#{} vehicle={}#{} reachedNode={} distSq={} nextIndex={} nodeCount={}",
                        this.unit.getClass().getSimpleName(), this.unit.getId(),
                        this.vehicle.getName().getString(), this.vehicle.getId(),
                        node, nodeDistSq, nextIndex(this.currentPath), nodeCount(this.currentPath));
            }
            this.currentPath.advance();
        }
        notePathNode(dest);
        logSteerTarget("directDest", dest);
        return dest; // no usable path (or path exhausted) — steer straight at the goal
    }

    private void notePathNode(BlockPos node) {
        if (this.lastPathNode != null && !this.lastPathNode.equals(node)) {
            PathingPerf.pathFlips++;
        }
        this.lastPathNode = node;
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

    private void driveGroundVehicle(BlockPos targetPos, double distanceSq) {
        Vec3 desired = new Vec3(
                targetPos.getX() - this.vehicle.getX(),
                0,
                targetPos.getZ() - this.vehicle.getZ()
        ).normalize();

        boolean avoidance = this.sensor.enabled();
        Vec3 steer = desired;
        if (avoidance) {
            steer = this.sensor.chooseClearBearing(desired, this.sensor.lookahead(), this.stuckTicks > 0);
            if (steer == null) {
                // Boxed in on every probed bearing. No pivot-and-hope here — see the doc on
                // HULL_FAN_REVERSE_DURATION for why: try a safe retreat immediately.
                //
                // The route is dropped on the way out because the fan is centred on the bearing to
                // the next PATH NODE: if every approach to it is fouled, the route we are on is the
                // thing that is wrong, and a reverse in place cannot fix it either. The recalc
                // cooldown bounds how often that costs a search.
                if (SewvDiag.groundPathingVerbose()) {
                    SewvDiag.pathing("bearing BLOCKED unit={}#{} vehicle={}#{} target={} desired={} dropPath inWater={} pos={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            targetPos, desired, this.vehicle.isInWater(), this.vehicle.blockPosition());
                }
                this.currentPath = null;
                if (noteBankLipFanBlocked(desired)) {
                    return; // reverse recovery started — inputs already set
                }
                if (armFanReverse(desired, "boxedIn")) {
                    return; // reverse armed — inputs already set
                }
                // No safe retreat this tick either — hold still and re-probe next tick rather than
                // pivot blind. updateStuck's own straight-reverse fallback still applies if this
                // persists (position AND yaw both genuinely static, unlike a pivot that always
                // "moves").
                stop();
                return;
            }
        }
        this.bankLipFanBlockedTicks = 0;

        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, steer);
        double angleThreshold = getRotationStopAngle(distanceSq);
        // Only translate when the direction the hull would actually move (its facing) is itself
        // clear — while it is still swinging toward the chosen detour bearing the nose may still
        // point at the hazard.
        boolean facingClear = !avoidance
                || this.sensor.headingClear(horizontalFacing(forward), this.sensor.lookahead());

        if (Math.abs(angle) < angleThreshold) {
            if (facingClear) {
                boolean throttle = !this.infantryPace
                        || (this.unit.level().getGameTime() & 1L) == 0L;
                this.vehicle.setForwardInputDown(throttle);
                this.vehicle.setBackInputDown(false);
                this.vehicle.setLeftInputDown(false);
                this.vehicle.setRightInputDown(false);
            } else {
                // steer is a real, clear heading; the hull just hasn't finished turning onto it
                // yet, so keep turning — not a stuck case, faceHeading's plain deadband is enough.
                if (SewvDiag.groundPathingVerbose()) {
                    SewvDiag.pathing("forward BLOCKED unit={}#{} vehicle={}#{} target={} desired={} steer={} angleDeg={} thresholdDeg={} inWater={} pos={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            targetPos, desired, steer,
                            Math.toDegrees(angle), Math.toDegrees(angleThreshold),
                            this.vehicle.isInWater(), this.vehicle.blockPosition());
                }
                faceHeading(steer);
            }
        } else {
            this.vehicle.setLeftInputDown(angle > 0);
            this.vehicle.setRightInputDown(angle < 0);
            this.vehicle.setForwardInputDown(!this.hull.isTracked() && facingClear);
            this.vehicle.setBackInputDown(false);
        }
    }

    /**
     * Count sustained full-fan blocks while the center column is a dry bank-lip hazard. Returns
     * true if reverse recovery has been armed this tick (caller must not also stop-and-reprobe).
     */
    private boolean noteBankLipFanBlocked(Vec3 desired) {
        if (!this.sensor.isDryBankLipHazard()) {
            this.bankLipFanBlockedTicks = 0;
            this.lastBankLipPos = null;
            return false;
        }
        Vec3 pos = this.vehicle.position();
        boolean moved = this.lastBankLipPos != null
                && pos.distanceToSqr(this.lastBankLipPos) > STUCK_MOVE_EPSILON_SQ;
        if (moved) {
            this.bankLipFanBlockedTicks = 0;
            this.lastBankLipPos = pos;
            return false;
        }
        if (this.lastBankLipPos == null) this.lastBankLipPos = pos;
        this.bankLipFanBlockedTicks++;
        if (this.bankLipFanBlockedTicks < BANK_LIP_BLOCK_TICKS) return false;

        this.bankLipReverseAway = desired;
        this.bankLipReverseTicksLeft = BANK_LIP_REVERSE_DURATION;
        this.bankLipFanBlockedTicks = 0;
        this.reverseFaceTurn = 0;
        SewvDiag.waterEvent(
                "bankLip reverse START unit={}#{} vehicle={}#{} pos={} inWater={} desired={} "
                        + "threshold={} duration={} — dry bank lip, full fan blocked, no progress",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                this.vehicle.blockPosition(), this.vehicle.isInWater(), desired,
                BANK_LIP_BLOCK_TICKS, BANK_LIP_REVERSE_DURATION);
        driveFaceAndReverse(this.bankLipReverseAway);
        return true;
    }

    /**
     * Probe {@code -desired}, then ±25°, for a heading actually clear to reverse into — never
     * blind-reverse into a second hazard. First clear wins; arms the shared hull-fan reverse
     * countdown checked at the top of {@link #navigateTo}. Called directly, immediately, from
     * {@link #driveGroundVehicle} the instant the fan is fully blocked, whatever the reason
     * (terrain, another vehicle, an on-foot ally in the way) — see the doc on
     * {@link #HULL_FAN_REVERSE_DURATION} for why there is no threshold or pivot fallback anymore.
     *
     * @param trigger which caller armed this, for the log line only
     * @return true when reverse was armed this tick (inputs already set); false when retreat was
     *         skipped (logs {@code SKIP allRetreatBlocked}) so the caller can stop and re-probe
     */
    private boolean armFanReverse(Vec3 desired, String trigger) {
        Vec3 retreatBase = desired.scale(-1.0);
        if (retreatBase.lengthSqr() < 1.0E-8) {
            Vector3f forward = this.vehicle.getForwardDirection().normalize();
            retreatBase = new Vec3(-forward.x, 0, -forward.z);
        } else {
            retreatBase = retreatBase.normalize();
        }

        double look = this.sensor.lookahead();
        Vec3 chosenRetreat = null;
        for (double offDeg : HULL_FAN_RETREAT_OFFSETS_DEG) {
            Vec3 candidate = offDeg == 0.0
                    ? retreatBase
                    : VehicleTargeting.rotateY(retreatBase, Math.toRadians(offDeg));
            if (this.sensor.headingClear(candidate, look)) {
                chosenRetreat = candidate;
                break;
            }
        }

        if (chosenRetreat == null) {
            SewvDiag.pathingEvent(
                    "hullFan reverse SKIP allRetreatBlocked unit={}#{} vehicle={}#{} pos={} "
                            + "desired={} reasons=[{}] trigger={} "
                            + "probed=-desired,+25,-25 — holding, no blind reverse",
                    this.unit.getClass().getSimpleName(), this.unit.getId(),
                    this.vehicle.getName().getString(), this.vehicle.getId(),
                    this.vehicle.blockPosition(), desired, this.sensor.lastFanReasons(), trigger);
            return false;
        }

        // Face opposite the cleared retreat so reverse translation follows that bearing
        // (straight -desired when that probe won; a ±25° diagonal otherwise).
        this.hullFanFaceDesired = chosenRetreat.scale(-1.0).normalize();
        this.hullFanReverseTicksLeft = HULL_FAN_REVERSE_DURATION;
        this.reverseFaceTurn = 0;
        SewvDiag.pathingEvent(
                "hullFan reverse START unit={}#{} vehicle={}#{} pos={} desired={} retreat={} face={} "
                        + "reasons=[{}] trigger={} "
                        + "duration={} — face opposite retreat, reverse along cleared bearing",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                this.vehicle.blockPosition(), desired, chosenRetreat, this.hullFanFaceDesired,
                this.sensor.lastFanReasons(), trigger,
                HULL_FAN_REVERSE_DURATION);
        driveFaceAndReverse(this.hullFanFaceDesired);
        return true;
    }

    /**
     * Turn to face {@code face} while backing up. Same hysteresis shape as {@link #retreatFrom}
     * and for the same reason: a hull that can't back away cleanly (colliding with terrain while
     * reversing) will swing past a bare deadband and bounce back, flip-flopping the facing turn
     * every tick while {@code setBackInputDown} stays held throughout — visibly "wiggling while
     * going backwards". {@link #reverseFaceTurn} is reset wherever a reverse is freshly armed
     * (bank-lip, hull-fan), so each episode starts undecided.
     */
    private void driveFaceAndReverse(Vec3 face) {
        Vec3 away = face;
        if (away == null || away.lengthSqr() < 1.0E-8) {
            Vector3f forward = this.vehicle.getForwardDirection().normalize();
            away = new Vec3(forward.x, 0, forward.z);
        }
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, away);
        boolean aligned = Math.abs(angle) < FACING_DEADBAND_RAD;
        boolean left;
        if (this.reverseFaceTurn > 0) {
            left = angle > -REVERSE_FACE_HYSTERESIS_RAD;
        } else if (this.reverseFaceTurn < 0) {
            left = angle > REVERSE_FACE_HYSTERESIS_RAD;
        } else {
            left = angle > 0;
        }
        this.reverseFaceTurn = aligned ? 0 : (left ? 1 : -1);
        this.vehicle.setLeftInputDown(!aligned && left);
        this.vehicle.setRightInputDown(!aligned && !left);
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
    }

    /**
     * Last-resort surfacing: if the hull's own hitbox is entirely inside fluid, drop every normal
     * concern (order, path, recovery state) and drive straight at the nearest dry cell within
     * {@link #SUBMERGED_ESCAPE_RADIUS} blocks. Returns true when it took over steering this tick.
     */
    private boolean checkSubmergedFailsafe() {
        if (GroundMobility.isAmphibious(this.vehicle) || !isFullySubmerged()) return false;
        BlockPos escape = nearestDryCell();
        if (escape == null) {
            // Nothing dry within reach this tick — hold rather than guess; the loop re-checks
            // every tick, so as soon as a reachable cell exists (current or drift) this resumes.
            stop();
            return true;
        }
        SewvDiag.waterEvent(
                "submerged FAILSAFE unit={}#{} vehicle={}#{} pos={} escape={} — hull hitbox fully in fluid",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                this.vehicle.blockPosition(), escape);
        this.currentPath = null;
        this.pathRecalcCooldown = 0;
        clearRecovery();
        driveDirectAt(escape);
        return true;
    }

    /** True iff every block cell the hull's bounding box overlaps is fluid — not a probe sample,
     * the actual hitbox, so this cannot be fooled by whatever blind spot let the hull get here. */
    private boolean isFullySubmerged() {
        AABB box = this.vehicle.getBoundingBox();
        Level level = this.unit.level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) return false;
                }
            }
        }
        return true;
    }

    /** Nearest dry LAND within {@link #SUBMERGED_ESCAPE_RADIUS} blocks — the actual ground
     * surface of each column, not just any non-fluid cell. A raw "not fluid" scan finds the
     * solid lakebed a couple of blocks straight down from a fully submerged hull just as
     * readily as it finds a real bank; that cell is embedded rock, not somewhere to drive, so
     * the hull just sat there aiming into the ground every tick. Horizontal distance only, since
     * {@link #driveDirectAt} steers horizontally — a column's own height above/below the hull
     * doesn't matter here. */
    private BlockPos nearestDryCell() {
        Level level = this.unit.level();
        BlockPos center = this.vehicle.blockPosition();
        BlockPos best = null;
        long bestDistSq = Long.MAX_VALUE;
        int r = SUBMERGED_ESCAPE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                BlockPos surface = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, center.getY(), z));
                if (Math.abs(surface.getY() - center.getY()) > r) continue;
                if (level.getFluidState(surface.below()).is(FluidTags.WATER)) continue;
                long distSq = (long) dx * dx + (long) dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = surface;
                }
            }
        }
        return best;
    }

    /** Raw point-and-go: no sensor, no fan, no pathfinder — the whole reason this exists is that
     * every one of those has been caught wrong while the hull was drowning. Same turn/throttle
     * shape as the tail of {@link #driveGroundVehicle}, just with nothing standing between the
     * hull and the escape cell. */
    private void driveDirectAt(BlockPos targetPos) {
        Vec3 desired = new Vec3(
                targetPos.getX() + 0.5 - this.vehicle.getX(),
                0,
                targetPos.getZ() + 0.5 - this.vehicle.getZ());
        if (desired.lengthSqr() < 1.0E-8) { stop(); return; }
        desired = desired.normalize();
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, desired);
        if (Math.abs(angle) < FACING_DEADBAND_RAD) {
            this.vehicle.setForwardInputDown(true);
            this.vehicle.setBackInputDown(false);
            this.vehicle.setLeftInputDown(false);
            this.vehicle.setRightInputDown(false);
        } else {
            this.vehicle.setLeftInputDown(angle > 0);
            this.vehicle.setRightInputDown(angle < 0);
            this.vehicle.setForwardInputDown(!this.hull.isTracked());
            this.vehicle.setBackInputDown(false);
        }
    }

    private double getRotationStopAngle(double distanceSq) {
        return Mth.clampedLerp(MIN_ANGLE_RAD, MAX_ANGLE_RAD,
                Mth.inverseLerp(Math.sqrt(distanceSq), MIN_DISTANCE, MAX_DISTANCE));
    }

    private static Vec3 horizontalFacing(Vector3f forward) {
        return new Vec3(forward.x, 0, forward.z).normalize();
    }

    private void logSteerTarget(String reason, BlockPos target) {
        if (!SewvDiag.groundPathingVerbose()) return;
        if (target.equals(this.lastLoggedSteerTarget)) return;
        this.lastLoggedSteerTarget = target;
        SewvDiag.pathing("steerTarget {} unit={}#{} vehicle={}#{} target={} pathPresent={} nextIndex={} nodeCount={}",
                reason,
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                target,
                this.currentPath != null,
                nextIndex(this.currentPath),
                nodeCount(this.currentPath));
    }

    private static BlockPos nextNode(Path path) {
        return path == null || path.isDone() ? null : path.getNextNodePos();
    }

    private static int nextIndex(Path path) {
        return path == null ? -1 : path.getNextNodeIndex();
    }

    private static int nodeCount(Path path) {
        return path == null ? -1 : path.getNodeCount();
    }
}
