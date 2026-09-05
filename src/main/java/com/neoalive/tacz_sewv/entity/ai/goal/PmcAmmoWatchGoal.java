package com.neoalive.tacz_sewv.entity.ai.goal;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Flagless PMC-only watcher: detects TACZ dry magazine+reserves and notifies the owner.
 * Always declines to run so it never contends for MOVE/LOOK.
 */
public final class PmcAmmoWatchGoal extends Goal {

    private final PmcUnitEntity unit;

    public PmcAmmoWatchGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        HudNotify.watchPmcInfantryAmmo(this.unit);
        return false;
    }
}
