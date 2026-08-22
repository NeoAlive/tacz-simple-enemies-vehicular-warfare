package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Captured (frozen, awaiting payment) state for an RU/US medic — this mod's own mechanic, entirely
 * separate from the PMC downed-revive system. Modeled on {@link IPmcDowned}'s durable-NBT + synced
 * mirror pattern for client rendering, with one deliberate divergence: on timeout, the captured
 * state simply ends (and AI resumes) rather than killing the unit.
 *
 * <p>Only {@code RuMedicEntity} and {@code UsMedicEntity} implement this interface (directly, not
 * via mixin — these are classes this mod owns outright). It is never added to
 * {@code MixinPmcUnitEntity} or any PMC class, which is what makes the PMC exclusion airtight
 * compared to using {@code IMedicTreat} (which a PMC holding a medical kit also implements).
 *
 * <p>Persistent state ({@code sewv$isCaptured}/{@code sewv$setCaptured}/{@code
 * sewv$capturedDeadline}) lives in NBT and survives a chunk reload. Synced state
 * ({@code sewv$isCapturedSynced}) is a separate {@code SynchedEntityData} mirror backed by an
 * {@code EntityDataAccessor} in the implementing class, because persistent NBT is never replicated
 * to the client. {@code MedicCapturedGoal} keeps the two in sync every tick, which is also what
 * makes the synced flag correct again after a reload without needing a save/load hook.
 */
public interface IMedicCaptured {

    String TAG_CAPTURED = "sewv_medic_captured";
    String TAG_CAPTURED_DEADLINE = "sewv_medic_captured_deadline";

    default boolean sewv$isCaptured() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_CAPTURED);
    }

    /** {@code deadline} is an absolute game time — the moment a captured medic escapes if unpaid. */
    default void sewv$setCaptured(boolean captured, long deadline) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_CAPTURED, captured);
        tag.putLong(TAG_CAPTURED_DEADLINE, deadline);
    }

    default long sewv$capturedDeadline() {
        return ((Entity) this).getPersistentData().getLong(TAG_CAPTURED_DEADLINE);
    }

    /** Client-visible mirror of {@link #sewv$isCaptured()} — read this from rendering code, not that. */
    boolean sewv$isCapturedSynced();

    void sewv$setCapturedSynced(boolean captured);
}
