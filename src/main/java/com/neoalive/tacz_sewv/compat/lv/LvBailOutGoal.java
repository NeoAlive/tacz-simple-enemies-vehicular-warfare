package com.neoalive.tacz_sewv.compat.lv;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;

/**
 * Bails a crew out of an LV hull that is about to be knocked out — the health half of
 * {@code BailOutVehicleGoal}, which is typed to SBW hulls throughout. A hull that IS knocked out
 * already throws everyone out itself ({@code AbstractVehicle.hurt} ejects on destruction), so this
 * only covers the stretch just before that, at the same fraction the SBW goal uses.
 */
final class LvBailOutGoal extends Goal {

    /** Same threshold as {@code BailOutVehicleGoal.BAIL_HEALTH_FRACTION}. */
    private static final float BAIL_HEALTH_FRACTION = 0.15F;

    private final AbstractUnit unit;

    LvBailOutGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide() || !LvCompat.aiEnabled()) return false;
        return this.unit.getVehicle() instanceof AbstractVehicle v
                && !v.isDestroyed()
                && LvHulls.writtenOff(v, BAIL_HEALTH_FRACTION);
    }

    @Override
    public boolean canContinueToUse() {
        return false; // one action, done in start()
    }

    @Override
    public void start() {
        this.unit.stopRiding();
        // A pending board order would walk the unit straight back into the burning hull.
        if (this.unit instanceof IVehicleBoarder boarder) {
            boarder.tacz_sewv$setBoarding(false);
            boarder.tacz_sewv$setMountTargetId(-1);
        }
    }
}
