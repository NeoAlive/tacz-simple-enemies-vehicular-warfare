package com.neoalive.tacz_sewv.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * "Is this chunk loaded?" that can never cause a load.
 *
 * <p>{@code Level.hasChunk} does not answer that on the server. {@code ServerChunkCache.hasChunk}
 * is true for any chunk holder whose ticket level reaches FULL, whether or not the chunk has
 * finished loading or generating. The {@code getHeight} or {@code getBlockState} read that follows
 * then blocks the main thread until it does: a disk load, or worldgen on fresh terrain. Aircraft
 * probing terrain ahead of their own chunk tickets hit that every tick (17% of the server thread
 * and a 5 s tick in one profile).
 *
 * <p>Here the server answers from {@code getChunkNow}, which only returns a chunk that is already
 * FULL, so a read guarded by this never waits. The client keeps {@code hasChunk}, which is a plain
 * cache lookup there. Same rule as {@link LoadedClip}.
 */
public final class LoadedTerrain {

    private LoadedTerrain() {}

    public static boolean loaded(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel server) {
            return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        }
        return level.hasChunk(chunkX, chunkZ);
    }

    public static boolean loadedAt(Level level, int blockX, int blockZ) {
        return loaded(level, SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
    }

    public static boolean loadedAt(Level level, BlockPos pos) {
        return loadedAt(level, pos.getX(), pos.getZ());
    }
}
