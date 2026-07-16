package com.neoalive.tacz_sewv.util;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import javax.annotation.Nullable;

/**
 * One force-loaded chunk held on an entity's behalf, following it as it moves.
 *
 * <p>Long-range AI only works while its chunk keeps ticking: a helicopter flown beyond
 * simulation distance stops flying, and a mortar crew shooting 770 blocks stops existing
 * well before its own shells land. Every such goal holds one of these per entity it needs
 * kept alive — the crew AND the tube for a mortar, since the AI lives on the crew and it
 * can stand across a chunk boundary from the weapon.
 *
 * <p>Entity-owned tickets are self-cleaning: they are not restored across a world reload
 * without a validation callback, which suits a live-operation aid rather than persistent
 * world state.
 */
public final class ChunkTicket {

    @Nullable
    private ChunkPos held;

    /**
     * Keeps the owner's current chunk loaded. Safe to call every tick — the ticket is only
     * re-issued when the owner crosses into a different chunk.
     */
    public void follow(Entity owner) {
        if (!(owner.level() instanceof ServerLevel level)) return;

        ChunkPos want = new ChunkPos(owner.blockPosition());
        if (want.equals(this.held)) return;

        if (this.held != null) force(level, owner, this.held, false);
        force(level, owner, want, true);
        this.held = want;
    }

    /** Hands the chunk back. A null owner (already gone) just drops the record. */
    public void release(@Nullable Entity owner) {
        if (this.held == null) return;
        if (owner != null && owner.level() instanceof ServerLevel level) {
            force(level, owner, this.held, false);
        }
        this.held = null;
    }

    /**
     * Takes the ticket that {@code owner}'s own goal will adopt on its first tick.
     *
     * <p>A goal can only hold its chunk from a tick, and an entity spawned outside
     * simulation distance never gets one — so something spawned far away would sit frozen
     * until a player walked to it. This bootstraps the loop. Same mod id, owner and chunk
     * as {@link #follow}, so the goal adopts this ticket rather than stacking a second one,
     * and the goal's release hands it back.
     */
    public static void bootstrap(ServerLevel level, Entity owner) {
        force(level, owner, new ChunkPos(owner.blockPosition()), true);
    }

    private static void force(ServerLevel level, Entity owner, ChunkPos chunk, boolean add) {
        ForgeChunkManager.forceChunk(level, TaczSewv.MODID, owner, chunk.x, chunk.z, add, true);
    }
}
