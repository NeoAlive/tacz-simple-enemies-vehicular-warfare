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
    boolean boarding = this.boarder.tacz_sewv$isBoarding();
    int mountId = this.boarder.tacz_sewv$getMountTargetId();
    System.out.println("[TACZ_SEWV] canUse check — boarding: " + boarding + ", mountId: " + mountId + ", vehicle: " + this.unit.getVehicle());

    if (!boarding) return false;
    if (this.unit.getVehicle() != null) return false;
    if (mountId == -1) return false;

    Entity e = this.unit.level().getEntity(mountId);
    System.out.println("[TACZ_SEWV] resolved entity: " + e);

    if (e instanceof VehicleEntity v && v.isAlive() && !v.isWreck() && v.getFirstPassenger() == null) {
        this.targetVehicle = v;
        System.out.println("[TACZ_SEWV] canUse TRUE — target locked");
        return true;
    }
    System.out.println("[TACZ_SEWV] canUse FALSE — vehicle check failed");
    return false;
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