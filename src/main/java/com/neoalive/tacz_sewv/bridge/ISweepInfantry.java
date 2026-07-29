package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * On-foot Sweep &amp; Advance area (chunk AABB). Distinct from {@link IVehiclePatrol} —
 * mounted crews use MODE_SWEEP; infantry uses this. Id-free so it can persist.
 */
public interface ISweepInfantry {

    String TAG_ACTIVE = "tacz_sewv_inf_sweep";
    String TAG_LEFT = "tacz_sewv_inf_sweep_left";
    String TAG_TOP = "tacz_sewv_inf_sweep_top";
    String TAG_RIGHT = "tacz_sewv_inf_sweep_right";
    String TAG_BOTTOM = "tacz_sewv_inf_sweep_bottom";
    String TAG_WAYPOINT = "tacz_sewv_inf_sweep_wp";
    String TAG_NEXT = "tacz_sewv_inf_sweep_next";

    default boolean sewv$hasInfantrySweep() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_ACTIVE);
    }

    default void sewv$setInfantrySweep(int left, int top, int right, int bottom) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_LEFT, left);
        tag.putInt(TAG_TOP, top);
        tag.putInt(TAG_RIGHT, right);
        tag.putInt(TAG_BOTTOM, bottom);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT);
    }

    default void sewv$clearInfantrySweep() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ACTIVE);
        tag.remove(TAG_LEFT);
        tag.remove(TAG_TOP);
        tag.remove(TAG_RIGHT);
        tag.remove(TAG_BOTTOM);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT);
    }

    default int sewv$getInfSweepLeft() {
        return ((Entity) this).getPersistentData().getInt(TAG_LEFT);
    }

    default int sewv$getInfSweepTop() {
        return ((Entity) this).getPersistentData().getInt(TAG_TOP);
    }

    default int sewv$getInfSweepRight() {
        return ((Entity) this).getPersistentData().getInt(TAG_RIGHT);
    }

    default int sewv$getInfSweepBottom() {
        return ((Entity) this).getPersistentData().getInt(TAG_BOTTOM);
    }

    default long sewv$getInfSweepWaypoint() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_WAYPOINT) ? tag.getLong(TAG_WAYPOINT) : Long.MIN_VALUE;
    }

    default void sewv$setInfSweepWaypoint(long packed) {
        ((Entity) this).getPersistentData().putLong(TAG_WAYPOINT, packed);
    }

    default long sewv$getInfSweepNext() {
        return ((Entity) this).getPersistentData().getLong(TAG_NEXT);
    }

    default void sewv$setInfSweepNext(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_NEXT, gameTime);
    }
}
