package com.neoalive.tacz_sewv.block;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.neoalive.tacz_sewv.init.ModBlockEntities;
import com.neoalive.tacz_sewv.zone.SpawnZoneData;

/** Survival no-zone source. See {@link ZoneBeaconBlockEntity}. */
public class ZoneBeaconBlock extends BaseEntityBlock {

    // NeoForge port: 1.21 BaseEntityBlock requires a codec; this block takes no configurable properties.
    public static final MapCodec<ZoneBeaconBlock> CODEC = simpleCodec(properties -> new ZoneBeaconBlock());

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public ZoneBeaconBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F)
                .sound(SoundType.METAL).noOcclusion());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable("block.tacz_sewv.zone_beacon.desc").withStyle(ChatFormatting.GRAY));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ZoneBeaconBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.ZONE_BEACON.get(), ZoneBeaconBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p
                && level.getBlockEntity(pos) instanceof ZoneBeaconBlockEntity be) {
            be.setOwner(p.getUUID());
        }
    }

    // NeoForge port: 1.21 split Block#use into useWithoutItem/useItemOn.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ZoneBeaconBlockEntity be)
                || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        if (!be.mayEdit(sp)) {
            sp.displayClientMessage(Component.translatable("message.tacz_sewv.zone_beacon.not_owner"), true);
            return InteractionResult.CONSUME;
        }
        sp.openMenu(be, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel s) {
            if (s.getBlockEntity(pos) instanceof ZoneBeaconBlockEntity be) {
                Containers.dropItemStack(s, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        be.battery().getStackInSlot(0).copy());
                be.battery().setStackInSlot(0, ItemStack.EMPTY);
            }
            SpawnZoneData.get(s).removeBeacon(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
