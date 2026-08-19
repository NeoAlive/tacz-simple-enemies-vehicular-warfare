package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import com.neoalive.tacz_sewv.debug.PathingPerf;
import com.neoalive.tacz_sewv.debug.SewvDiag;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import com.neoalive.tacz_sewv.entity.ai.sensor.GroundTerrainSensor;

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

    // Hull-fan faceplant: full whisker fan blocked for hull-dominated reasons (another vehicle /
    // wreck in the inflated AABB), with no positional progress. Parallel to bank-lip — not merged.
    // Reverse only after headingClear on a retreat bearing (-desired, then ±25°).
    private static final int HULL_FAN_BLOCK_TICKS = 40;
    private static final int HULL_FAN_REVERSE_DURATION = 24;
    private static final double[] HULL_FAN_RETREAT_OFFSETS_DEG = {0.0, 25.0, -25.0};

    // holdAtEdge hysteresis: once committed to a turn direction, keep it until the bearing swings
    // clearly past center the OTHER way, rather than flipping on every sign change of a
    // near-zero angle. A bare angle>0 test with no band is exactly the "alternating sides on a
    // high-centered pit edge just rocks the hull in place" twitch the stuck-recovery comment
    // above already names — that fix (straight reverse, no swing) covers updateStuck's own
    // stuck-reverse, but holdAtEdge pivots via a completely separate path and needed the same
    // protection. The self-perpetuating trap: any yaw change >1° reads as "moved" to updateStuck
    // (STUCK_YAW_EPSILON_DEG), so an unbanded twitch never accumulates stuckTicks and the
    // intended straight-reverse fallback never fires — the hull rocks in place indefinitely.
    private static final double HOLD_AT_EDGE_HYSTERESIS_RAD = Math.toRadians(15.0);
    // A genuinely wedged hull swings PAST whatever hysteresis band it's given before bouncing
    // back off terrain — live-tested confirming this: with the band above already in place, a
    // hull with a completely frozen target/desired/position still visibly wiggled, just more
    // broadly, because the bounce itself is real physics, not noise a wider band could out-wait.
    // A few flips is enough to tell "pivoting isn't converging" from "still turning toward an
    // opening", so give up on the pivot at that point rather than let it keep rocking.
    private static final int HOLD_AT_EDGE_FLIP_LIMIT = 3;
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

    private int hullFanBlockedTicks;
    private int hullFanReverseTicksLeft;
    private Vec3 lastHullFanPos;
    private Vec3 hullFanFaceDesired; // face the fouled goal / obstacle while reversing

    // 0 = undecided, >0 = committed left, <0 = committed right — see holdAtEdge.
    private int holdAtEdgeTurn;
    // Counts commit-direction flips since the current "boxed in" episode began — see holdAtEdge
    // and noteHoldAtEdgePivotFailing.
    private int holdAtEdgeFlips;

    // 0 = undecided, >0 = committed left, <0 = committed right — see driveFaceAndReverse.
    private int reverseFaceTurn;

    public VehicleDriver(AbstractUnit unit, HullFacts hull) {
        this.unit = unit;
        this.hull = hull;
        this.sensor = new GroundTerrainSensor(unit);
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
        dest = com.neoalive.tacz_sewv.compat.ExterminationPodAvoidance.adjust(this.vehicle, dest);
        // Bank-lip reverse: face the blocked destination (usually into the water) and reverse off
        // the overhang. Runs ahead of ordinary stuck recovery because holdAtEdge rotation would
        // otherwise keep updateStuck from ever firing. Abort if SBW reports wet — that is the
        // existing escape-hatch case, not this recovery.
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
        // retreat. Same holdAtEdge trap as bank-lip (yaw clears stuck), separate gate.
        if (this.hullFanReverseTicksLeft > 0) {
            this.hullFanReverseTicksLeft--;
            driveFaceAndReverse(this.hullFanFaceDesired);
            if (this.hullFanReverseTicksLeft == 0) {
                this.currentPath = null;
                this.pathRecalcCooldown = 0;
                this.hullFanBlockedTicks = 0;
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
     */
    public void retreatFrom(BlockPos targetPos, double retreatRadius, double distanceSq) {
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
            this.vehicle.setLeftInputDown(!aligned && angleToTarget > 0);
            this.vehicle.setRightInputDown(!aligned && angleToTarget < 0);
            this.vehicle.setForwardInputDown(false);
            this.vehicle.setBackInputDown(true);
        } else {
            // The standoff point is pathfound to via the node evaluator, so it still respects
            // over-ford-depth water. Ring math is shared with the flight goal.
            navigateTo(VehicleTargeting.computeStandoffPoint(this.vehicle, targetPos, retreatRadius),
                    distanceSq);
        }
    }

    /**
     * Turn in place onto {@code dir}, stopping once inside the deadband.
     *
     * <p>Deliberately NOT {@link #holdAtEdge}: that one FORCES a left turn when already aligned,
     * because it is scanning for a way past an obstacle and its rotation is what keeps
     * {@link #updateStuck} from firing a blind unstick reverse. Sharing it here would leave a whole
     * formation slowly pirouetting in place.
     */
    public void faceHeading(Vec3 dir) {
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
        this.hullFanBlockedTicks = 0;
        this.hullFanReverseTicksLeft = 0;
        this.lastHullFanPos = null;
        this.hullFanFaceDesired = null;
        this.holdAtEdgeTurn = 0;
        this.holdAtEdgeFlips = 0;
        this.reverseFaceTurn = 0;
    }

    /** Forget the hull entirely, for a crew leaving its seat. */
    public void clear() {
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.lastLoggedSteerTarget = null;
        this.lastPathNode = null;
        this.pathRecalcCooldown = 0;
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
                // Boxed in on every probed bearing — hold at the edge, turning in place toward the
                // goal rather than ploughing in.
                //
                // The route is dropped on the way out because the fan is centred on the bearing to
                // the next PATH NODE: if every approach to it is fouled, the route we are on is the
                // thing that is wrong, and holding at the edge cannot fix it. Rotation counts as
                // progress to updateStuck, so nothing else would ever repath this hull — it would
                // pivot at the wall for good. The recalc cooldown bounds how often that costs a
                // search.
                if (SewvDiag.groundPathingVerbose()) {
                    SewvDiag.pathing("bearing BLOCKED unit={}#{} vehicle={}#{} target={} desired={} dropPath holdAtEdge inWater={} pos={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            targetPos, desired, this.vehicle.isInWater(), this.vehicle.blockPosition());
                }
                this.currentPath = null;
                if (noteBankLipFanBlocked(desired)) {
                    return; // reverse recovery started — inputs already set
                }
                if (noteHullFanBlocked(desired)) {
                    return; // reverse armed — inputs already set
                }
                if (noteHoldAtEdgePivotFailing(desired)) {
                    return; // reverse armed — inputs already set
                }
                holdAtEdge(desired);
                return;
            }
        }
        this.bankLipFanBlockedTicks = 0;
        this.hullFanBlockedTicks = 0;

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
                // Truly done with holdAtEdge for this tick — safe to drop its hysteresis state.
                // Resetting this unconditionally whenever steer != null (as an earlier version
                // did) wiped it on every tick the "forward BLOCKED" branch below also runs,
                // since that branch is reached with steer != null too — silently defeating the
                // hysteresis for that call site by resetting it moments before every use.
                this.holdAtEdgeTurn = 0;
                this.holdAtEdgeFlips = 0;
                this.vehicle.setForwardInputDown(true);
                this.vehicle.setBackInputDown(false);
                this.vehicle.setLeftInputDown(false);
                this.vehicle.setRightInputDown(false);
            } else {
                if (SewvDiag.groundPathingVerbose()) {
                    SewvDiag.pathing("forward BLOCKED unit={}#{} vehicle={}#{} target={} desired={} steer={} angleDeg={} thresholdDeg={} inWater={} pos={}",
                            this.unit.getClass().getSimpleName(), this.unit.getId(),
                            this.vehicle.getName().getString(), this.vehicle.getId(),
                            targetPos, desired, steer,
                            Math.toDegrees(angle), Math.toDegrees(angleThreshold),
                            this.vehicle.isInWater(), this.vehicle.blockPosition());
                }
                holdAtEdge(steer);
            }
        } else {
            this.holdAtEdgeTurn = 0;
            this.holdAtEdgeFlips = 0;
            this.vehicle.setLeftInputDown(angle > 0);
            this.vehicle.setRightInputDown(angle < 0);
            this.vehicle.setForwardInputDown(!this.hull.isTracked() && facingClear);
            this.vehicle.setBackInputDown(false);
        }
    }

    /**
     * Turn in place toward {@code dir} with no forward/back input. When already nearly aligned (the
     * hazard is dead ahead), force a consistent turn so the hull keeps rotating — this both scans
     * for an opening and keeps {@link #updateStuck} from firing a blind unstick reverse.
     *
     * <p>Committed via {@link #holdAtEdgeTurn} once a direction is chosen, and held through
     * {@link #HOLD_AT_EDGE_HYSTERESIS_RAD} of swing the other way before switching — each fresh
     * "boxed in" episode starts undecided again (reset alongside the other fan-block trackers).
     */
    private void holdAtEdge(Vec3 dir) {
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        double angle = VehicleTargeting.signedAngleTo(forward, dir);
        boolean left;
        if (this.holdAtEdgeTurn > 0) {
            left = angle > -HOLD_AT_EDGE_HYSTERESIS_RAD;
        } else if (this.holdAtEdgeTurn < 0) {
            left = angle > HOLD_AT_EDGE_HYSTERESIS_RAD;
        } else {
            left = Math.abs(angle) < 0.05 || angle > 0;
        }
        int newTurn = left ? 1 : -1;
        if (this.holdAtEdgeTurn != 0 && newTurn != this.holdAtEdgeTurn) this.holdAtEdgeFlips++;
        this.holdAtEdgeTurn = newTurn;
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(left);
        this.vehicle.setRightInputDown(!left);
    }

    /**
     * Count sustained full-fan blocks while the center column is a dry bank-lip hazard. Returns
     * true if reverse recovery has been armed this tick (caller must not also holdAtEdge).
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
     * Sustained hull-dominated full-fan block with no positional progress. Bank-lip owns its
     * cases first; this must not fire when {@link GroundTerrainSensor#isDryBankLipHazard()} is
     * true. Hull-dominated means {@code hullCount * 2 > n} on the fan summary (strictly more
     * than half of failed offsets are {@code hull}).
     *
     * <p>Before reversing, probes retreat bearings {@code -desired}, then ±25°. First clear
     * wins. If all fail, logs {@code SKIP allRetreatBlocked} and returns false so the caller
     * can {@link #holdAtEdge} — never blind-reverse into a second hull.
     *
     * @return true when reverse was armed this tick (inputs already set); false when the gate
     *         did not apply, the threshold is not reached, or retreat was skipped
     */
    private boolean noteHullFanBlocked(Vec3 desired) {
        if (this.sensor.isDryBankLipHazard() || !this.sensor.isLastFanHullDominated()) {
            this.hullFanBlockedTicks = 0;
            this.lastHullFanPos = null;
            return false;
        }
        Vec3 pos = this.vehicle.position();
        boolean moved = this.lastHullFanPos != null
                && pos.distanceToSqr(this.lastHullFanPos) > STUCK_MOVE_EPSILON_SQ;
        if (moved) {
            this.hullFanBlockedTicks = 0;
            this.lastHullFanPos = pos;
            return false;
        }
        if (this.lastHullFanPos == null) this.lastHullFanPos = pos;
        this.hullFanBlockedTicks++;
        if (this.hullFanBlockedTicks < HULL_FAN_BLOCK_TICKS) return false;
        this.hullFanBlockedTicks = 0;
        return armFanReverse(desired, "hullDominated");
    }

    /**
     * Repeated commit-direction flips inside {@link #holdAtEdge} mean the pivot itself keeps
     * bouncing off terrain rather than converging toward an opening — live-tested confirming that
     * widening the hysteresis band only delays this (a genuinely wedged hull still swings past
     * whatever band it's given before bouncing back). Once the flip count crosses
     * {@link #HOLD_AT_EDGE_FLIP_LIMIT}, give up on pivoting for this episode and fall back to the
     * same doctrine-approved straight reverse the other fan-block cases use, instead of letting
     * physics keep rocking the hull indefinitely.
     */
    private boolean noteHoldAtEdgePivotFailing(Vec3 desired) {
        if (this.holdAtEdgeFlips < HOLD_AT_EDGE_FLIP_LIMIT) return false;
        this.holdAtEdgeFlips = 0;
        this.holdAtEdgeTurn = 0;
        return armFanReverse(desired, "pivotFlipping");
    }

    /**
     * Probe {@code -desired}, then ±25°, for a heading actually clear to reverse into — never
     * blind-reverse into a second hazard. First clear wins; arms the shared hull-fan reverse
     * countdown checked at the top of {@link #navigateTo}. Shared by {@link #noteHullFanBlocked}
     * and {@link #noteHoldAtEdgePivotFailing} — same recovery, different triggers.
     *
     * @param trigger which caller armed this, for the log line only ({@code "hullDominated"} or
     *                 {@code "pivotFlipping"})
     * @return true when reverse was armed this tick (inputs already set); false when retreat was
     *         skipped (logs {@code SKIP allRetreatBlocked}) so the caller can {@link #holdAtEdge}
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
                            + "probed=-desired,+25,-25 — holdAtEdge, no blind reverse",
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
                        + "threshold={} duration={} — face opposite retreat, reverse along cleared bearing",
                this.unit.getClass().getSimpleName(), this.unit.getId(),
                this.vehicle.getName().getString(), this.vehicle.getId(),
                this.vehicle.blockPosition(), desired, chosenRetreat, this.hullFanFaceDesired,
                this.sensor.lastFanReasons(), trigger,
                HULL_FAN_BLOCK_TICKS, HULL_FAN_REVERSE_DURATION);
        driveFaceAndReverse(this.hullFanFaceDesired);
        return true;
    }

    /**
     * Face {@code face} (fouled goal / hazard) and reverse. Shared by bank-lip and hull-fan
     * recoveries — same "gun toward trouble, open distance the other way" shape.
     */
    /**
     * Turn to face {@code face} while backing up. Same hysteresis shape as {@link #holdAtEdge}
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
