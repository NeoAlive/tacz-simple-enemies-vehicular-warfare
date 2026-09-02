package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;

public class StockpileAmmoBlock extends AbstractFobDecorBlock {

    public StockpileAmmoBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.5f, 6.0f)
                .sound(SoundType.METAL)
                .noOcclusion()
                .isSuffocating((s, g, p) -> false)
                .isViewBlocking((s, g, p) -> false));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        FobSubBlock.onSubPlaced(level, pos, "stockpile", placer);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        FobManager mgr = FobManager.get((ServerLevel) level);
        FobInstance fob = mgr.getFobAt(pos, level);
        if (fob == null || !serverPlayer.getUUID().equals(fob.owner)) {
            return InteractionResult.FAIL;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof StockpileBlockEntity stockpile)) return InteractionResult.PASS;
        NetworkHooks.openScreen(serverPlayer, stockpile, pos);
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StockpileBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof StockpileBlockEntity stockpile && level instanceof ServerLevel server) {
                for (int i = 0; i < stockpile.getItems().getSlots(); i++) {
                    Containers.dropItemStack(server, pos.getX(), pos.getY(), pos.getZ(),
                            stockpile.getItems().getStackInSlot(i));
                }
            }
            FobSubBlock.onSubRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
