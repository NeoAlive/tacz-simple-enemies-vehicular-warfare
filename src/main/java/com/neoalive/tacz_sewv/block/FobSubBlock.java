package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;

abstract class FobSubBlock extends Block {

    private final String linkType;

    FobSubBlock(String linkType) {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(2.5f, 6.0f)
                .sound(SoundType.STONE));
        this.linkType = linkType;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        onSubPlaced(level, pos, this.linkType, placer);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            onSubRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    static void onSubPlaced(Level level, BlockPos pos, String linkType, @Nullable LivingEntity placer) {
        if (level.isClientSide()) return;
        ServerLevel server = (ServerLevel) level;
        FobManager mgr = FobManager.get(server);
        FobInstance fob = mgr.getFobAt(pos, level);
        if (fob == null) {
            level.destroyBlock(pos, false);
            if (placer instanceof ServerPlayer player) {
                FobManager.denyPlacement(player, "message.tacz_sewv.fob.no_command_block");
            }
            return;
        }
        mgr.linkSubBlock(fob.commandPos, pos, linkType, level);
    }

    static void onSubRemoved(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        FobManager mgr = FobManager.get((ServerLevel) level);
        FobInstance fob = mgr.getFobAt(pos, level);
        if (fob != null) {
            mgr.unlinkSubBlock(fob.commandPos, pos, level);
        }
    }
}
