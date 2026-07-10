package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.PathNavigationRegion;
import com.neoalive.tacz_sewv.entity.ai.navigation.GroundVehicleNodeEvaluator;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.UUID;

public class DriveVehicleGoal extends Goal {

    // Angle thresholds — tight when close, laxer when far (from SuperbRecruitz)
    private static final double MIN_ANGLE_RAD = Math.toRadians(3.0);
    private static final double MAX_ANGLE_RAD = Math.toRadians(22.5);
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 20.0;
    private static final double STOP_DISTANCE = 8.0;
    private static final int WEAPON_CANNON = 0; // verify in-game
    private static final int WEAPON_MG = 1;      // verify in-game

    private final GroundVehicleNodeEvaluator nodeEvaluator = new GroundVehicleNodeEvaluator();
    private Path currentPath = null;
    private int pathRecalcCooldown = 0;

    private final PmcUnitEntity unit;
    private VehicleEntity vehicle;
    private int weaponSwitchCooldown = 0;

    public DriveVehicleGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // driving doesn't need to lock move/look flags
    }

    @Override
    public boolean canUse() {
        if (!(this.unit.getVehicle() instanceof VehicleEntity v)) return false;
        // ONLY the driver (seat 0) drives — enforces your driver-commander model
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
    }

    @Override
public void tick() {
    if (this.weaponSwitchCooldown > 0) this.weaponSwitchCooldown--;
    if (this.pathRecalcCooldown > 0) this.pathRecalcCooldown--;

    BlockPos targetPos = getTargetPos();
    if (targetPos == null) {
        stopVehicleMovement();
        return;
    }

    // Distance to the REAL target — used for standoff band and firing range
    double distanceSq = this.vehicle.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());

    // --- PATHFINDING: compute a route around obstacles, throttled ---
    if (this.pathRecalcCooldown <= 0) {
        recomputePath(targetPos);
        this.pathRecalcCooldown = 20; // recompute once per second
    }

    // Work out what to STEER toward — the next path waypoint, or the raw target as fallback
    BlockPos steerTarget = targetPos;
    if (this.currentPath != null && !this.currentPath.isDone()) {
        BlockPos next = this.currentPath.getNextNodePos();
        steerTarget = next;
        // Advance to the next node once we're close to the current one
        double nodeDistSq = this.vehicle.distanceToSqr(next.getX() + 0.5, this.vehicle.getY(), next.getZ() + 0.5);
        if (nodeDistSq < 9.0) {
            this.currentPath.advance();
        }
    }

    boolean isCombat = this.unit.getTarget() != null;

    if (isCombat) {
        double tooClose = 10.0;
        double tooFar = 20.0;
        double dist = Math.sqrt(distanceSq); // distance to REAL target, for band
        int seatIndex = this.vehicle.getSeatIndex(this.unit);

        if (dist < tooClose) {
            selectWeapon(seatIndex, WEAPON_MG);
            reverseFromTarget(targetPos, distanceSq); // reverse relative to real target
        } else if (dist > tooFar) {
            selectWeapon(seatIndex, WEAPON_CANNON);
            driveGroundVehicle(steerTarget, distanceSq); // STEER toward path waypoint
        } else {
            selectWeapon(seatIndex, WEAPON_MG);
            stopVehicleMovement();
        }
    } else {
        double stopDistance = this.vehicle.getBbWidth() - 1 + STOP_DISTANCE;
        if (distanceSq > stopDistance * stopDistance) {
            driveGroundVehicle(steerTarget, distanceSq); // STEER toward path waypoint
        } else {
            stopVehicleMovement();
        }
    }
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

private void reverseFromTarget(BlockPos targetPos, double distanceSq) {
    // Keep facing the target (so turret stays on it), but drive in REVERSE to open distance
    Vec3 toTarget = new Vec3(
            targetPos.getX() - this.vehicle.getX(),
            0,
            targetPos.getZ() - this.vehicle.getZ()
    ).normalize();

    Vector3f forward = this.vehicle.getForwardDirection().normalize();
    double angle = getAngleBetween(forward, toTarget);
    double angleThreshold = getRotationStopAngle(distanceSq);

    if (Math.abs(angle) < angleThreshold) {
        // Facing target — reverse straight back
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
        this.vehicle.setLeftInputDown(false);
        this.vehicle.setRightInputDown(false);
    } else {
        // Turn to face target while backing — steer + reverse
        this.vehicle.setLeftInputDown(angle > 0);
        this.vehicle.setRightInputDown(angle < 0);
        this.vehicle.setForwardInputDown(false);
        this.vehicle.setBackInputDown(true);
    }
}

private void recomputePath(BlockPos target) {
    try {
        int range = 64;
        PathNavigationRegion region = new PathNavigationRegion(
            this.unit.level(),
            this.vehicle.blockPosition().offset(-range, -range, -range),
            this.vehicle.blockPosition().offset(range, range, range)
        );
        this.nodeEvaluator.prepare(region, this.unit);
        PathFinder finder = new PathFinder(this.nodeEvaluator, 1024);
        java.util.Set<BlockPos> targets = java.util.Set.of(target);
        this.currentPath = finder.findPath(region, this.unit, targets, (float) range, 1, 1.0F);
        // DON'T call this.nodeEvaluator.done() — findPath already does it internally
    } catch (Exception e) {
        this.currentPath = null;
    }
}

private boolean isTrackedVehicle() {
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

    private void selectWeapon(int seatIndex, int weaponIndex) {
    if (seatIndex < 0) return;
    if (this.weaponSwitchCooldown > 0) return;
    this.vehicle.setWeaponIndex(seatIndex, weaponIndex);
    this.weaponSwitchCooldown = 40;
    }

    // YOUR order system → drive destination
    private BlockPos getTargetPos() {
        OrderType order = this.unit.getOrder();

        switch (order) {
            case HOLD_POSITION:
                return null;

            case MOVE_TO_POSITION:
                Vec3 moveTarget = this.unit.getMoveToTarget();
                return (moveTarget != null && !moveTarget.equals(Vec3.ZERO))
                        ? BlockPos.containing(moveTarget) : null;

            case ATTACK_THAT_TARGET:
            case FREE_FIRE:
                if (this.unit.getTarget() != null) {
                    return this.unit.getTarget().blockPosition();
                }
                return null;

            case FOLLOW_COMMANDER:
                UUID ownerId = this.unit.getOwnerUUID();
                if (ownerId != null) {
                    Player owner = this.unit.level().getPlayerByUUID(ownerId);
                    if (owner != null) return owner.blockPosition();
                }
                return null;

            default:
                return null;
        }
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