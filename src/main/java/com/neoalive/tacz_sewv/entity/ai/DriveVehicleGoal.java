package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.VehicleWeapons.TargetCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.PathNavigationRegion;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.List;

public class DriveVehicleGoal extends Goal {

    // Angle thresholds, tight when close, laxer when far (from SuperbRecruitz)
    private static final double MIN_ANGLE_RAD = Math.toRadians(3.0);
    private static final double MAX_ANGLE_RAD = Math.toRadians(22.5);
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 20.0;
    private static final double STOP_DISTANCE = 8.0;
    // Infantry standoff band, MG's effective engagement range.
    private static final double INFANTRY_TOO_CLOSE = 10.0;
    private static final double INFANTRY_TOO_FAR = 20.0;
    // Vehicle standoff, MG can't hurt armor and cannon/TOW work at range, so armor
    // actively holds the FAR ring (VEHICLE_TOO_FAR) rather than sitting anywhere in a
    // band — a tank duel that collapses to point-blank is a tank duel getting lost.
    // A deadband around the ring keeps the hull from jittering forward/back over it.
    private static final double VEHICLE_TOO_FAR = 40.0;
    private static final double VEHICLE_RING_DEADBAND = 4.0;

    // Self-preservation: a crew that has lost most of its health breaks contact
    // rather than trading to the death — pop smoke toward the threat and fall back
    // past the standoff ring, then hold at range instead of charging back in.
    private static final float PRESERVE_HEALTH_FRACTION = 0.25F; // retreat below 1/4 health
    private static final double PRESERVE_RETREAT_MARGIN = 8.0;   // fall back this far BEYOND the ring
    private static final float PRESERVE_SMOKE_CHANCE = 0.5F;     // coin-flip, per retreat, whether to screen with smoke

    // Pathfinding throttles, A* over the vehicle's block volume is the most
    // expensive thing this goal does, so a still-valid path is reused instead
    // of recomputed on a fixed timer.
    private static final int PATH_RECALC_COOLDOWN = 20;      // min ticks between searches
    private static final int PATH_FAIL_COOLDOWN = 60;        // back off after a failed search
    private static final int MAX_PATH_AGE_TICKS = 100;       // force a refresh even if "valid"
    private static final double PATH_TARGET_DRIFT_SQ = 9.0;  // target moved >3 blocks → refresh path
    private static final double PATH_ABANDON_DRIFT_SQ = 256.0; // dest jumped >16 blocks → repath now, don't coast on the old route
    // How close (squared) the hull must be to a waypoint to treat it as reached and
    // aim at the next one. Re-evaluated from the live position every tick, so the
    // aimed waypoint stays put while the hull turns in place instead of jittering.
    private static final double NODE_REACHED_SQ = 9.0;

    // Stuck recovery: if the hull neither moves nor turns for this long while it is
    // being told to drive, it is wedged on terrain — reverse and swing the tail out
    // for a moment, then repath. Rotation counts as progress so a slow turn-in-place
    // is never mistaken for being stuck.
    private static final int STUCK_TICKS_THRESHOLD = 40;      // 2s of no movement AND no rotation
    private static final double STUCK_MOVE_EPSILON_SQ = 0.04; // <0.2 block moved = no headway
    private static final float STUCK_YAW_EPSILON_DEG = 1.0F;  // <1° turned = no rotation
    private static final int UNSTICK_DURATION = 16;           // reverse-and-swing for ~0.8s

    private final GroundVehicleNodeEvaluator nodeEvaluator = new GroundVehicleNodeEvaluator();
    private final PathFinder pathFinder = new PathFinder(this.nodeEvaluator, 512);
    private Path currentPath = null;
    private int pathRecalcCooldown = 0;
    private int pathAge = 0;
    private BlockPos lastPathTarget = null;

    private Vec3 lastStuckPos = null;
    private float lastStuckYaw = 0.0F;
    private int stuckTicks = 0;
    private int unstickTicksLeft = 0;
    private boolean unstickSwingLeft = false;

    private final AbstractUnit unit;
    private VehicleEntity vehicle;
    private int weaponSwitchCooldown = 0;

    // Self-preservation smoke episode state (see preserveRetreat).
    private long lastRetreatTick = Long.MIN_VALUE;
    private boolean deploySmokeThisRetreat = false;

    // Per-vehicle drivetrain caches: track/wheel type, buoyancy and engine type
    // don't change mid-drive, and vehicle.data().compute() is too expensive to
    // call every tick.
    private VehicleEntity trackedCacheVehicle;
    private boolean trackedCacheValue;
    private VehicleEntity amphibiousCacheVehicle;
    private boolean amphibiousCacheValue;
    private VehicleEntity helicopterCacheVehicle;
    private boolean helicopterCacheValue;

    // Per-game-tick cache of nearby vehicle obstacles (wrecks, allied hulls), each
    // box pre-inflated by our half-width. The whisker fan probes up to 9 headings a
    // tick and every probe consults this list — only the first builds it.
    private long vehicleObstacleCacheTime = Long.MIN_VALUE;
    private List<AABB> vehicleObstacles = List.of();

    // Only PmcUnitEntity (player-commandable units) has an order queue via ICommandableMob;
    // RUunitEntity/USunitEntity are plain hostile units with no order system.
    public DriveVehicleGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // driving doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) drives, enforces your driver-commander model
        if (v.getFirstPassenger() != this.unit) return false;
        this.vehicle = v;
        // Flight is DriveHelicopterGoal's job — this goal only steers ground/ship hulls.
        if (isHelicopter()) return false;
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

    // The stuck detector, retreat-episode detection and the steering ramp all
    // assume one tick() per game tick; vanilla only ticks running goals every
    // OTHER tick unless this is overridden.
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        // Release all inputs so the tank coasts to a stop when we're done.
        // The decoy input is latched vehicle state (stopVehicleMovement doesn't
        // touch it), so a crew leaving mid-retreat must let go of it here or the
        // launcher keeps volleying smoke forever.
        stopVehicleMovement();
        this.vehicle.setDecoyInputDown(false);
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.pathRecalcCooldown = 0;
        this.vehicleObstacles = List.of();
        this.vehicleObstacleCacheTime = Long.MIN_VALUE;
        this.allyAssist.clear();
        clearRecovery();
    }

    @Override
public void tick() {
    if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
    if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
    this.pathAge++;

    LivingEntity target = this.unit.getTarget();

    // The decoy input is latched vehicle state: release it on every tick that is
    // not part of a smoking retreat (preserveRetreat re-asserts it right after
    // when the episode calls for smoke), otherwise one retreat would leave the
    // launcher volleying a fresh smoke salvo every reload, forever.
    if (target == null || !isLowHealth() || !this.deploySmokeThisRetreat) {
        this.vehicle.setDecoyInputDown(false);
    }

    BlockPos targetPos = getTargetPos();
    if (targetPos == null) {
        stopVehicleMovement();
        clearRecovery(); // no task — nothing to be stuck against
        return;
    }

    if (target != null) {
        // Combat maneuvering is anchored to the TARGET, not the resolved order
        // destination — under FOLLOW/MOVE_TO/formation orders those differ, and
        // holding a standoff ring around the own commander (while weapon choice
        // tracks the actual enemy) is exactly the bug this distinction avoids.
        // Once the fight ends, the next tick resumes driving on the order.
        BlockPos combatPos = target.blockPosition();
        double distanceSq = this.vehicle.distanceToSqr(
                combatPos.getX() + 0.5, combatPos.getY(), combatPos.getZ() + 0.5);
        double dist = Math.sqrt(distanceSq);

        TargetCategory category = VehicleWeapons.classifyTarget(target);
        boolean isVehicleTarget = category == TargetCategory.VEHICLE;

        boolean tooFar = dist > (isVehicleTarget ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR);
        int seatIndex = this.vehicle.getSeatIndex(this.unit);
        if (this.weaponSwitchCooldown <= 0) {
            selectWeaponForTarget(seatIndex, category, tooFar);
        }
        // The special is a guided missile whose lofted firing solution can't pass
        // SBW's 4° straight-line gate at ring range — fire it ourselves within the
        // configured wider cone (guidance corrects the loft). Cannon/MG keep
        // firing through SBW's native precise gate.
        if (seatIndex >= 0
                && this.vehicle.getWeaponIndex(seatIndex) == VehicleWeapons.WEAPON_SPECIAL) {
            VehicleWeapons.tryAiFireAssist(this.vehicle, this.unit, target,
                    SewvConfig.AI_FIRE_ASSIST_CONE_DEG.get());
        }

        if (isLowHealth()) {
            // Badly hurt — abandon the standoff, screen with smoke and break off.
            preserveRetreat(target, category);
        } else if (isVehicleTarget) {
            // Armor holds the far ring: close in if beyond it, back off if inside it.
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
    } else {
        double distanceSq = this.vehicle.distanceToSqr(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
        double stopDistance = this.vehicle.getBbWidth() - 1 + STOP_DISTANCE;
        if (distanceSq > stopDistance * stopDistance) {
            navigateTo(targetPos, distanceSq);
        } else {
            stopVehicleMovement();
            clearRecovery(); // parked at destination
        }
    }
}

// All order-driven movement funnels through here (approach, MOVE_TO_POSITION,
// FOLLOW/formations, retreat's drive branch).
//
// The governing rule, learned the hard way: while there is somewhere to go, drive
// EVERY tick and never release the steering inputs. SuperbWarfare ramps a tracked
// hull's turn rate only while left/right stays held (holdTick); the instant the
// inputs are released the rate collapses back to a crawl. The old path-authoritative
// version stopped the hull on any tick the pathfinder had no fresh route, which
// reset that ramp constantly and left the tank pivoting in place forever. So the
// pathfinder is now purely advisory: we steer toward its next waypoint when it has
// one, and straight at the destination when it doesn't — but we always steer.
private void navigateTo(BlockPos dest, double distanceSq) {
    // Wedged on terrain: back up and swing the tail for a moment, then repath.
    // Inputs stay engaged throughout, so this never stalls the steering ramp.
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

// The waypoint to steer at: the pathfinder's next reachable node when it has a
// usable route, otherwise the destination itself. Never returns null — a missing
// path means "steer straight," not "stop," because stopping kills the turn ramp.
private BlockPos getSteerTarget(BlockPos dest) {
    double targetDriftSq = this.lastPathTarget == null
            ? Double.MAX_VALUE
            : this.lastPathTarget.distSqr(dest);
    boolean pathStale = this.currentPath == null
            || this.currentPath.isDone()
            || this.pathAge > MAX_PATH_AGE_TICKS
            || targetDriftSq > PATH_TARGET_DRIFT_SQ;
    // A far jump in destination (order change, retreat flip) means the route in
    // hand points somewhere we no longer want to go, so ignore the throttle and
    // repath immediately rather than coast toward the stale goal for ~20 ticks.
    boolean destJumped = targetDriftSq > PATH_ABANDON_DRIFT_SQ;

    // Refresh on the throttle only; between refreshes keep following the path in
    // hand (a route to where the target was a few blocks ago is still a fine
    // approximation) so steering stays continuous.
    if (pathStale && (this.pathRecalcCooldown <= 0 || destJumped)) {
        recomputePath(dest); // replaces currentPath with the fresh route (or null on failure)
        this.lastPathTarget = dest;
        this.pathAge = 0;
        // Terrain won't have changed next tick, back off harder after a failed search
        this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
    }

    if (this.currentPath != null && !this.currentPath.isDone()) {
        // Consume every node we've already reached (measured from the LIVE hull
        // position), then aim at the first one still ahead. Re-deriving this from
        // position each tick — instead of advancing once per tick unconditionally —
        // is what keeps the aimed waypoint fixed while the hull turns in place,
        // rather than marching down the path and swinging the steer angle around.
        while (!this.currentPath.isDone()) {
            BlockPos node = this.currentPath.getNextNodePos();
            double nodeDistSq = this.vehicle.distanceToSqr(node.getX() + 0.5, this.vehicle.getY(), node.getZ() + 0.5);
            if (nodeDistSq < NODE_REACHED_SQ) {
                this.currentPath.advance();
            } else {
                return node;
            }
        }
    }
    return dest; // no usable path (or path exhausted) — steer straight at the goal
}

// True once the hull has gone STUCK_TICKS_THRESHOLD ticks without either moving or
// turning while being told to drive. Rotation counts as progress, so a legitimate
// (even slow) turn-in-place is never flagged — only a hull truly pinned on terrain.
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

// Drop stuck/unstick state. Called whenever the goal isn't actively driving (no
// task, holding the standoff band, parked) so a fresh drive starts clean.
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

    boolean avoidance = SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get();
    Vec3 steer = desired;
    if (avoidance) {
        steer = chooseClearBearing(desired);
        if (steer == null) {
            // Boxed in by water/cliffs/vehicle obstacles on every probed bearing —
            // hold at the edge,
            // turning in place toward the goal rather than ploughing in. Keeping a
            // turn input held preserves the tracked turn ramp and stops updateStuck
            // (rotation counts as progress) from triggering a blind unstick reverse.
            holdAtEdge(desired);
            return;
        }
    }

    Vector3f forward = this.vehicle.getForwardDirection().normalize();
    double angle = getAngleBetween(forward, steer);
    double angleThreshold = getRotationStopAngle(distanceSq);
    // Only translate when the direction the hull would actually move (its facing)
    // is itself clear — while it is still swinging toward the chosen detour bearing
    // the nose may still point at the hazard.
    boolean facingClear = !avoidance || headingClear(horizontalFacing(forward), lookaheadDistance());

    if (Math.abs(angle) < angleThreshold) {
        if (facingClear) {
            moveVehicleForward();
        } else {
            holdAtEdge(steer);
        }
    } else {
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);

        boolean turnInPlace = isTrackedVehicle();
        this.vehicle.setForwardInputDown(!turnInPlace && facingClear);
        this.vehicle.setBackInputDown(false);
    }
}

// The performance knob and the safe-drop tolerance, read per call so config edits
// take effect live.
private double lookaheadDistance() {
    return SewvConfig.VEHICLE_LOOKAHEAD_DISTANCE.get();
}

private static Vec3 horizontalFacing(Vector3f forward) {
    return new Vec3(forward.x, 0, forward.z).normalize();
}

// Whisker scan: prefer the goal bearing, then fan out to alternating flanks. Returns
// the smallest-offset bearing whose look-ahead probe is clear, or null if the whole
// forward cone is blocked.
private static final double[] WHISKER_OFFSETS_DEG = {0.0, 25.0, -25.0, 50.0, -50.0, 75.0, -75.0};

private Vec3 chooseClearBearing(Vec3 desired) {
    double dist = lookaheadDistance();
    for (double offDeg : WHISKER_OFFSETS_DEG) {
        Vec3 cand = rotateY(desired, Math.toRadians(offDeg));
        if (headingClear(cand, dist)) return cand;
    }
    return null;
}

// Rotate a horizontal (y=0) direction about the vertical axis.
private static Vec3 rotateY(Vec3 dir, double angleRad) {
    double cos = Math.cos(angleRad);
    double sin = Math.sin(angleRad);
    return new Vec3(dir.x * cos - dir.z * sin, 0.0, dir.x * sin + dir.z * cos);
}

// Turn in place toward `dir` with no forward/back input. When already nearly aligned
// (the hazard is dead ahead), force a consistent turn so the hull keeps rotating —
// this both scans for an opening and prevents the stuck timer from firing.
private void holdAtEdge(Vec3 dir) {
    Vector3f forward = this.vehicle.getForwardDirection().normalize();
    double angle = getAngleBetween(forward, dir);
    boolean left = Math.abs(angle) < 0.05 || angle > 0;
    this.vehicle.setForwardInputDown(false);
    this.vehicle.setBackInputDown(false);
    this.vehicle.setLeftInputDown(left);
    this.vehicle.setRightInputDown(!left);
}

// True if driving `distance` blocks along `dir` from the hull crosses no hazard:
// water (unless amphibious), lava, a drop deeper than the safe-drop tolerance, or
// a vehicle obstacle (wreck or allied hull) the block sensor can't see.
private boolean headingClear(Vec3 dir, double distance) {
    boolean avoidWater = !isAmphibiousVehicle();
    int maxDrop = SewvConfig.VEHICLE_MAX_SAFE_DROP.get();
    Level level = this.unit.level();
    double startX = this.vehicle.getX();
    double startZ = this.vehicle.getZ();
    int baseY = this.vehicle.getBlockY();
    double half = this.vehicle.getBbWidth() / 2.0;
    List<AABB> obstacles = vehicleObstacles();
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    // Step ~1 block at a time from just past the hull edge out to the look-ahead range.
    for (double d = half + 0.5; d <= half + distance; d += 1.0) {
        double sampleX = startX + dir.x * d;
        double sampleZ = startZ + dir.z * d;
        if (isBlockedByVehicle(obstacles, sampleX, sampleZ)) return false;
        if (isHazardColumn(level, pos, Mth.floor(sampleX), Mth.floor(sampleZ), baseY, avoidWater, maxDrop)) {
            return false;
        }
    }
    return true;
}

// A column is hazardous if the surface the hull would drive onto is water/lava, or
// if there is no footing within `maxDrop` blocks below the driving level (a cliff).
// An uphill wall is NOT a hazard here — footing is found immediately, and walls are
// the pathfinder's / stuck-recovery's job, not the terrain sensor's.
private boolean isHazardColumn(Level level, BlockPos.MutableBlockPos pos, int x, int z,
                               int baseY, boolean avoidWater, int maxDrop) {
    // Fluid at the driving cell or the cell the tracks rest in.
    for (int dy = 0; dy >= -1; dy--) {
        var fluid = level.getFluidState(pos.set(x, baseY + dy, z));
        if (fluid.is(FluidTags.LAVA)) return true;
        if (avoidWater && fluid.is(FluidTags.WATER)) return true;
    }
    // Look for solid footing from the normal floor (baseY-1) down. Drop depth = k-1,
    // so k up to maxDrop+1 permits drops of exactly maxDrop; none found → cliff.
    for (int k = 1; k <= maxDrop + 1; k++) {
        int y = baseY - k;
        var state = level.getBlockState(pos.set(x, y, z));
        var fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return true;
        if (avoidWater && fluid.is(FluidTags.WATER)) return true;
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false; // footing within tolerance — safe column
        }
    }
    return true; // no ground within maxDrop → cliff
}

// Vehicle obstacles are entities, so the block-based hazard sensor can't see them:
// wrecks (dead hulls linger as scenery) and allied crewed vehicles must not be
// driven through, while enemy hulls stay fair game — the standoff ring keeps the
// distance, and refusing to close on an enemy "obstacle" would fight it. Our own
// hull is excluded explicitly: probes start just past our edge, so with boxes
// inflated by our half-width a self-match would flag every bearing as blocked.
private List<AABB> vehicleObstacles() {
    long now = this.unit.level().getGameTime();
    if (now != this.vehicleObstacleCacheTime) {
        this.vehicleObstacleCacheTime = now;
        double half = this.vehicle.getBbWidth() / 2.0;
        double reach = lookaheadDistance() + half + 1.0;
        // ±2 vertically: an obstacle on a drivable slope still counts, one far below
        // a cliff edge doesn't (the drop check already rejects that bearing).
        AABB search = this.vehicle.getBoundingBox().inflate(reach, 2.0, reach);
        // Inflating each box by our half-width lets the centerline point probes in
        // headingClear stand in for sweeping the full hull width along the bearing.
        this.vehicleObstacles = this.unit.level().getEntitiesOfClass(VehicleEntity.class, search,
                        v -> v != this.vehicle && isVehicleObstacle(v)).stream()
                .map(v -> v.getBoundingBox().inflate(half, 0.0, half))
                .toList();
    }
    return this.vehicleObstacles;
}

private boolean isVehicleObstacle(VehicleEntity other) {
    if (other.isWreck()) return true;
    return other.getFirstPassenger() instanceof AbstractUnit driver
            && VehicleTargeting.isSameFaction(this.unit, driver);
}

private static boolean isBlockedByVehicle(List<AABB> obstacles, double x, double z) {
    for (AABB box : obstacles) {
        if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) {
            return true;
        }
    }
    return false;
}

// Hold an armored target at the far standoff ring (VEHICLE_TOO_FAR): close in when
// beyond it, open the distance back out when inside it, and hold when on it. This is
// what stops two SuperbWarfare vehicles from creeping into a point-blank standstill
// where the cannon/TOW can't be brought to bear — the deadband gives the ring width
// so the hull settles instead of dithering forward and back across the exact radius.
private void maintainVehicleStandoff(BlockPos targetPos, double distanceSq, double dist) {
    if (dist > VEHICLE_TOO_FAR + VEHICLE_RING_DEADBAND) {
        navigateTo(targetPos, distanceSq);            // beyond the ring — close in
    } else if (dist < VEHICLE_TOO_FAR - VEHICLE_RING_DEADBAND) {
        retreatFromTarget(targetPos, VEHICLE_TOO_FAR, distanceSq); // inside the ring — back out to it
    } else {
        stopVehicleMovement();                        // on the ring — hold and let the turret work
        clearRecovery();
    }
}

// True once the hull is below the self-preservation health threshold. Vehicle health
// only falls in combat (repairs happen out of contact), so this is monotonic — no
// flicker around the threshold to guard against.
private boolean isLowHealth() {
    float max = this.vehicle.getMaxHealth();
    return max > 0.0F && this.vehicle.getHealth() < max * PRESERVE_HEALTH_FRACTION;
}

// Self-preservation once badly hurt: screen with smoke toward the threat and fall
// back past the standoff ring, then hold at range rather than re-engaging. The
// smoke is fired by raising the decoy input — the vehicle's own tick launches it
// along the turret vector (already tracking the threat), and the launcher's ready/
// reload gating means holding the input just fires each volley as it comes back up.
private void preserveRetreat(LivingEntity threat, TargetCategory category) {
    // preserveRetreat runs every tick while low on health, so rolling the dice here
    // would just stutter the held decoy input. Instead decide ONCE per retreat: a
    // gap in consecutive ticks marks a fresh episode and re-rolls the coin flip, so
    // about half of retreating crews screen with smoke and half break contact silent.
    // The sentinel is tested explicitly — now - Long.MIN_VALUE overflows negative,
    // which would silently skip the roll for the first-ever episode.
    long now = this.unit.level().getGameTime();
    if (this.lastRetreatTick == Long.MIN_VALUE || now - this.lastRetreatTick > 1) {
        this.deploySmokeThisRetreat = this.unit.getRandom().nextFloat() < PRESERVE_SMOKE_CHANCE;
    }
    this.lastRetreatTick = now;

    if (this.deploySmokeThisRetreat && this.vehicle.hasDecoy()) {
        this.vehicle.setDecoyInputDown(true);
    }

    BlockPos threatPos = threat.blockPosition();
    double distanceSq = this.vehicle.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ());
    double dist = Math.sqrt(distanceSq);
    double ringRadius = category == TargetCategory.VEHICLE ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR;
    double breakDistance = ringRadius + PRESERVE_RETREAT_MARGIN;

    if (dist > breakDistance) {
        // Clear of the ring — far enough to be safe. Hold here (still smoking) so we
        // neither sprint away forever nor charge back into the standoff.
        stopVehicleMovement();
        clearRecovery();
        return;
    }
    retreatFromTarget(threatPos, breakDistance, distanceSq);
}

// Reversing only opens distance while the target sits inside this frontal cone;
// beyond it, backing up moves the hull sideways or INTO the target.
private static final double REVERSE_FACING_CONE_RAD = Math.toRadians(75.0);

// Open distance back out to retreatRadius. Only reverse when the target is actually
// in front (gun and front armor stay on it while distance grows). Anywhere else,
// reversing is wrong — e.g. target behind the hull after driving past it — so
// pathfind forward to a standoff point at retreatRadius instead.
private void retreatFromTarget(BlockPos targetPos, double retreatRadius, double distanceSq) {
    Vec3 toTarget = new Vec3(
            targetPos.getX() + 0.5 - this.vehicle.getX(),
            0,
            targetPos.getZ() + 0.5 - this.vehicle.getZ()
    ).normalize();
    Vector3f forward = this.vehicle.getForwardDirection().normalize();
    double angleToTarget = getAngleBetween(forward, toTarget);

    boolean canReverse = Math.abs(angleToTarget) <= REVERSE_FACING_CONE_RAD;
    // Don't back off a cliff or into water. If the ground behind the hull is a hazard,
    // pathfind forward to a standoff point instead of reversing blindly.
    if (canReverse && SewvConfig.VEHICLE_TERRAIN_AVOIDANCE.get()) {
        Vec3 behind = new Vec3(-forward.x, 0, -forward.z).normalize();
        if (!headingClear(behind, lookaheadDistance())) canReverse = false;
    }

    if (canReverse) {
        reverseFromTarget(angleToTarget, distanceSq);
    } else {
        // Standoff point is pathfound to via the node evaluator, so it still respects
        // hazards like the water margin. Ring math is shared with the flight goal.
        navigateTo(VehicleTargeting.computeStandoffPoint(this.vehicle, targetPos, retreatRadius), distanceSq);
    }
}

private void reverseFromTarget(double angle, double distanceSq) {
    // Keep facing the target (so turret stays on it), but drive in REVERSE to open distance
    double angleThreshold = getRotationStopAngle(distanceSq);

    if (Math.abs(angle) < angleThreshold) {
        // Facing target, reverse straight back
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    } else {
        // Turn to face target while backing, steer + reverse
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
    }
}

private void recomputePath(BlockPos target) {
    try {
        // 32 horizontal keeps the chunk snapshot at 5×5 chunks instead of 9×9;
        // targets beyond it still get a partial path that walks us closer.
        // Ground vehicles never need a 64-block-tall search volume either.
        int range = 32;
        int vertical = 16;
        BlockPos origin = this.vehicle.blockPosition();
        PathNavigationRegion region = new PathNavigationRegion(
            this.unit.level(),
            origin.offset(-range, -vertical, -range),
            origin.offset(range, vertical, range)
        );
        // PathFinder.findPath() calls nodeEvaluator.prepare()/done() internally, don't call them here too
        java.util.Set<BlockPos> targets = java.util.Set.of(target);
        this.currentPath = this.pathFinder.findPath(region, this.unit, targets, (float) range, 1, 1.0F);
    } catch (Exception e) {
        this.currentPath = null;
    }
}

// Cached per vehicle instance, the track/wheel drivetrain doesn't change mid-drive,
// and vehicle.data().compute() is too expensive to call every tick.
private boolean isTrackedVehicle() {
    if (this.vehicle != this.trackedCacheVehicle) {
        this.trackedCacheVehicle = this.vehicle;
        this.trackedCacheValue = computeIsTrackedVehicle();
    }
    return this.trackedCacheValue;
}

private boolean computeIsTrackedVehicle() {
    try {
        var data = this.vehicle.data().compute();
        if (data != null) {
            var trackRotSpeed = data.getEngineInfo().get("TrackRotSpeed");
            return trackRotSpeed != null && trackRotSpeed.getAsInt() > 0;
        }
    } catch (Exception ignored) {}
    return false;
}

// Amphibious/floating hulls (boats, or any vehicle with positive buoyancy) are
// exempt from the water half of the terrain sensor — cliffs and lava still apply.
private boolean isAmphibiousVehicle() {
    if (this.vehicle != this.amphibiousCacheVehicle) {
        this.amphibiousCacheVehicle = this.vehicle;
        this.amphibiousCacheValue = computeIsAmphibiousVehicle();
    }
    return this.amphibiousCacheValue;
}

private boolean computeIsAmphibiousVehicle() {
    try {
        EngineInfo engine = this.vehicle.getEngineInfo();
        if (engine == null) return false;
        // Any positive buoyancy means it floats rather than sinking; Ship engines are
        // amphibious by construction. On any error, default to non-amphibious so the
        // safe behavior (avoid water) is the fallback.
        return engine instanceof EngineInfo.Ship || engine.getBuoyancy() > 0.0;
    } catch (Exception ignored) {}
    return false;
}

// Helicopters (and fixed-wing, which subclass EngineInfo.Helicopter) are flown
// by DriveHelicopterGoal instead — this goal declines them in canUse.
private boolean isHelicopter() {
    if (this.vehicle != this.helicopterCacheVehicle) {
        this.helicopterCacheVehicle = this.vehicle;
        this.helicopterCacheValue = this.vehicle.getEngineInfo() instanceof EngineInfo.Helicopter;
    }
    return this.helicopterCacheValue;
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

    // Ground doctrine (classification, cannon/special round-robin vs armor, MG
    // range split vs infantry) lives in VehicleWeapons; only the switch cooldown
    // lives here. The flight goal uses its own random-cycle doctrine instead.
    private void selectWeaponForTarget(int seatIndex, TargetCategory category, boolean tooFar) {
        if (seatIndex < 0 || this.weaponSwitchCooldown > 0) return;
        VehicleWeapons.selectWeaponForTarget(this.vehicle, seatIndex, category, tooFar);
        this.weaponSwitchCooldown = SewvConfig.WEAPON_SWITCH_COOLDOWN_TICKS.get();
    }

    // Mutual support scanner (idle crew reinforces an allied crew in combat), shared
    // with DriveHelicopterGoal via VehicleTargeting.
    private final VehicleTargeting.AllyAssist allyAssist = new VehicleTargeting.AllyAssist();

    // Destination resolution — SEM order queue for PMC, current target / ally-assist
    // for RU/US — is shared with DriveHelicopterGoal. See VehicleTargeting.
    private BlockPos getTargetPos() {
        return VehicleTargeting.resolveDestination(this.unit, this.vehicle, this.allyAssist);
    }

    private double getAngleBetween(Vector3f forward, Vec3 target) {
        double cross = forward.x * target.z - forward.z * target.x;
        double dot = forward.x * target.x + forward.z * target.z;
        return -Math.atan2(cross, dot);
    }

    private double getRotationStopAngle(double distanceSq) {
        double distance = Math.sqrt(distanceSq);
        double t = (distance - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);
        t = Math.max(0.0, Math.min(1.0, t));
        return MIN_ANGLE_RAD + (MAX_ANGLE_RAD - MIN_ANGLE_RAD) * t;
    }
}