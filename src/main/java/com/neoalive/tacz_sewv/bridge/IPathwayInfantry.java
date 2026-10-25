package com.neoalive.tacz_sewv.bridge;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import com.neoalive.tacz_sewv.entity.ai.support.PathwaySupport;

/**
 * On-foot preferred pathway route — one-shot leg-to-leg walk, distinct from patrol/sweep.
 */
public interface IPathwayInfantry {

    String TAG_ACTIVE = "tacz_sewv_pathway_active";
    String TAG_ROUTE = "tacz_sewv_pathway_route";
    String TAG_STEP = "tacz_sewv_pathway_step";
    String TAG_STEP_DEADLINE = "tacz_sewv_pathway_step_deadline";
    String TAG_SOURCE_ID = "tacz_sewv_pathway_source";
    /** True when joined by passive entry matching: may auto-abandon. */
    String TAG_PASSIVE = "tacz_sewv_pathway_passive";
    /** After a route completes, FREE_FIRE velocity matching stays off until this game time. */
    String TAG_PASSIVE_COOLDOWN = "tacz_sewv_pathway_passive_cooldown";
    /** Packed BlockPos of the last MOVE destination a path was taken for: a MOVE funnels once. */
    String TAG_FUNNELED_DEST = "tacz_sewv_pathway_funneled_dest";
    String TAG_STALE_CHECK = "tacz_sewv_pathway_stale_next";

    default boolean sewv$hasPathway() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_ACTIVE);
    }

    default boolean sewv$isPathwayPassive() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_PASSIVE);
    }

    default void sewv$setPathway(List<BlockPos> route, int startStep, String sourcePathId,
                                 boolean passive) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        long[] packed = new long[route.size()];
        for (int i = 0; i < route.size(); i++) packed[i] = route.get(i).asLong();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putBoolean(TAG_PASSIVE, passive);
        tag.putLongArray(TAG_ROUTE, packed);
        tag.putInt(TAG_STEP, startStep);
        tag.putLong(TAG_STEP_DEADLINE, 0L);
        if (sourcePathId != null && !sourcePathId.isEmpty()) {
            tag.putString(TAG_SOURCE_ID, sourcePathId);
        } else {
            tag.remove(TAG_SOURCE_ID);
        }
    }

    /** Drops the route with no cooldown: a clear caused by a new order must not stop that order using a path. */
    default void sewv$clearPathway() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ACTIVE);
        tag.remove(TAG_ROUTE);
        tag.remove(TAG_STEP);
        tag.remove(TAG_STEP_DEADLINE);
        tag.remove(TAG_SOURCE_ID);
        tag.remove(TAG_PASSIVE);
        tag.remove(TAG_STALE_CHECK);
    }

    /** Route walked to its end: clear, and keep FREE_FIRE velocity matching off for a while. */
    default void sewv$finishPathway() {
        sewv$clearPathway();
        ((Entity) this).getPersistentData().putLong(TAG_PASSIVE_COOLDOWN,
                ((Entity) this).level().getGameTime() + PathwaySupport.PASSIVE_COOLDOWN);
    }

    /** Records that a MOVE to this destination (packed BlockPos) has already been funnelled once. */
    default void sewv$markFunneledFor(long dest) {
        ((Entity) this).getPersistentData().putLong(TAG_FUNNELED_DEST, dest);
    }

    default boolean sewv$wasFunneledFor(long dest) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_FUNNELED_DEST) && tag.getLong(TAG_FUNNELED_DEST) == dest;
    }

    default boolean sewv$isPathwayPassiveBlocked() {
        long until = ((Entity) this).getPersistentData().getLong(TAG_PASSIVE_COOLDOWN);
        return until > 0L && ((Entity) this).level().getGameTime() < until;
    }

    default long sewv$getPathwayStaleCheck() {
        return ((Entity) this).getPersistentData().getLong(TAG_STALE_CHECK);
    }

    default void sewv$setPathwayStaleCheck(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_STALE_CHECK, gameTime);
    }

    default List<BlockPos> sewv$getPathwayRoute() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_ROUTE)) return List.of();
        long[] packed = tag.getLongArray(TAG_ROUTE);
        List<BlockPos> route = new ArrayList<>(packed.length);
        for (long l : packed) route.add(BlockPos.of(l));
        return route;
    }

    default int sewv$getPathwayStep() {
        return ((Entity) this).getPersistentData().getInt(TAG_STEP);
    }

    default void sewv$setPathwayStep(int step) {
        ((Entity) this).getPersistentData().putInt(TAG_STEP, step);
    }

    default long sewv$getPathwayStepDeadline() {
        return ((Entity) this).getPersistentData().getLong(TAG_STEP_DEADLINE);
    }

    default void sewv$setPathwayStepDeadline(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_STEP_DEADLINE, gameTime);
    }

    default String sewv$getPathwaySourceId() {
        return ((Entity) this).getPersistentData().getString(TAG_SOURCE_ID);
    }
}
