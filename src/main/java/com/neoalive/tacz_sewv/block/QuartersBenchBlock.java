package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;
import com.neoalive.tacz_sewv.fob.FobNetworking;

/**
 * Command block for a player-owned Forward Operating Base. One per player.
 */
public class QuartersBenchBlock extends Block {

    public QuartersBenchBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5f, 6.0f)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        if (level.isClientSide()) return;
        if (!(placer instanceof ServerPlayer player)) return;
        FobManager mgr = FobManager.get((net.minecraft.server.level.ServerLevel) level);
        if (mgr.getFobForOwner(player.getUUID()) != null) {
            level.destroyBlock(pos, false);
            FobManager.denyPlacement(player, "message.tacz_sewv.fob.already_owned");
            return;
        }
        mgr.addFob(pos, player.getUUID(), level);
        mgr.validate(pos, level);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        FobManager mgr = FobManager.get((net.minecraft.server.level.ServerLevel) level);
        FobInstance fob = mgr.getFob(pos);
        if (fob == null || !serverPlayer.getUUID().equals(fob.owner)) {
            return InteractionResult.FAIL;
        }
        FobNetworking.openGui(serverPlayer, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            FobManager.get((net.minecraft.server.level.ServerLevel) level).removeFob(pos,
                    (net.minecraft.server.level.ServerLevel) level);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
