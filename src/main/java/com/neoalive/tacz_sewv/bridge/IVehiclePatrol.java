package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * A patrol order carried on a unit entity and read by {@link com.neoalive.tacz_sewv.entity.ai.VehicleTargeting}
 * through {@link com.neoalive.tacz_sewv.entity.ai.PatrolSupport}: the driver of a ground hull wanders valid
 * ground inside a circle around an origin, moving to a fresh point on a timer.
 *
 * <p>Everything here is id-free (a BlockPos origin, a radius, the current waypoint, an absolute
 * game-time deadline), so unlike the boarding order or mortar claim it is safe to PERSIST — a
 * patrol set before a save should still be running after the reload. Stored in the entity's Forge
 * persistent data; the unit mixins only need {@code implements}, these defaults are the whole thing.
 * A missing origin means "not patrolling".
 */
public interface IVehiclePatrol {

    String TAG_ORIGIN = "tacz_sewv_patrol_origin";
    String TAG_RADIUS = "tacz_sewv_patrol_radius";
    String TAG_WAYPOINT = "tacz_sewv_patrol_waypoint";
    String TAG_NEXT_ROTATE = "tacz_sewv_patrol_next_rotate";

    /** Begin (or re-origin) a patrol. Clears any current waypoint so the next resolve picks fresh. */
    default void sewv$setPatrol(BlockPos origin, int radius) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putLong(TAG_ORIGIN, origin.asLong());
        tag.putInt(TAG_RADIUS, radius);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT_ROTATE);
    }

    default void sewv$clearPatrol() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ORIGIN);
        tag.remove(TAG_RADIUS);
        tag.remove(TAG_WAYPOINT);
        tag.remove(TAG_NEXT_ROTATE);
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
