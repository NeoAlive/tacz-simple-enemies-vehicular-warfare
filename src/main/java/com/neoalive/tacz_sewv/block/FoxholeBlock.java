package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Standalone dirt-slab foxhole. No NSEW variants of its own, but adjacent
 * {@link TrenchBlock}s treat it as a connecting neighbour (same as a trench plinth cell).
 */
public class FoxholeBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 6, 16);

    private static final ThreadLocal<Boolean> PLAYER_EDIT = ThreadLocal.withInitial(() -> false);

    public FoxholeBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.DIRT)
                .strength(1.5f, 3.0f)
                .sound(SoundType.GRAVEL)
                .noOcclusion());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        TrenchBlock.onPlayerTopologyEdit(level, pos);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        PLAYER_EDIT.set(true);
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        boolean playerEdit = Boolean.TRUE.equals(PLAYER_EDIT.get());
        try {
            if (!state.is(newState.getBlock()) && playerEdit) {
                TrenchBlock.onPlayerTopologyEdit(level, pos, pos);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        } finally {
            if (playerEdit) {
                PLAYER_EDIT.set(false);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return type == PathComputationType.LAND;
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        // Prefer trench floors — see TrenchPathTypes (bootstrapped in mod ctor).
        return TrenchPathTypes.TRENCH;
    }
}
