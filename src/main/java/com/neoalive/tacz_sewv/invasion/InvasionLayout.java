package com.neoalive.tacz_sewv.invasion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension counter for ascending {@code capture_point} IDs.
 * Uniqueness is still validated at {@code /sewv invasion start}; this only seeds new placements.
 */
public class InvasionLayout extends SavedData {

    private static final String DATA_NAME = "tacz_sewv_invasion_layout";

    private int nextCapturePointId;

    public InvasionLayout() {
    }

    public static InvasionLayout load(CompoundTag nbt) {
        InvasionLayout data = new InvasionLayout();
        data.nextCapturePointId = Math.max(0, nbt.getInt("NextCapturePointId"));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag nbt) {
        nbt.putInt("NextCapturePointId", nextCapturePointId);
        return nbt;
    }

    public static InvasionLayout get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(InvasionLayout::load, InvasionLayout::new, DATA_NAME);
    }

    /** Claims the next ascending ID and persists. */
    public int claimNextId() {
        int id = nextCapturePointId++;
        setDirty();
        return id;
    }

    /** Ensures the counter stays above any manually-edited ID so new places do not collide. */
    public void noteExistingId(int id) {
        if (id >= nextCapturePointId) {
            nextCapturePointId = id + 1;
            setDirty();
        }
    }
}
