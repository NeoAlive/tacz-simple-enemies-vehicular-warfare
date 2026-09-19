package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.airport.HelipadTraffic;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOpenHelipadGui;

/**
 * Helipad: a 3x3 pad made of one master block and eight part blocks around it.
 *
 * <p>Minecraft only hit-tests and collides a block's shape inside its own cell, so a single block
 * with an oversized shape would draw a big outline that could only be clicked through the middle
 * cell. Instead the master ({@link Part#CENTER}) carries the model and the block entity, and the
 * eight parts are invisible blocks whose shape is the slice of the model that falls in their cell.
 * A part finds its master from its own offset, so no lookup is stored.
 */
public class HelipadBlock extends BaseEntityBlock {

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** Model elements in master-cell pixels: {x0, x1, z0, z1}. Height is 0.5 px throughout. */
    private static final double[][] MODEL_BOXES = {
            {-11, -6, -16, 32},
            {-6, 22, 6, 10},
            {22, 27, -16, 32},
    };
    private static final VoxelShape[] SHAPES = buildShapes();

    /** North is -Z, east is +X. The master is the centre of the 3x3. */
    public enum Part implements StringRepresentable {
        CENTER("center", 0, 0),
        N("n", 0, -1),
        NE("ne", 1, -1),
        E("e", 1, 0),
        SE("se", 1, 1),
        S("s", 0, 1),
        SW("sw", -1, 1),
        W("w", -1, 0),
        NW("nw", -1, -1);

        private final String id;
        public final int dx;
        public final int dz;

        Part(String id, int dx, int dz) {
            this.id = id;
            this.dx = dx;
            this.dz = dz;
        }

        @Override
        public String getSerializedName() {
            return this.id;
        }
    }

    public HelipadBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(1.0f, 6.0f)
                .sound(SoundType.STONE)
                .noOcclusion()
                .isSuffocating((s, g, p) -> false)
                .isViewBlocking((s, g, p) -> false));
        registerDefaultState(this.stateDefinition.any().setValue(PART, Part.CENTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(PART);
    }

    /** Each part's shape is the model clipped to its own cell; a cell the model misses is empty. */
    private static VoxelShape[] buildShapes() {
        Part[] parts = Part.values();
        VoxelShape[] out = new VoxelShape[parts.length];
        for (Part part : parts) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : MODEL_BOXES) {
                double x0 = Math.max(0, b[0] - part.dx * 16);
                double x1 = Math.min(16, b[1] - part.dx * 16);
                double z0 = Math.max(0, b[2] - part.dz * 16);
                double z1 = Math.min(16, b[3] - part.dz * 16);
                if (x1 > x0 && z1 > z0) {
                    shape = Shapes.or(shape, net.minecraft.world.level.block.Block.box(x0, 0, z0, x1, 0.5, z1));
                }
            }
            out[part.ordinal()] = shape;
        }
        return out;
    }

    public static BlockPos masterOf(BlockPos pos, BlockState state) {
        Part part = state.getValue(PART);
        return pos.offset(-part.dx, 0, -part.dz);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(PART).ordinal()];
    }

    @Override
    @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == Part.CENTER ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.CENTER ? new HelipadBlockEntity(pos, state) : null;
    }

    /** The eight parts go down with the master; the item's own check has already proved the room. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        if (level.isClientSide) return;
        for (Part part : Part.values()) {
            if (part == Part.CENTER) continue;
            level.setBlock(pos.offset(part.dx, 0, part.dz), state.setValue(PART, part), 3);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos master = masterOf(pos, state);
        if (!(level.getBlockEntity(master) instanceof HelipadBlockEntity pad)) return InteractionResult.PASS;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                PacketOpenHelipadGui.of(pad, com.neoalive.tacz_sewv.airport.HelipadClearance.Status.NONE, null,
                        HelipadTraffic.isOccupied(serverLevel, master)));
        return InteractionResult.CONSUME;
    }

    /**
     * Breaking any part takes the whole pad with it, and only the master drops the item. Guarded on
     * each neighbour still being one of ours: {@code destroyBlock} re-enters this method, and the
     * position that started it is already air by then.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos master = masterOf(pos, state);
            for (Part part : Part.values()) {
                BlockPos other = master.offset(part.dx, 0, part.dz);
                if (other.equals(pos)) continue;
                BlockState there = level.getBlockState(other);
                if (there.is(this) && there.getValue(PART) == part) {
                    level.destroyBlock(other, part == Part.CENTER);
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
