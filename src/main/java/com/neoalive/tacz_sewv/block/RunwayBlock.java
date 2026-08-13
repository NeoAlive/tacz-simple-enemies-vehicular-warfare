package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.airport.AirportRegistry;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenAirportGui;

/** PMC runway marker: right-click opens the strip editor. */
public class RunwayBlock extends BaseEntityBlock {

    public RunwayBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(2.5f, 6.0f)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RunwayBlockEntity runway)) return InteractionResult.PASS;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                PacketOpenAirportGui.open(runway));
        return InteractionResult.CONSUME;
    }

    /** Forget on real break/replace only — not on chunk unload. */
    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            AirportRegistry.get(serverLevel).forget(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RunwayBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Cube is baked so neighbours still occlude against it; the mast is a BER on top.
        return RenderShape.MODEL;
    }
}
