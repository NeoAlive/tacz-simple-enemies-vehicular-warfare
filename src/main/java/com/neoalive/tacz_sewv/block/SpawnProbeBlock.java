package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import com.neoalive.tacz_sewv.client.SpawnProbeClient;

/**
 * Structure-prep marker: barrier-like (unbreakable / undroppable / invisible), but
 * traversable. Visibility is world-level via {@code sewvShowSpawnProbes}
 * ({@code /sewv debug ShowSpawnProbes}) — same MODEL/INVISIBLE flip as holding a barrier.
 * Right-click (op) opens the vehicle-list editor; data lives in the block entity NBT.
 */
public class SpawnProbeBlock extends InvasionNodeBlock {

    public SpawnProbeBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.NONE)
                .strength(-1.0f, 3600000.8f)
                .noLootTable()
                .noOcclusion()
                .noCollission()
                .sound(SoundType.STONE)
                .pushReaction(PushReaction.BLOCK)
                .isValidSpawn((s, g, p, t) -> false)
                .isRedstoneConductor((s, g, p) -> false)
                .isSuffocating((s, g, p) -> false)
                .isViewBlocking((s, g, p) -> false));
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (!SpawnProbeEditor.mayEdit(serverPlayer)) {
            SpawnProbeEditor.deny(serverPlayer);
            return InteractionResult.CONSUME;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SpawnProbeBlockEntity probe)) return InteractionResult.PASS;
        SpawnProbeEditor.open(serverPlayer, probe);
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpawnProbeBlockEntity(pos, state);
    }

    /**
     * Mirrors barrier's hold-to-see path: MODEL when the world gamerule is on, else INVISIBLE.
     * Client-only read via DistExecutor so dedicated servers never touch client classes.
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        Boolean show = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> SpawnProbeClient::showProbes);
        return Boolean.TRUE.equals(show) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.block();
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos,
                                     CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
