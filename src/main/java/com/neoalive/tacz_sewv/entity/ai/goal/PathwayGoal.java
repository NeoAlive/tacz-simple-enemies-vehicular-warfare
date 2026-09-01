package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.IPathwayInfantry;
import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;

/**
 * Walk a preferred pathway leg-by-leg; clears on final node (no loop).
 */
public class PathwayGoal extends Goal {

    private final PmcUnitEntity unit;

    public PathwayGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        return !this.unit.isPassenger() && ((IPathwayInfantry) this.unit).sewv$hasPathway();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (PathwaySupport.shouldAbandonPassivePath(this.unit)) {
            ((IPathwayInfantry) this.unit).sewv$clearPathway();
            this.unit.getNavigation().stop();
            return;
        }

        BlockPos leg = PathwaySupport.currentLeg(this.unit);
        if (leg == null) {
            this.unit.getNavigation().stop();
            return;
        }
        this.unit.getNavigation().moveTo(leg.getX() + 0.5, leg.getY(), leg.getZ() + 0.5, 1.0);
    }
}
