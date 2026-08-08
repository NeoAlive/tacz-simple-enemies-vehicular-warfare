package com.neoalive.tacz_sewv.entity.ai.support;

import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IMedicTreat;

/** Synched treating flag for {@link com.neoalive.tacz_sewv.entity.ai.goal.MedicGoal}. */
public final class MedicControl {

    private MedicControl() {}

    public static boolean isTreating(Entity entity) {
        return entity instanceof IMedicTreat treat && treat.sewv$isTreating();
    }

    public static void setTreating(AbstractUnit unit, boolean treating) {
        if (unit instanceof IMedicTreat treat) {
            treat.sewv$setTreating(treating);
        }
    }
}
