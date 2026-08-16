package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared map-node rules for {@link TeamBaseBlock} / {@link CapturePointBlock}: immortal to
 * explosions, and only operators may break or configure them.
 */
public abstract class InvasionNodeBlock extends BaseEntityBlock {

    protected InvasionNodeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Op-only mining — creative alone is not enough (map grief / accidental break). */
    @Override
    @SuppressWarnings("deprecation")
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!player.hasPermissions(2)) {
            return 0.0f;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    /** SBW / vanilla blasts must not delete match nodes even if resistance is somehow bypassed. */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        // intentionally empty
    }

    @Override
    public boolean canDropFromExplosion(BlockState state, BlockGetter level, BlockPos pos,
                                        Explosion explosion) {
        return false;
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos,
                                        Explosion explosion) {
        return Float.MAX_VALUE;
    }
}
