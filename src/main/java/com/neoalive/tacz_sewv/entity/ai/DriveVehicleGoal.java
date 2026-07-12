package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.PathNavigationRegion;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.UUID;

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
    private static final int WEAPON_CANNON = 0;
    private static final int WEAPON_MG = 1;
    private static final int WEAPON_SPECIAL = 2; // TOW / heavy anti-vehicle ordnance
    private static final int WEAPON_COUNT = 3;

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

    private VehicleEntity trackedCacheVehicle;
    private boolean trackedCacheValue;

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

    @Override
    public void stop() {
        // Release all inputs so the tank coasts to a stop when we're done
        stopVehicleMovement();
        this.vehicle = null;
        this.currentPath = null;
        this.lastPathTarget = null;
        this.pathRecalcCooldown = 0;
        this.assistAlly = null;
        clearRecovery();
    }

    @Override
public void tick() {
    if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
    if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
    this.pathAge++;

    BlockPos targetPos = getTargetPos();
    if (targetPos == null) {
        stopVehicleMovement();
        clearRecovery(); // no task — nothing to be stuck against
        return;
    }

    // Distance to the REAL target, used for standoff band and firing range
    double distanceSq = this.vehicle.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());

    boolean isCombat = this.unit.getTarget() != null;

    if (isCombat) {
        LivingEntity target = this.unit.getTarget();
        TargetCategory category = classifyTarget(target);
        boolean isVehicleTarget = category == TargetCategory.VEHICLE;

        double dist = Math.sqrt(distanceSq); // distance to REAL target

        boolean tooFar = dist > (isVehicleTarget ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR);
        if (this.weaponSwitchCooldown <= 0) {
            selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), category, tooFar);
        }

        if (isLowHealth()) {
            // Badly hurt — abandon the standoff, screen with smoke and break off.
            preserveRetreat(target, category);
        } else if (isVehicleTarget) {
            // Armor holds the far ring: close in if beyond it, back off if inside it.
            maintainVehicleStandoff(targetPos, distanceSq, dist);
        } else if (dist < INFANTRY_TOO_CLOSE) {
            // Infantry: a wide comfortable band inside the MG's effective range.
            retreatFromTarget(targetPos, (INFANTRY_TOO_CLOSE + INFANTRY_TOO_FAR) / 2.0, distanceSq);
        } else if (tooFar) {
            navigateTo(targetPos, distanceSq);
        } else {
            stopVehicleMovement();
            clearRecovery(); // holding the band on purpose — not stuck
        }
    } else {
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
    Vec3 toTarget = new Vec3(
            targetPos.getX() - this.vehicle.getX(),
            0,
            targetPos.getZ() - this.vehicle.getZ()
    ).normalize();

    Vector3f forward = this.vehicle.getForwardDirection().normalize();
    double angle = getAngleBetween(forward, toTarget);
    double angleThreshold = getRotationStopAngle(distanceSq);

    if (Math.abs(angle) < angleThreshold) {
        moveVehicleForward();
    } else {
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);

        boolean turnInPlace = isTrackedVehicle();
        this.vehicle.setForwardInputDown(!turnInPlace);
        this.vehicle.setBackInputDown(false);
    }
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
private long lastRetreatTick = Long.MIN_VALUE;
private boolean deploySmokeThisRetreat = false;

private void preserveRetreat(LivingEntity threat, TargetCategory category) {
    // preserveRetreat runs every tick while low on health, so rolling the dice here
    // would just stutter the held decoy input. Instead decide ONCE per retreat: a
    // gap in consecutive ticks marks a fresh episode and re-rolls the coin flip, so
    // about half of retreating crews screen with smoke and half break contact silent.
    long now = this.unit.level().getGameTime();
    if (now - this.lastRetreatTick > 1) {
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

    if (Math.abs(angleToTarget) <= REVERSE_FACING_CONE_RAD) {
        reverseFromTarget(angleToTarget, distanceSq);
    } else {
        navigateTo(computeStandoffPoint(targetPos, retreatRadius), distanceSq);
    }
}

// Point at `radius` straight out from the target through the vehicle — the ring the
// hull should fall back to (mid-band for soft targets, the far ring for armor, or
// beyond it when breaking contact). Pathfound to via the node evaluator, so it
// still respects hazards like the water margin.
private BlockPos computeStandoffPoint(BlockPos targetPos, double radius) {
    double cx = targetPos.getX() + 0.5;
    double cz = targetPos.getZ() + 0.5;
    double dx = this.vehicle.getX() - cx;
    double dz = this.vehicle.getZ() - cz;
    double len = Math.sqrt(dx * dx + dz * dz);
    if (len < 1.0E-3) {
        // Practically on top of the target — flee along the current facing
        Vector3f forward = this.vehicle.getForwardDirection().normalize();
        dx = forward.x;
        dz = forward.z;
        len = 1.0;
    }
    double scale = radius / len;
    return BlockPos.containing(cx + dx * scale, this.vehicle.getY(), cz + dz * scale);
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

    private enum TargetCategory { VEHICLE, MONSTER, PMC_UNIT }

    private TargetCategory classifyTarget(LivingEntity target) {
        // A VehicleEntity isn't a LivingEntity, so it can never be the target itself
        // the AI targets the crew riding inside; armor makes the MG useless against them.
        if (target.getVehicle() instanceof VehicleEntity) return TargetCategory.VEHICLE;
        if (target instanceof RUunitEntity || target instanceof USunitEntity) return TargetCategory.PMC_UNIT;
        return TargetCategory.MONSTER; // vanilla hostiles + fallback default
    }

    // Weighted pick: each category excludes the weapon(s) that can't/shouldn't
    // engage it, then prefers one of what's left based on range.
    private void selectWeaponForTarget(int seatIndex, TargetCategory category, boolean tooFar) {
        double[] weight = new double[WEAPON_COUNT];

        switch (category) {
            case VEHICLE:
                weight[WEAPON_MG] = Double.NEGATIVE_INFINITY; // small arms can't hurt armor
                weight[tooFar ? WEAPON_SPECIAL : WEAPON_CANNON] = 1.0;
                break;
            case MONSTER:
            case PMC_UNIT: // same doctrine as monsters, don't burn heavy ordnance on infantry
                weight[WEAPON_SPECIAL] = Double.NEGATIVE_INFINITY;
                weight[tooFar ? WEAPON_CANNON : WEAPON_MG] = 1.0;
                break;
        }

        // Not every vehicle has a 3rd weapon slot, setWeaponIndex() doesn't
        // bounds-check, so an invalid index silently leaves the seat unarmed.
        SeatInfo seat = this.vehicle.getSeat(seatIndex);
        if (seat == null || seat.weapons().size() <= WEAPON_SPECIAL) {
            weight[WEAPON_SPECIAL] = Double.NEGATIVE_INFINITY;
        }

        selectWeapon(seatIndex, argmax(weight));
    }

    private static int argmax(double[] weight) {
        int best = 0;
        for (int i = 1; i < weight.length; i++) {
            if (weight[i] > weight[best]) best = i;
        }
        return best;
    }

    private void selectWeapon(int seatIndex, int weaponIndex) {
    if (seatIndex < 0) return;
    if (this.weaponSwitchCooldown > 0) return;
    this.vehicle.setWeaponIndex(seatIndex, weaponIndex);
    this.weaponSwitchCooldown = SewvConfig.WEAPON_SWITCH_COOLDOWN_TICKS.get();
    }

    // Formation spacing between successive slots, in blocks.
    private static final double FORMATION_SPACING = 5.0;

    // Drive-to point for a formation slot behind the commander. COLUMN files units
    // directly astern; WEDGE fans them out to alternating flanks stepping back each
    // rank. Slot is derived from the unit's SEM-assigned formation index.
    private BlockPos formationTarget(Player owner, OrderType order, int index) {
        float yawRad = owner.getYRot() * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yawRad);
        double forwardZ = Mth.cos(yawRad);
        double rightX = Mth.cos(yawRad);
        double rightZ = Mth.sin(yawRad);

        double back;
        double side;
        if (order == OrderType.FORM_COLUMN) {
            back = (index + 1) * FORMATION_SPACING;
            side = 0.0;
        } else { // FORM_WEDGE
            int rank = (index / 2) + 1;
            int sign = (index % 2 == 0) ? -1 : 1;
            back = rank * FORMATION_SPACING;
            side = sign * rank * FORMATION_SPACING;
        }

        double x = owner.getX() - forwardX * back + rightX * side;
        double z = owner.getZ() - forwardZ * back + rightZ * side;
        return BlockPos.containing(x, owner.getY(), z);
    }

    // Player-commandable units (PmcUnitEntity) use the order queue for their drive
    // destination. RU/US crews have no order system, they just engage whatever
    // they're currently targeting, same as vanilla hostile-mob AI.
    private BlockPos getTargetPos() {
        if (!(this.unit instanceof PmcUnitEntity pmc)) {
            LivingEntity target = this.unit.getTarget();
            if (target != null) return target.blockPosition();
            // No fight of our own — reinforce a nearby allied crew that has one.
            return assistTargetPos();
        }

        OrderType order = pmc.getOrder();

        switch (order) {
            case HOLD_POSITION:
            case CEASE_FIRE:
                // CEASE_FIRE holds ground too; firing is suppressed separately in
                // MixinVehicleFireCooldown so the crew simply sits and doesn't shoot.
                return null;

            case MOVE_TO_POSITION:
                Vec3 moveTarget = pmc.getMoveToTarget();
                return (moveTarget != null && !moveTarget.equals(Vec3.ZERO))
                        ? BlockPos.containing(moveTarget) : null;

            case ATTACK_THAT_TARGET:
                // Player designated the target — no freelancing off to help allies.
                return pmc.getTarget() != null ? pmc.getTarget().blockPosition() : null;

            case FREE_FIRE:
                if (pmc.getTarget() != null) {
                    return pmc.getTarget().blockPosition();
                }
                // Free-firing with nothing to shoot — reinforce an allied crew in combat.
                return assistTargetPos();

            case FOLLOW_COMMANDER:
                Player follows = commander(pmc);
                return follows != null ? follows.blockPosition() : null;

            case FORM_WEDGE:
            case FORM_COLUMN:
                Player leader = commander(pmc);
                return leader != null
                        ? formationTarget(leader, order, pmc.getFormationIndex()) : null;

            default:
                return null;
        }
    }

    private Player commander(PmcUnitEntity pmc) {
        UUID ownerId = pmc.getOwnerUUID();
        return ownerId != null ? pmc.level().getPlayerByUUID(ownerId) : null;
    }

    // Mutual support: an idle crew that notices an allied vehicle in combat drives
    // to it and settles inside this ring around the ally — close enough to bring
    // its own guns into the same fight (the cylinder target scan takes over from
    // there), far enough to not park on the ally's tracks.
    private static final double ASSIST_RING_RADIUS = 16.0;

    private VehicleEntity assistAlly;
    private long lastAssistScanTime = Long.MIN_VALUE;

    // Drive-to point for reinforcing an ally, or null when there is nothing to
    // reinforce (or we're already inside the ally's ring — arrival, not failure).
    // The ring point goes through navigateTo like every destination, so the route
    // still respects the node evaluator's hazards (water margin etc.).
    private BlockPos assistTargetPos() {
        VehicleEntity ally = findAllyInCombat();
        if (ally == null) return null;

        double dx = ally.getX() - this.vehicle.getX();
        double dz = ally.getZ() - this.vehicle.getZ();
        double arrive = ASSIST_RING_RADIUS + VEHICLE_RING_DEADBAND;
        if (dx * dx + dz * dz <= arrive * arrive) return null; // inside the ring — done

        return computeStandoffPoint(ally.blockPosition(), ASSIST_RING_RADIUS);
    }

    // Nearest allied crewed vehicle in combat within the configured assist range.
    // The world scan is the expensive part, so it reruns only on the target-scan
    // cadence ("each scan"); between scans the cached ally is just revalidated.
    private VehicleEntity findAllyInCombat() {
        double range = SewvConfig.VEHICLE_ALLY_ASSIST_RANGE.get();
        if (range <= 0.0) return null; // mutual support disabled

        long now = this.unit.level().getGameTime();
        if (now - this.lastAssistScanTime < SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get()) {
            return validateAssistAlly(range);
        }
        this.lastAssistScanTime = now;

        // Same flat-cylinder shape as the target scan: full horizontal reach,
        // capped vertical extent, corners rounded off by the distance filter.
        double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        AABB bounds = new AABB(
                this.vehicle.getX() - range, this.vehicle.getY() - halfHeight, this.vehicle.getZ() - range,
                this.vehicle.getX() + range, this.vehicle.getY() + halfHeight, this.vehicle.getZ() + range);

        VehicleEntity best = null;
        double bestDistSq = range * range;
        for (VehicleEntity ally : this.unit.level().getEntitiesOfClass(VehicleEntity.class, bounds,
                v -> v != this.vehicle && !v.isWreck())) {
            if (!isAlliedCrewInCombat(ally)) continue;
            double dx = ally.getX() - this.vehicle.getX();
            double dz = ally.getZ() - this.vehicle.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq <= bestDistSq) {
                best = ally;
                bestDistSq = distSq;
            }
        }
        this.assistAlly = best;
        return best;
    }

    // Between scans: keep the cached ally only while it still needs the help.
    private VehicleEntity validateAssistAlly(double range) {
        VehicleEntity ally = this.assistAlly;
        if (ally == null) return null;
        if (ally.isRemoved() || ally.isWreck() || !isAlliedCrewInCombat(ally)
                || this.vehicle.distanceToSqr(ally) > range * range * 2.25) { // >1.5x range — chase abandoned
            this.assistAlly = null;
            return null;
        }
        return ally;
    }

    private boolean isAlliedCrewInCombat(VehicleEntity ally) {
        if (!(ally.getFirstPassenger() instanceof AbstractUnit driver)) return false;
        if (!isSameFaction(driver)) return false;
        LivingEntity target = driver.getTarget();
        return target != null && target.isAlive();
    }

    private boolean isSameFaction(AbstractUnit other) {
        if (this.unit instanceof RUunitEntity) return other instanceof RUunitEntity;
        if (this.unit instanceof USunitEntity) return other instanceof USunitEntity;
        return this.unit instanceof PmcUnitEntity && other instanceof PmcUnitEntity;
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