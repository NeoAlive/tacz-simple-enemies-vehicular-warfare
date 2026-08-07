package com.neoalive.tacz_sewv.entity.ai.support;

import javax.annotation.Nullable;

import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.bridge.IMedicTreat;

/**
 * Synched treating flag + heal {@link AnimationState} for {@link MedicGoal}.
 * Mirrors {@link DroneControl}'s lock/sit split for the engineer sit pose.
 */
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

    @Nullable
    public static AnimationState treatAnimationState(Entity entity) {
        return entity instanceof IMedicTreat treat ? treat.sewv$treatAnimationState() : null;
    }
}
