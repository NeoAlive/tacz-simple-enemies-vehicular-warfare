package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Coordinated fire delay stamped on a mortar crew when a radio call schedules shells
 * to land together. The order arrives immediately; {@link com.neoalive.tacz_sewv.entity.ai.goal.ManMortarGoal}
 * and {@link com.neoalive.tacz_sewv.entity.ai.goal.ManVehicleMortarGoal} hold the trigger until the
 * deadline passes.
 */
public interface IDelayedFire {

    String TAG_DELAY_UNTIL = "sewv:fire_delay_until";

    /** Absolute game-time deadline, or {@code 0} when none. */
    default long sewv$getFireDelayUntil() {
        return ((Entity) this).getPersistentData().getLong(TAG_DELAY_UNTIL);
    }

    /** {@code deadline <= 0} clears the timer (including override on re-trigger). */
    default void sewv$setFireDelayUntil(long deadline) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (deadline <= 0L) {
            tag.remove(TAG_DELAY_UNTIL);
        } else {
            tag.putLong(TAG_DELAY_UNTIL, deadline);
        }
    }

    default boolean sewv$hasActiveFireDelay(long gameTime) {
        return sewv$getFireDelayUntil() > gameTime;
    }
}
