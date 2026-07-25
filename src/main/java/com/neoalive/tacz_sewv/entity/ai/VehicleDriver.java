package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Vector3f;

import java.util.Set;

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
final class VehicleDriver {

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
    // drive, it is wedged on terrain — reverse and swing the tail out for a moment, then repath.
    // Rotation counts as progress so a slow turn-in-place is never mistaken for being stuck.
    private static final int STUCK_TICKS_THRESHOLD = 40;      // 2s of no movement AND no rotation
    private static final double STUCK_MOVE_EPSILON_SQ = 0.04; // <0.2 block moved = no headway
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;  // <1° turned = no rotation
    private static final int UNSTICK_DURATION = 16;           // reverse-and-swing for ~0.8s

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
    private boolean unstickSwingLeft;

    VehicleDriver(AbstractUnit unit, HullFacts hull) {
        this.unit = unit;
        this.hull = hull;
        this.sensor = new GroundTerrainSensor(unit);
    }

    void attach(VehicleEntity v) {
        this.vehicle = v;
        this.sensor.attach(v);
    }

    /** Age the pathfinding throttles. Call once per game tick before any steering. */
    void tickTimers() {
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
    void navigateTo(BlockPos dest, double distanceSq) {
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
     * Open the distance back out to {@code retreatRadius}.
     *
     * <p>Only reverses when the target is actually in front, so the gun and the front armor stay on
     * it while the distance grows. Anywhere else — a target behind the hull after driving past it —
     * reversing is wrong, so it pathfinds forward to a standoff point instead.
     */
    void retreatFrom(BlockPos targetPos, double retreatRadius, double distanceSq) {
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
            // hazards like the water margin. Ring math is shared with the flight goal.
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
    void faceHeading(Vec3 dir) {
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
    void stop() {
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(false);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    }

    /**
     * Drop stuck/unstick state. Called whenever the goal isn't actively driving (no task, holding
     * the standoff band, parked) so a fresh drive starts clean.
     */
    void clearRecovery() {
        this.stuckTicks = 0;
        this.unstickTicksLeft = 0;
        this.lastStuckPos = null;
    }

    /** Forget the hull entirely, for a crew leaving its seat. */
    void clear() {
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
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
            recomputePath(dest);
            this.lastPathTarget = dest;
            this.pathAge = 0;
            // Terrain won't have changed next tick — back off harder after a failed search.
            this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
        }

        // Consume every node we've already reached (measured from the LIVE hull position), then aim
        // at the first one still ahead. Re-deriving this from position each tick — instead of
        // advancing once per tick unconditionally — is what keeps the aimed waypoint fixed while the
        // hull turns in place, rather than marching down the path and swinging the steer angle.
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
        // clear — while it is still swinging toward the chosen detour bearing the nose may still
        // point at the hazard.
        boolean facingClear = !avoidance
                || this.sensor.headingClear(horizontalFacing(forward), this.sensor.lookahead());

        if (Math.abs(angle) < angleThreshold) {
            if (facingClear) {
                this.vehicle.setForwardInputDown(true);
                this.vehicle.setBackInputDown(false);
                this.vehicle.setLeftInputDown(false);
                this.vehicle.setRightInputDown(false);
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
     * Turn in place toward {@code dir} with no forward/back input. When already nearly aligned (the
     * hazard is dead ahead), force a consistent turn so the hull keeps rotating — this both scans
     * for an opening and keeps {@link #updateStuck} from firing a blind unstick reverse.
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

    private double getRotationStopAngle(double distanceSq) {
        return Mth.clampedLerp(MIN_ANGLE_RAD, MAX_ANGLE_RAD,
                Mth.inverseLerp(Math.sqrt(distanceSq), MIN_DISTANCE, MAX_DISTANCE));
    }

    private static Vec3 horizontalFacing(Vector3f forward) {
        return new Vec3(forward.x, 0, forward.z).normalize();
    }
}
