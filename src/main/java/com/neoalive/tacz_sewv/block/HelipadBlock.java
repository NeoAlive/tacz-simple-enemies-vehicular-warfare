package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Decorative helipad decal. The model paints a ~2.4 x 3 block pad from one placed block (its
 * elements run outside the block cube on purpose), so only the placed block's own 0.5-pixel
 * skin has a hitbox.
 */
public class HelipadBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 0.5, 16);

    public HelipadBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(1.0f, 6.0f)
                .sound(SoundType.STONE)
                .noOcclusion()
                .isSuffocating((s, g, p) -> false)
                .isViewBlocking((s, g, p) -> false));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }
}
