package com.neoalive.tacz_sewv.block;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.fob.FobInstance;
import com.neoalive.tacz_sewv.fob.FobManager;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public class StockpileAmmoBlock extends AbstractFobDecorBlock {

    /** Set around {@link #playerWillDestroy} so {@link #onRemove} knows who broke it. */
    private static final ThreadLocal<UUID> BREAKING_PLAYER = new ThreadLocal<>();

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

    /** Survival: non-owners cannot dig. Creative still hits {@link #onBreakCancel}. */
    @Override
    @SuppressWarnings("deprecation")
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            FobInstance fob = FobManager.get(server).getFobAt(pos, server);
            if (fob != null && !player.getUUID().equals(fob.owner)) {
                return 0.0f;
            }
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // onRemove runs after this returns (ServerPlayerGameMode.destroyBlock), so leave the
        // ThreadLocal set until onRemove clears it.
        if (!level.isClientSide()) {
            BREAKING_PLAYER.set(player.getUUID());
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        try {
            if (!state.is(newState.getBlock())) {
                if (level instanceof ServerLevel server
                        && level.getBlockEntity(pos) instanceof StockpileBlockEntity stockpile
                        && mayDropContents(server, pos)) {
                    for (int i = 0; i < stockpile.getItems().getSlots(); i++) {
                        Containers.dropItemStack(server, pos.getX(), pos.getY(), pos.getZ(),
                                stockpile.getItems().getStackInSlot(i));
                    }
                }
                FobSubBlock.onSubRemoved(level, pos);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        } finally {
            BREAKING_PLAYER.remove();
        }
    }

    /** Owner break or non-player removal (explosion / command) may spill; grief harvest must not. */
    private static boolean mayDropContents(ServerLevel level, BlockPos pos) {
        FobInstance fob = FobManager.get(level).getFobAt(pos, level);
        if (fob == null) return true;
        UUID breaker = BREAKING_PLAYER.get();
        return breaker == null || breaker.equals(fob.owner);
    }

    @SubscribeEvent
    public static void onBreakCancel(BlockEvent.BreakEvent event) {
        if (!(event.getState().getBlock() instanceof StockpileAmmoBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FobInstance fob = FobManager.get(level).getFobAt(event.getPos(), level);
        if (fob != null && !event.getPlayer().getUUID().equals(fob.owner)) {
            event.setCanceled(true);
        }
    }
}
