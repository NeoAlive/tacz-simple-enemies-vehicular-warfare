package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IEntrenched;
import com.neoalive.tacz_sewv.entity.ai.support.EntrenchSupport;

/**
 * Keeps an ENTRENCHED unit on its cell (reroll / MOVE / HOLD). Flagless — SEM MOVE goals and
 * board/mortar goals own locomotion; this only refreshes orders and validates the assignment.
 */
public class EntrenchGoal extends Goal {

    private final AbstractUnit unit;

    public EntrenchGoal(AbstractUnit unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return !this.unit.level().isClientSide()
                && this.unit instanceof IEntrenched e
                && e.sewv$isEntrenched();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        EntrenchSupport.tick(this.unit);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
