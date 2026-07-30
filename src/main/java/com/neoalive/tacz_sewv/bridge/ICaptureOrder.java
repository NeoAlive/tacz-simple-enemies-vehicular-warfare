package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Persistent invasion CAPTURE_POINT pipeline for AI-fleet crews.
 * Id-free (point id + BlockPos), so it survives reload mid-match — same shape as
 * {@link IVehiclePatrol} / fire missions, not an entity-network-id order.
 *
 * <p>Only written for units tagged {@code sewv:invasion_ai}. Inactive = no tags = zero behaviour change.
 */
public interface ICaptureOrder {

    String TAG_ACTIVE = "tacz_sewv_capture_order";
    /** Current objective centre ({@link BlockPos#asLong()}). */
    String TAG_TARGET = "tacz_sewv_capture_target";
    /**
     * Objective kind: {@link #KIND_POINT} = capture_point by id, {@link #KIND_BASE} = team_base pos,
     * {@link #KIND_NONE} = nothing left (hold last).
     */
    String TAG_KIND = "tacz_sewv_capture_kind";
    String TAG_POINT_ID = "tacz_sewv_capture_point_id";

    int KIND_NONE = 0;
    int KIND_POINT = 1;
    int KIND_BASE = 2;

    default boolean sewv$hasCaptureOrder() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_ACTIVE);
    }

    default void sewv$beginCaptureOrder() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.remove(TAG_TARGET);
        tag.putInt(TAG_KIND, KIND_NONE);
        tag.remove(TAG_POINT_ID);
    }

    default void sewv$clearCaptureOrder() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ACTIVE);
        tag.remove(TAG_TARGET);
        tag.remove(TAG_KIND);
        tag.remove(TAG_POINT_ID);
    }

    default int sewv$getCaptureKind() {
        return ((Entity) this).getPersistentData().getInt(TAG_KIND);
    }

    default int sewv$getCapturePointId() {
        return ((Entity) this).getPersistentData().contains(TAG_POINT_ID)
                ? ((Entity) this).getPersistentData().getInt(TAG_POINT_ID)
                : -1;
    }

    @javax.annotation.Nullable
    default BlockPos sewv$getCaptureTarget() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_TARGET)) return null;
        return BlockPos.of(tag.getLong(TAG_TARGET));
    }

    default void sewv$setCapturePoint(int pointId, BlockPos pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_POINT);
        tag.putInt(TAG_POINT_ID, pointId);
        tag.putLong(TAG_TARGET, pos.asLong());
    }

    default void sewv$setCaptureBase(BlockPos pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_BASE);
        tag.remove(TAG_POINT_ID);
        tag.putLong(TAG_TARGET, pos.asLong());
    }

    /** No remaining objectives — keep the last TARGET if any so the hull holds ground. */
    default void sewv$setCaptureDone() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_NONE);
        tag.remove(TAG_POINT_ID);
        // leave TAG_TARGET so destination can still hold the last point
    }
}
