package com.neoalive.tacz_sewv.bridge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * A Territory Mode post: the chunk this unit was assigned to hold by the Frontline Tool. Id-free (two
 * chunk ints and a flag), so like {@link ISweepInfantry} it lives in the entity's persistent data and
 * survives a save/reload.
 *
 * <p>{@link #sewv$hasTerritoryPost} is read on the {@code setTarget} veto and by every MOVE goal on
 * every AI tick for every PMC, so it must stay a single field read for the (overwhelmingly common)
 * unit that has no post. That is why the three state methods are implemented by the mixin, which
 * caches the answer in a field, instead of being NBT defaults like the rest.
 */
public interface ITerritoryPost {

    String TAG_X = "tacz_sewv_terr_x";
    String TAG_Z = "tacz_sewv_terr_z";
    /** The player whose Territory Mode posted this unit; checked when the unit loads in to see if that mode is still on. */
    String TAG_BY = "tacz_sewv_terr_by";
    /** Assigned chunk was lost to another owner: the unit stops pulling toward it and holds where it is. */
    String TAG_LOST = "tacz_sewv_terr_lost";

    boolean sewv$hasTerritoryPost();

    /** Posts the unit on chunk (cx, cz); a fresh post is never "lost". */
    void sewv$setTerritoryPost(int chunkX, int chunkZ);

    void sewv$clearTerritoryPost();

    /**
     * Whether the unit has reached its post (within 3 blocks of the chunk centre) since it was posted. Sticky and
     * transient: it is what separates "still walking there" (fire opportunistically, never chase) from "holding it"
     * (pursue targets inside the leash), so it must survive a pursuit that carries the unit past the 6-block hold band.
     * Reset by {@link #sewv$setTerritoryPost} and {@link #sewv$clearTerritoryPost}; a reload starts it false again.
     */
    boolean sewv$hasReachedTerritoryPost();

    void sewv$setReachedTerritoryPost(boolean reached);

    default int sewv$getTerritoryChunkX() {
        return ((Entity) this).getPersistentData().getInt(TAG_X);
    }

    default int sewv$getTerritoryChunkZ() {
        return ((Entity) this).getPersistentData().getInt(TAG_Z);
    }

    default boolean sewv$isTerritoryLost() {
        return ((Entity) this).getPersistentData().getBoolean(TAG_LOST);
    }

    default void sewv$setTerritoryLost(boolean lost) {
        CompoundTag tag = ((Entity) this).getPersistentData();
        if (lost) tag.putBoolean(TAG_LOST, true);
        else tag.remove(TAG_LOST);
    }
}
