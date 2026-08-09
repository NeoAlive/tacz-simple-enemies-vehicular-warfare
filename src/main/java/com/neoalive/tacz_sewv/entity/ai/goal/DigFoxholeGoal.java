package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.entity.ai.support.DigFoxholeSupport;

/**
 * Autonomous one-shot foxhole dig for Combat Engineers. Flagless — placement is instant.
 * Gates: lifetime dig flag, 15&nbsp;s age, inverted SBW container clearance, no fortification
 * within {@link DigFoxholeSupport#CLEARANCE_RADIUS}.
 */
public class DigFoxholeGoal extends Goal {

    private static final int MIN_AGE_TICKS = 15 * 20;
    private static final int SCAN_INTERVAL = 40;

    private final AbstractUnit unit;
    private int scanCooldown;

    public DigFoxholeGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide()) return false;
        if (!(this.unit.level() instanceof ServerLevel level)) return false;
        if (DigFoxholeSupport.hasDug(this.unit)) return false;
        if (this.unit.tickCount < MIN_AGE_TICKS) return false;
        if (this.scanCooldown-- > 0) return false;
        this.scanCooldown = SCAN_INTERVAL;

        BlockPos footing = this.unit.blockPosition().below();
        if (!DigFoxholeSupport.isGroundEligible(level, footing)) return false;
        if (DigFoxholeSupport.hasNearbyFortification(level, footing, DigFoxholeSupport.CLEARANCE_RADIUS)) {
            return false;
        }

        DigFoxholeSupport.place(level, this.unit);
        // Work done inside canUse (SeekAbandoned pattern) — never claim the selector.
        return false;
    }
}
