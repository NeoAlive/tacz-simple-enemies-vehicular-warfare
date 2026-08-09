package com.neoalive.tacz_sewv.bridge;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Persistent ENTRENCHED task: hold a trench-network cell (and optionally crew a linked
 * emplacement). Id-free so it survives save/load — same family as {@link IVehiclePatrol}.
 */
public interface IEntrenched {

    String TAG_ACTIVE = "tacz_sewv_entrench";
    String TAG_NETWORK_SEED = "tacz_sewv_entrench_seed";
    String TAG_CELL = "tacz_sewv_entrench_cell";
    String TAG_EMP = "tacz_sewv_entrench_emp";
    String TAG_REROLL_AT = "tacz_sewv_entrench_reroll";

    default boolean sewv$isEntrenched() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_ACTIVE);
    }

    default void sewv$setEntrenched(long networkSeed, BlockPos cell, @Nullable BlockPos emplacement) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putLong(TAG_NETWORK_SEED, networkSeed);
        tag.putLong(TAG_CELL, cell.asLong());
        if (emplacement != null) {
            tag.putLong(TAG_EMP, emplacement.asLong());
        } else {
            tag.remove(TAG_EMP);
        }
        tag.remove(TAG_REROLL_AT);
    }

    default void sewv$clearEntrenched() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.remove(TAG_ACTIVE);
        tag.remove(TAG_NETWORK_SEED);
        tag.remove(TAG_CELL);
        tag.remove(TAG_EMP);
        tag.remove(TAG_REROLL_AT);
    }

    default long sewv$getEntrenchNetworkSeed() {
        return ((Entity) this).getPersistentData().getLong(TAG_NETWORK_SEED);
    }

    @Nullable
    default BlockPos sewv$getEntrenchCell() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_CELL) ? BlockPos.of(tag.getLong(TAG_CELL)) : null;
    }

    default void sewv$setEntrenchCell(BlockPos cell) {
        ((Entity) this).getPersistentData().putLong(TAG_CELL, cell.asLong());
    }

    @Nullable
    default BlockPos sewv$getEntrenchEmplacement() {
        CompoundTag tag = ((Entity) this).getPersistentData();
        return tag.contains(TAG_EMP) ? BlockPos.of(tag.getLong(TAG_EMP)) : null;
    }

    default void sewv$clearEntrenchEmplacement() {
        ((Entity) this).getPersistentData().remove(TAG_EMP);
    }

    default long sewv$getEntrenchRerollAt() {
        return ((Entity) this).getPersistentData().getLong(TAG_REROLL_AT);
    }

    default void sewv$setEntrenchRerollAt(long gameTime) {
        ((Entity) this).getPersistentData().putLong(TAG_REROLL_AT, gameTime);
    }
}
