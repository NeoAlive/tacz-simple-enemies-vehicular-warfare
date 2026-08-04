package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.EnumSet;

/**
 * Priority {@code -1} freeze while the engineer operates a kamikaze drone. Claims MOVE/LOOK/TARGET
 * so it displaces vanilla {@code FloatGoal} (prio 0) in water and outranks repair/combat.
 */
public final class DroneControlLockGoal extends Goal {

    private final AbstractUnit unit;

    public DroneControlLockGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        return !this.unit.level().isClientSide() && DroneControl.isLocked(this.unit);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        this.unit.getNavigation().stop();
        this.unit.getMoveControl().setWantedPosition(this.unit.getX(), this.unit.getY(), this.unit.getZ(), 0.0);
        this.unit.setZza(0.0F);
        this.unit.setXxa(0.0F);
        this.unit.setSpeed(0.0F);
        this.unit.setDeltaMovement(this.unit.getDeltaMovement().multiply(0.0, 1.0, 0.0));
    }
}
