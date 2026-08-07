package com.neoalive.tacz_sewv.invasion;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Session-scoped force-loads for every known capture_point / team_base chunk.
 * Tickets are owned by the node's {@link BlockPos} so they survive without a live entity.
 */
public final class InvasionTickets {

    private InvasionTickets() {}

    /** Force-load every registered node chunk. Safe to call repeatedly (idempotent add). */
    public static void ticketAll(ServerLevel level) {
        InvasionLayout layout = InvasionLayout.get(level);
        Set<Long> chunks = new HashSet<>();
        for (long packed : layout.capturePointPositions()) {
            BlockPos pos = BlockPos.of(packed);
            chunks.add(new ChunkPos(pos).toLong());
            force(level, pos, true);
        }
        for (long packed : layout.teamBasePositions()) {
            BlockPos pos = BlockPos.of(packed);
            chunks.add(new ChunkPos(pos).toLong());
            force(level, pos, true);
        }
        SewvDiag.invasion("tickets add nodes={} chunks≈{}",
                layout.capturePointPositions().size() + layout.teamBasePositions().size(),
                chunks.size());
    }

    /** Drop every registered node ticket (stop / orphan cleanup). */
    public static void releaseAll(ServerLevel level) {
        InvasionLayout layout = InvasionLayout.get(level);
        int n = 0;
        for (long packed : layout.capturePointPositions()) {
            force(level, BlockPos.of(packed), false);
            n++;
        }
        for (long packed : layout.teamBasePositions()) {
            force(level, BlockPos.of(packed), false);
            n++;
        }
        SewvDiag.invasion("tickets release n={}", n);
    }

    /**
     * Load one node's chunk for a validate read without a session ticket.
     * Failed starts must not leave Forge tickets behind — {@link #ticketAll} owns those.
     */
    public static void ensureLoaded(ServerLevel level, BlockPos pos) {
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void force(ServerLevel level, BlockPos owner, boolean add) {
        ChunkPos chunk = new ChunkPos(owner);
        ForgeChunkManager.forceChunk(level, TaczSewv.MODID, owner, chunk.x, chunk.z, add, true);
    }
}
