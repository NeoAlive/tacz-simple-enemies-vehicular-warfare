package com.neoalive.tacz_sewv.util;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block raycast that never loads a chunk.
 *
 * <p>{@code Level.clip} reads blocks through {@code Level.getBlockState}, which on the server
 * falls through to {@code ServerChunkCache.getChunk(..., load = true)} for a chunk that is not
 * loaded — a synchronous disk load on the main thread, followed by an unload-time save a little
 * later. An AI line-of-fire check from a hull to a target hundreds of blocks away crosses exactly
 * such chunks: in one large-battle profile the fire gate's two clips spent 7.5 ms/tick blocked in
 * chunk loads. This view reads only chunks already loaded ({@code getChunkNow}) and treats
 * anything else as air: terrain nobody can see is assumed open, which is the same answer the AI
 * gets with no check at all. Use it for every AI raycast that can reach past its own chunk.
 */
public final class LoadedClip implements BlockGetter {

    private final Level level;
    // A ray walks consecutive blocks, so the same chunk is asked for again and again.
    private int cachedX = Integer.MIN_VALUE;
    private int cachedZ = Integer.MIN_VALUE;
    @Nullable
    private LevelChunk cached;

    private LoadedClip(Level level) {
        this.level = level;
    }

    public static BlockHitResult clip(Level level, ClipContext context) {
        return new LoadedClip(level).clip(context);
    }

    @Nullable
    private LevelChunk chunkAt(BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        if (cx != this.cachedX || cz != this.cachedZ) {
            this.cachedX = cx;
            this.cachedZ = cz;
            this.cached = this.level.getChunkSource().getChunkNow(cx, cz);
        }
        return this.cached;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (this.level.isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
        LevelChunk chunk = chunkAt(pos);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (this.level.isOutsideBuildHeight(pos)) return Fluids.EMPTY.defaultFluidState();
        LevelChunk chunk = chunkAt(pos);
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        LevelChunk chunk = chunkAt(pos);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }
}
