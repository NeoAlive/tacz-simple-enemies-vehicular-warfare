package com.neoalive.tacz_sewv.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Persistent invasion CAPTURE_POINT pipeline for AI-fleet crews.
 * Target is a {@link BlockPos} (survives reload) — order among points is vicinity to the
 * crew's own team_base, not a point id.
 */
public interface ICaptureOrder {

    String TAG_ACTIVE = "tacz_sewv_capture_order";
    /** Current objective centre ({@link BlockPos#asLong()}). */
    String TAG_TARGET = "tacz_sewv_capture_target";
    /**
     * Objective kind: {@link #KIND_POINT} = capture_point, {@link #KIND_BASE} = team_base,
     * {@link #KIND_NONE} = nothing left (hold last).
     */
    String TAG_KIND = "tacz_sewv_capture_kind";

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
        tag.remove("tacz_sewv_capture_point_id"); // legacy Stage F tag
    }

    default void sewv$clearCaptureOrder() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ACTIVE);
        tag.remove(TAG_TARGET);
        tag.remove(TAG_KIND);
        tag.remove("tacz_sewv_capture_point_id");
    }

    default int sewv$getCaptureKind() {
        return ((Entity) this).getPersistentData().getInt(TAG_KIND);
    }

    @javax.annotation.Nullable
    default BlockPos sewv$getCaptureTarget() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (!tag.contains(TAG_TARGET)) return null;
        return BlockPos.of(tag.getLong(TAG_TARGET));
    }

    default void sewv$setCapturePoint(BlockPos pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_POINT);
        tag.putLong(TAG_TARGET, pos.asLong());
        tag.remove("tacz_sewv_capture_point_id");
    }

    default void sewv$setCaptureBase(BlockPos pos) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_BASE);
        tag.putLong(TAG_TARGET, pos.asLong());
        tag.remove("tacz_sewv_capture_point_id");
    }

    /** No remaining objectives — keep the last TARGET if any so the hull holds ground. */
    default void sewv$setCaptureDone() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putInt(TAG_KIND, KIND_NONE);
        tag.remove("tacz_sewv_capture_point_id");
    }
}
