package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

public class BoardVehicleGoal extends Goal {

    private static final double MOUNT_DISTANCE = 5.0;

    private final PmcUnitEntity unit;
    private final IVehicleBoarder boarder;
    private VehicleEntity targetVehicle;

    public BoardVehicleGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.boarder = (IVehicleBoarder) unit; // safe — mixin makes PMC implement it
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
public boolean canUse() {
    if (!this.boarder.tacz_sewv$isBoarding()) return false;
    if (this.unit.getVehicle() != null) return false;

    int mountId = this.boarder.tacz_sewv$getMountTargetId();
    if (mountId == -1) return false;

    Entity e = this.unit.level().getEntity(mountId);

    if (e instanceof VehicleEntity v && v.isAlive() && !v.isWreck()) {
        // Vehicle full and I'm not already on it? Give up gracefully — free the surplus unit.
        if (v.getPassengers().size() >= v.getMaxPassengers()) {
            this.cancelBoarding();
            return false;
        }
        this.targetVehicle = v;
        return true;
    }

    // Target vehicle gone/dead/wreck — cancel
    this.cancelBoarding();
    return false;
}

private void cancelBoarding() {
    this.boarder.tacz_sewv$setBoarding(false);
    this.boarder.tacz_sewv$setMountTargetId(-1);
    this.targetVehicle = null;
}

    @Override
    public boolean canContinueToUse() {
        return this.boarder.tacz_sewv$isBoarding()
                && this.unit.getVehicle() == null
                && this.targetVehicle != null
                && this.targetVehicle.isAlive()
                && !this.targetVehicle.isWreck()
                && this.targetVehicle.getFirstPassenger() == null;
    }

    @Override
    public void start() {
        this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
    }

    @Override
public void tick() {
    if (this.targetVehicle == null) return;

    this.unit.getLookControl().setLookAt(this.targetVehicle, 30F, 30F);

    double distSq = this.unit.distanceToSqr(this.targetVehicle);
    boolean closeEnough = distSq <= MOUNT_DISTANCE * MOUNT_DISTANCE;
    boolean navStuck = this.unit.getNavigation().isDone() && distSq <= 36.0; // within 6 blocks but nav gave up on the hitbox

    if (closeEnough || navStuck) {
        if (!this.unit.level().isClientSide) {
            this.unit.startRiding(this.targetVehicle);
        }
        this.unit.getNavigation().stop();
    } else if (this.unit.getNavigation().isDone()) {
        this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
    }
}

    @Override
    public void stop() {
        this.boarder.tacz_sewv$setBoarding(false);
        this.boarder.tacz_sewv$setMountTargetId(-1);
        this.targetVehicle = null;
        this.unit.getNavigation().stop();
    }
}