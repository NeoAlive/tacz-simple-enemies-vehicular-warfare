package com.neoalive.tacz_sewv.util;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.init.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.phys.Vec3;

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
 *
 * <p>Never hand such a ray a {@code VehicleEntity} as its context entity: SBW's
 * {@code BlockStateMixin} answers {@code getCollisionShape} for a vehicle context by reading
 * {@code vehicle.level().getBlockState(pos)} itself, around whatever reader it was given, and so
 * loads the chunk anyway (4 ms/tick in a battle, after the reader above was already in place).
 * {@link #clipAsVehicle} reproduces SBW's vehicle rule here instead and casts with no entity.
 */
public final class LoadedClip implements BlockGetter {

    private final Level level;
    private final boolean vehiclePassThrough;
    // A ray walks consecutive blocks, so the same chunk is asked for again and again.
    private int cachedX = Integer.MIN_VALUE;
    private int cachedZ = Integer.MIN_VALUE;
    @Nullable
    private LevelChunk cached;

    private LoadedClip(Level level, boolean vehiclePassThrough) {
        this.level = level;
        this.vehiclePassThrough = vehiclePassThrough;
    }

    /** For a ray whose context entity is not a vehicle (a soldier, or none). */
    public static BlockHitResult clip(Level level, ClipContext context) {
        return new LoadedClip(level, false).clip(context);
    }

    /**
     * A ray seen the way SBW treats a vehicle: blocks in {@code #superbwarfare:vehicle_pass_through}
     * that are not axe-mineable have no collision — the same test SBW's {@code BlockStateMixin}
     * applies for a vehicle context (its dragon's-teeth height scaling is not reproduced; it only
     * changes a low obstacle's shape). The cast itself carries no entity, so that mixin never runs.
     */
    public static BlockHitResult clipAsVehicle(Level level, Vec3 from, Vec3 to,
            ClipContext.Block block, ClipContext.Fluid fluid) {
        return new LoadedClip(level, true).clip(new ClipContext(from, to, block, fluid, null));
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
        if (chunk == null) return Blocks.AIR.defaultBlockState();
        BlockState state = chunk.getBlockState(pos);
        if (this.vehiclePassThrough
                && state.is(ModTags.Blocks.VEHICLE_PASS_THROUGH)
                && !state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
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
