package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * Downed (bleeding out, not dead) state for a PMC unit — this mod's own mechanic, and it never
 * touches PlayerReviveMod's own API. That mod's {@code IBleeding} capability is attached only to
 * {@code Player} entities ({@code AttachCapabilitiesEvent<Entity>} gates on {@code instanceof
 * Player}), so an NPC can never hold it. It is nonetheless gated on PlayerReviveMod's presence
 * ({@code PmcDownedSupport#onDeath}, {@code MixinPmcUnitEntity}'s goal-add gate) — see
 * {@code PmcDownedSupport}'s class doc for why. Only {@code PmcUnitEntity} implements this
 * ({@code MixinPmcUnitEntity}) — RU/US never go down, they simply die.
 *
 * <p>Persistent rather than a transient mixin field, same reasoning as {@link IHelicopterPilot}'s
 * flight command: a downed PMC must still be downed after a chunk reload, not silently revert to
 * normal (and re-trigger the same lethal hit as a fresh, un-cancellable death) because a transient
 * flag reset to {@code false}. The deadline is an absolute game time, not a countdown, so it
 * survives save/load meaning the same moment — same shape as {@code bridge/FireMission}'s deadline.
 *
 * <p>{@code sewv$isDowned}/{@code sewv$setDowned}/{@code sewv$downedDeadline} are the durable,
 * server-authoritative state and are untouched by rendering. {@code sewv$isDownedSynced} /
 * {@code sewv$setDownedSynced} are a separate, deliberately abstract (not default) pair — like
 * {@code IMedicTreat} — backed by a {@code SynchedEntityData} flag in {@code MixinPmcUnitEntity},
 * because persistent NBT is never replicated to the client. {@code DownedGoal} keeps the two in
 * sync every tick, which is also what makes the synced flag correct again after a reload without
 * needing a save/load hook of its own.
 */
public interface IPmcDowned {

    String TAG_DOWNED = "sewv_pmc_downed";
    String TAG_DOWNED_DEADLINE = "sewv_pmc_downed_deadline";

    default boolean sewv$isDowned() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_DOWNED);
    }

    /** {@code deadline} is an absolute game time — the moment an unrevived unit dies for real. */
    default void sewv$setDowned(boolean downed, long deadline) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        tag.putBoolean(TAG_DOWNED, downed);
        tag.putLong(TAG_DOWNED_DEADLINE, deadline);
    }

    default long sewv$downedDeadline() {
        return ((Entity) this).getPersistentData().getLong(TAG_DOWNED_DEADLINE);
    }

    /** Client-visible mirror of {@link #sewv$isDowned()} — read this from rendering code, not that. */
    boolean sewv$isDownedSynced();

    void sewv$setDownedSynced(boolean downed);
}
