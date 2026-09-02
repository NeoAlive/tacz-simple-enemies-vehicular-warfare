package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.fob.FobDebug;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobSupport;

/**
 * Completes a {@link com.neoalive.tacz_sewv.fob.FobNetworking#routeToFob} order: drivers dismount
 * once their hull reaches the parking pad; on-foot infantry auto-board an assigned empty vehicle.
 */
public class FobRouteArrivalGoal extends Goal {

    private final PmcUnitEntity unit;

    public FobRouteArrivalGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!FobSupport.hasRoutePending(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;

        if (FobSupport.sanitizeRoutePending(this.unit, level)) {
            FobDebug.logEntity(this.unit, "route goal canUse=false — stale route cleared");
            return false;
        }

        FobInstance fob = fob(level);
        if (fob == null || fob.scrambleActive || fob.parkingPos == null) {
            FobDebug.logEntity(this.unit, "route goal canUse=false — fob={}, scramble={}, parking={}",
                    fob != null, fob != null && fob.scrambleActive, fob != null && fob.parkingPos != null);
            return false;
        }
        boolean ready = readyToFinish(level, fob);
        if (!ready) {
            FobDebug.logEntity(this.unit, "route goal canUse=false — not at parking standoff yet");
        }
        return ready;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(this.unit.level() instanceof ServerLevel level)) return;
        FobInstance fob = fob(level);
        if (fob == null) return;

        if (this.unit.isPassenger() && this.unit.getVehicle() instanceof VehicleEntity hull
                && isDriver(hull) && atParkingStandoff(hull, fob, level)) {
            FobDebug.logEntity(this.unit, "route arrival — driver dismount at parking standoff");
            this.unit.stopRiding();
            finishRoute();
            return;
        }

        if (!this.unit.isPassenger() && atParkingStandoff(this.unit, fob, level)) {
            tryBoard(level, fob);
            FobDebug.logEntity(this.unit, "route arrival — infantry at parking standoff");
            finishRoute();
        }
    }

    private boolean readyToFinish(ServerLevel level, FobInstance fob) {
        if (fob.parkingPos == null) return false;

        if (this.unit.isPassenger() && this.unit.getVehicle() instanceof VehicleEntity hull) {
            return isDriver(hull) && atParkingStandoff(hull, fob, level);
        }
        return !this.unit.isPassenger() && atParkingStandoff(this.unit, fob, level);
    }

    private boolean isDriver(VehicleEntity hull) {
        return hull.getFirstPassenger() == this.unit;
    }

    private static boolean atParkingStandoff(Entity entity, FobInstance fob, Level level) {
        return FobSupport.withinParkingPad(fob, entity, level);
    }

    private void tryBoard(ServerLevel level, FobInstance fob) {
        VehicleEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID id : fob.assignedVehicles) {
            if (!(level.getEntity(id) instanceof VehicleEntity hull)) continue;
            if (!hull.isAlive() || hull.isWreck() || !hull.getPassengers().isEmpty()) continue;
            double d = hull.distanceToSqr(this.unit);
            if (d < bestDist) {
                bestDist = d;
                best = hull;
            }
        }
        if (best != null && this.unit instanceof IVehicleBoarder boarder) {
            boarder.tacz_sewv$setMountTargetId(best.getId());
            boarder.tacz_sewv$setBoarding(true);
            boarder.tacz_sewv$setPassengerOnly(false);
        }
    }

    private void finishRoute() {
        FobSupport.clearRoutePending(this.unit);
        this.unit.setOrder(OrderType.FREE_FIRE);
        FobDebug.logEntity(this.unit, "route finished — order reset to FREE_FIRE");
    }

    @Nullable
    private FobInstance fob(ServerLevel level) {
        BlockPos cmd = FobSupport.routeCommandPos(this.unit);
        if (cmd == null) cmd = FobSupport.stampPos(this.unit);
        if (cmd == null) return null;
        return FobManager.get(level).getFob(cmd);
    }
}
