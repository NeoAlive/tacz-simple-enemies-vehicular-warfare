package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
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
    // Vehicle standoff band, MG can't hurt armor and cannon/TOW work at range,
    // so hold much further back instead of closing to point-blank tank-on-tank.
    private static final double VEHICLE_TOO_CLOSE = 20.0;
    private static final double VEHICLE_TOO_FAR = 40.0;
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
    private static final double PATH_TARGET_DRIFT_SQ = 9.0;  // target moved >3 blocks → stale

    private final GroundVehicleNodeEvaluator nodeEvaluator = new GroundVehicleNodeEvaluator();
    private final PathFinder pathFinder = new PathFinder(this.nodeEvaluator, 512);
    private Path currentPath = null;
    private int pathRecalcCooldown = 0;
    private int pathAge = 0;
    private BlockPos lastPathTarget = null;

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
    }

    @Override
public void tick() {
    if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
    if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;
    this.pathAge++;

    BlockPos targetPos = getTargetPos();
    if (targetPos == null) {
        stopVehicleMovement();
        return;
    }

    // Distance to the REAL target, used for standoff band and firing range
    double distanceSq = this.vehicle.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());

    boolean isCombat = this.unit.getTarget() != null;

    if (isCombat) {
        LivingEntity target = this.unit.getTarget();
        TargetCategory category = classifyTarget(target);
        boolean isVehicleTarget = category == TargetCategory.VEHICLE;

        // Vehicle targets get a much wider standoff band, no point charging
        // point-blank when the MG can't hurt armor and cannon/TOW reach further.
        double tooCloseThreshold = isVehicleTarget ? VEHICLE_TOO_CLOSE : INFANTRY_TOO_CLOSE;
        double tooFarThreshold = isVehicleTarget ? VEHICLE_TOO_FAR : INFANTRY_TOO_FAR;

        double dist = Math.sqrt(distanceSq); // distance to REAL target, for band

        boolean tooFar = dist > tooFarThreshold;
        if (this.weaponSwitchCooldown <= 0) {
            selectWeaponForTarget(this.vehicle.getSeatIndex(this.unit), category, tooFar);
        }

        if (dist < tooCloseThreshold) {
            retreatFromTarget(targetPos, category, distanceSq);
        } else if (tooFar) {
            driveGroundVehicle(getSteerTarget(targetPos), distanceSq);
        } else {
            stopVehicleMovement();
        }
    } else {
        double stopDistance = this.vehicle.getBbWidth() - 1 + STOP_DISTANCE;
        if (distanceSq > stopDistance * stopDistance) {
            driveGroundVehicle(getSteerTarget(targetPos), distanceSq);
        } else {
            stopVehicleMovement();
        }
    }
}

// Only called from the branches that actually drive, a vehicle holding its
// standoff band or parked at its destination never pays for pathfinding.
private BlockPos getSteerTarget(BlockPos targetPos) {
    boolean pathStale = this.currentPath == null
            || this.currentPath.isDone()
            || this.pathAge > MAX_PATH_AGE_TICKS
            || this.lastPathTarget == null
            || this.lastPathTarget.distSqr(targetPos) > PATH_TARGET_DRIFT_SQ;

    if (pathStale) {
        if (this.pathRecalcCooldown > 0) {
            // Can't recompute yet — steer straight at the destination rather than
            // follow a path laid to somewhere else (e.g. an approach path right
            // after flipping into retreat, which would drive us AT the enemy).
            return targetPos;
        }
        recomputePath(targetPos);
        this.lastPathTarget = targetPos;
        this.pathAge = 0;
        // Terrain won't have changed next tick, back off harder after a failed search
        this.pathRecalcCooldown = this.currentPath == null ? PATH_FAIL_COOLDOWN : PATH_RECALC_COOLDOWN;
    }

    if (this.currentPath != null && !this.currentPath.isDone()) {
        BlockPos next = this.currentPath.getNextNodePos();
        // Advance to the next node once we're close to the current one
        double nodeDistSq = this.vehicle.distanceToSqr(next.getX() + 0.5, this.vehicle.getY(), next.getZ() + 0.5);
        if (nodeDistSq < 9.0) {
            this.currentPath.advance();
            if (!this.currentPath.isDone()) {
                next = this.currentPath.getNextNodePos();
            }
        }
        return next;
    }
    return targetPos; // no usable path, steer straight at the target as fallback
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

// Reversing only opens distance while the target sits inside this frontal cone;
// beyond it, backing up moves the hull sideways or INTO the target.
private static final double REVERSE_FACING_CONE_RAD = Math.toRadians(75.0);

// Too close — open distance back out to the standoff band. Only reverse when the
// target is actually in front (gun and front armor stay on it while distance
// grows). Anywhere else, reversing is wrong — e.g. target behind the hull after
// driving past it — so pathfind forward to a retreat point on the ring instead.
private void retreatFromTarget(BlockPos targetPos, TargetCategory category, double distanceSq) {
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
        driveGroundVehicle(getSteerTarget(computeRetreatPos(targetPos, category)), distanceSq);
    }
}

// Point on the standoff ring, straight out from the target through the vehicle:
// the comfortable mid-band for soft targets, the far ring for armored ones
// (cannon/TOW range, where the enemy vehicle's own guns are least dangerous).
private BlockPos computeRetreatPos(BlockPos targetPos, TargetCategory category) {
    double radius = category == TargetCategory.VEHICLE
            ? VEHICLE_TOO_FAR
            : (INFANTRY_TOO_CLOSE + INFANTRY_TOO_FAR) / 2.0;

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
            return target != null ? target.blockPosition() : null;
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
            case FREE_FIRE:
                if (pmc.getTarget() != null) {
                    return pmc.getTarget().blockPosition();
                }
                return null;

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