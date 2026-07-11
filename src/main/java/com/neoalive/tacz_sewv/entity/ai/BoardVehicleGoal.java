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
    private VehicleEntity targetVehicle;

    // THIS is what's missing — add it back:
    public BoardVehicleGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class)); // no flags = canUse ALWAYS evaluated
    }

    private IVehicleBoarder boarder() {
        return (IVehicleBoarder) this.unit;
    }

@Override
public boolean canUse() {
    if (this.unit.level().isClientSide()) return false;

    boolean boarding = boarder().tacz_sewv$isBoarding();
    int mountId = boarder().tacz_sewv$getMountTargetId();

    if (!boarding) return false;
    if (this.unit.getVehicle() != null) return false;
    if (mountId == -1) return false;

    Entity e = this.unit.level().getEntity(mountId);
    if (e instanceof VehicleEntity v && v.isAlive() && !v.isWreck() 
            && v.getPassengers().size() < v.getMaxPassengers()) {
        this.targetVehicle = v;
        return true;
    }
    return false;
}

private void cancelBoarding() {
    boarder().tacz_sewv$setBoarding(false);
    boarder().tacz_sewv$setMountTargetId(-1);
    this.targetVehicle = null;
}

    @Override
public boolean canContinueToUse() {
    if (!boarder().tacz_sewv$isBoarding()) return false;   // order cancelled → stop
    if (this.unit.getVehicle() != null) return false;         // mounted → stop (success)
    if (this.targetVehicle == null) return false;
    if (!this.targetVehicle.isAlive() || this.targetVehicle.isWreck()) return false;
    // Don't stop just because it's full — let tick() handle that with cancelBoarding
    return true;
}

    @Override
public void start() {
    this.boardingTicks = 0;
    this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
}

    private int boardingTicks = 0;
private static final int MAX_BOARDING_TICKS = 200; // 10 seconds to reach the vehicle

@Override
public void tick() {
    if (this.targetVehicle == null) return;

    this.boardingTicks++;

    // Been trying too long? Probably stuck in a crowd or can't reach — give up.
    if (this.boardingTicks > MAX_BOARDING_TICKS) {
        this.cancelBoarding();
        return;
    }

    // If the vehicle filled up while I was walking over, bail now
    if (this.targetVehicle.getPassengers().size() >= this.targetVehicle.getMaxPassengers()) {
        this.cancelBoarding();
        return;
    }

    this.unit.getLookControl().setLookAt(this.targetVehicle, 30F, 30F);

    double distSq = this.unit.distanceToSqr(this.targetVehicle);
    boolean closeEnough = distSq <= MOUNT_DISTANCE * MOUNT_DISTANCE;
    boolean navStuck = this.unit.getNavigation().isDone() && distSq <= 36.0;

    if (closeEnough || navStuck) {
    if (!this.unit.level().isClientSide) {
        this.unit.startRiding(this.targetVehicle);
        // Boarded successfully — clear the order so it doesn't loop or re-board after dismount
        boarder().tacz_sewv$setBoarding(false);
        boarder().tacz_sewv$setMountTargetId(-1);
    }
    this.unit.getNavigation().stop();
} else if (this.unit.getNavigation().isDone() && this.boardingTicks % 10 == 0) {
    // Throttled: an unreachable vehicle leaves navigation "done" every tick,
    // which would otherwise trigger a full repath every tick for 10 seconds.
    this.unit.getNavigation().moveTo(this.targetVehicle, 1.0);
}
}

    @Override
public void stop() {
    // DON'T cancel boarding here — the unit might still want to board,
    // this could just be a temporary goal interruption.
    // Only stop the current navigation.
    this.unit.getNavigation().stop();
    this.boardingTicks = 0; // reset the timeout counter for next attempt
}
}