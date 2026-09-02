package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.fob.ThreatEvaluator;

/**
 * Scramble response: acquire nearest hostile and board an assigned empty vehicle if on foot.
 */
public class FobScrambleGoal extends Goal {

    private final PmcUnitEntity unit;

    public FobScrambleGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (FobSupport.hasRoutePending(this.unit)) return false;
        if (!FobSupport.isStamped(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;
        FobInstance fob = fob(level);
        return fob != null && fob.scrambleActive;
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

        LivingEntity hostile = ThreatEvaluator.nearestHostile(level, fob, this.unit.blockPosition());
        if (hostile != null && this.unit.getTarget() != hostile) {
            this.unit.setTarget(hostile);
        }

        if (!this.unit.isPassenger()) {
            tryBoard(level, fob);
        }
    }

    private void tryBoard(ServerLevel level, FobInstance fob) {
        VehicleEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID id : fob.assignedVehicles) {
            if (!(level.getEntity(id) instanceof VehicleEntity hull)) continue;
            if (!hull.isAlive() || hull.isWreck()) continue;
            if (hull.getPassengers().isEmpty()) {
                double d = hull.distanceToSqr(this.unit);
                if (d < bestDist) {
                    bestDist = d;
                    best = hull;
                }
            }
        }
        if (best != null && this.unit instanceof IVehicleBoarder boarder) {
            boarder.tacz_sewv$setMountTargetId(best.getId());
            boarder.tacz_sewv$setBoarding(true);
            boarder.tacz_sewv$setPassengerOnly(false);
        }
    }

    @Nullable
    private FobInstance fob(ServerLevel level) {
        BlockPos cmd = FobSupport.stampPos(this.unit);
        if (cmd == null) return null;
        return FobManager.get(level).getFob(cmd);
    }
}
