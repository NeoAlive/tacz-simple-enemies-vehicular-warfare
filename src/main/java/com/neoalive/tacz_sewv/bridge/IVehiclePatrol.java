package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * An area task carried on a unit entity and read by {@link com.neoalive.tacz_sewv.entity.ai.VehicleTargeting}
 * through {@link com.neoalive.tacz_sewv.entity.ai.PatrolSupport}: the driver of a ground hull wanders valid
 * ground inside a circle around an origin, moving to a fresh point on a timer.
 *
 * <p>Everything here is id-free (a BlockPos origin, a radius, a route of BlockPos, the current
 * waypoint, an absolute game-time deadline), so unlike the boarding order or mortar claim it is safe to PERSIST — a
 * patrol set before a save should still be running after the reload. Stored in the entity's Forge
 * persistent data; the unit mixins only need {@code implements}, these defaults are the whole thing.
 * A missing origin means "not patrolling".
 */
public interface IVehiclePatrol {

    /** Endless wander of the area, re-rolling a waypoint on a timer. */
    int MODE_PATROL = 0;
    /** One-time sweep: each hull covers its own angular sector, then stands down. */
    int MODE_SEARCH = 1;
    /** Endless loop of player-plotted waypoints, in order. Uses {@link #TAG_ROUTE}. */
    int MODE_CRUISE = 2;

    String TAG_ORIGIN = "tacz_sewv_patrol_origin";
    String TAG_RADIUS = "tacz_sewv_patrol_radius";
    String TAG_WAYPOINT = "tacz_sewv_patrol_waypoint";
    String TAG_NEXT_ROTATE = "tacz_sewv_patrol_next_rotate";
    String TAG_MODE = "tacz_sewv_patrol_mode";
    String TAG_SECTOR = "tacz_sewv_patrol_sector";
    String TAG_SECTOR_COUNT = "tacz_sewv_patrol_sector_count";
    String TAG_STEP = "tacz_sewv_patrol_step";
    String TAG_STEP_DEADLINE = "tacz_sewv_patrol_step_deadline";
    String TAG_ROUTE = "tacz_sewv_patrol_route";

    /**
     * Begin (or replace) an area task. Clears the waypoint and sweep progress so the next resolve
     * starts fresh. The two modes share one state slot because a hull can only be doing one of
     * them — issuing either cancels the other by construction.
     */
    default void sewv$setAreaTask(BlockPos origin, int radius, int mode, int sector, int sectorCount) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putLong(TAG_ORIGIN, origin.asLong());
        tag.putInt(TAG_RADIUS, radius);
        tag.putInt(TAG_MODE, mode);
        tag.putInt(TAG_SECTOR, sector);
        tag.putInt(TAG_SECTOR_COUNT, sectorCount);
        tag.putInt(TAG_STEP, 0);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT_ROTATE);
        tag.remove(TAG_STEP_DEADLINE);
    }

    /**
     * Begin (or replace) a cruise: an endless loop of the given waypoints in order.
     *
     * <p>Deliberately stored in the SAME slot as patrol and search rather than in a system of its
     * own. A hull can only be doing one area task, {@code TAG_ORIGIN} is what everything else tests
     * to mean "has one" (dismiss, the order stand-down in {@code MixinPacketIssueOrder},
     * {@code resolveDestination}'s branch), and {@code TAG_STEP} is already the index-into-progress
     * field. Setting the origin to the first node buys all of that for free; the route itself is the
     * only new tag. A route is id-free, so like the rest of this state it PERSISTS.
     */
    default void sewv$setCruise(List<BlockPos> route) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        long[] packed = new long[route.size()];
        for (int i = 0; i < route.size(); i++) packed[i] = route.get(i).asLong();
        sewv$setAreaTask(route.get(0), 0, MODE_CRUISE, 0, 1);
        tag.putLongArray(TAG_ROUTE, packed);
    }

    /** The cruise legs in order; empty when this crew is not cruising. */
    default List<BlockPos> sewv$getCruiseRoute() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_ROUTE)) return List.of();
        long[] packed = tag.getLongArray(TAG_ROUTE);
        List<BlockPos> route = new ArrayList<>(packed.length);
        for (long l : packed) route.add(BlockPos.of(l));
        return route;
    }

    default void sewv$clearPatrol() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ORIGIN);
        tag.remove(TAG_RADIUS);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT_ROTATE);
        tag.remove(TAG_MODE);
        tag.remove(TAG_SECTOR);
        tag.remove(TAG_SECTOR_COUNT);
        tag.remove(TAG_STEP);
        tag.remove(TAG_STEP_DEADLINE);
        tag.remove(TAG_ROUTE);
    }

    default int sewv$getPatrolMode() {
        return ((Entity) this).getPersistentData().getInt(TAG_MODE);
    }

    default int sewv$getPatrolSector() {
        return ((Entity) this).getPersistentData().getInt(TAG_SECTOR);
    }

    default int sewv$getPatrolSectorCount() {
        return ((Entity) this).getPersistentData().getInt(TAG_SECTOR_COUNT);
    }

    default int sewv$getPatrolStep() {
        return ((Entity) this).getPersistentData().getInt(TAG_STEP);
    }

    default void sewv$setPatrolStep(int step) {
        ((Entity) this).getPersistentData().putInt(TAG_STEP, step);
    }

    default long sewv$getPatrolStepDeadline() {
        return ((Entity) this).getPersistentData().getLong(TAG_STEP_DEADLINE);
    }

    default void sewv$setPatrolStepDeadline(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_STEP_DEADLINE, gameTime);
    }

    default boolean sewv$isPatrolling() {
        return ((Entity) this).getPersistentData().contains(TAG_ORIGIN);
    }

    default BlockPos sewv$getPatrolOrigin() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_ORIGIN) ? BlockPos.of(tag.getLong(TAG_ORIGIN)) : null;
    }

    default int sewv$getPatrolRadius() {
        return ((Entity) this).getPersistentData().getInt(TAG_RADIUS);
    }

    default BlockPos sewv$getPatrolWaypoint() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_WAYPOINT) ? BlockPos.of(tag.getLong(TAG_WAYPOINT)) : null;
    }

    default void sewv$setPatrolWaypoint(BlockPos pos) {
        ((Entity) this).getPersistentData().putLong(TAG_WAYPOINT, pos.asLong());
    }

    default long sewv$getPatrolNextRotate() {
        return ((Entity) this).getPersistentData().getLong(TAG_NEXT_ROTATE);
    }

    default void sewv$setPatrolNextRotate(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_NEXT_ROTATE, gameTime);
    }
}
