package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobSupport;

/**
 * Patrol inside the FOB master area when command is active and not scrambling.
 */
public class FobPatrolGoal extends Goal {

    private static final double ARRIVE_SQ = 4.0D;
    private static final int REPATH_INTERVAL = 20;

    private final PmcUnitEntity unit;
    private BlockPos destination;
    private int repathCooldown;

    public FobPatrolGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (this.unit.isPassenger()) return false;
        if (!FobSupport.isStamped(this.unit)) return false;
        if (this.unit.getTarget() != null) return false;
        if (VehicleTargeting.underStandingOrder(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;
        FobInstance fob = fob(level);
        if (fob == null || !fob.fobCommandActive || fob.scrambleActive) return false;
        this.destination = FobSupport.randomPatrolPos(fob, level, level.getGameTime() ^ this.unit.getId());
        return this.destination != null
                && this.unit.blockPosition().distSqr(this.destination) >= ARRIVE_SQ;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.unit.isPassenger()) return false;
        if (this.unit.getTarget() != null) return false;
        if (VehicleTargeting.underStandingOrder(this.unit)) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;
        FobInstance fob = fob(level);
        if (fob == null || !fob.fobCommandActive || fob.scrambleActive) return false;
        return this.destination != null
                && this.unit.blockPosition().distSqr(this.destination) >= ARRIVE_SQ;
    }

    @Nullable
    private FobInstance fob(ServerLevel level) {
        BlockPos cmd = FobSupport.stampPos(this.unit);
        if (cmd == null) return null;
        return FobManager.get(level).getFob(cmd);
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.destination = null;
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.destination == null) return;
        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.unit.getNavigation().moveTo(
                    this.destination.getX() + 0.5,
                    this.destination.getY(),
                    this.destination.getZ() + 0.5,
                    1.0);
        }
    }
}
