package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Two-tall earthworks trench. Absolute NSEW connection via {@link TrenchConnection}.
 * Four-way cardinal neighbours resolve to {@link TrenchConnection#PLINTH}; the {@code +}
 * junction is the separate {@link TrenchXCrossBlock}.
 */
public class TrenchBlock extends Block {

    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<TrenchConnection> CONNECTION =
            EnumProperty.create("connection", TrenchConnection.class);

    private static final VoxelShape[] LOWER = new VoxelShape[TrenchConnection.values().length];
    private static final VoxelShape[] UPPER = new VoxelShape[TrenchConnection.values().length];

    static {
        for (TrenchConnection c : TrenchConnection.values()) {
            LOWER[c.ordinal()] = buildLower(c);
            UPPER[c.ordinal()] = buildUpper(c);
        }
    }

    public TrenchBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.DIRT)
                .strength(2.0f, 6.0f)
                .sound(SoundType.GRAVEL)
                .noOcclusion());
        registerDefaultState(stateDefinition.any()
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(CONNECTION, TrenchConnection.LONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, CONNECTION);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    private static VoxelShape shapeFor(BlockState state) {
        TrenchConnection c = state.getValue(CONNECTION);
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER[c.ordinal()] : UPPER[c.ordinal()];
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1) return null;
        if (!level.getBlockState(pos.above()).canBeReplaced(ctx)) return null;

        BlockState lower = defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER);
        return withConnections(lower, level, pos);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    /**
     * Temporary axe cut: end → mid, or dig a walled face out to plinth. Not sticky — a later
     * {@link #updateShape} from neighbours may restore the natural connection.
     */
    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ItemTags.AXES)) return InteractionResult.PASS;

        TrenchConnection next = state.getValue(CONNECTION).axeConvert(hit.getDirection());
        if (next == null) return InteractionResult.PASS;

        if (!level.isClientSide) {
            BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            BlockState lower = level.getBlockState(lowerPos);
            if (!(lower.getBlock() instanceof TrenchBlock)) return InteractionResult.PASS;

            BlockState newLower = lower.setValue(CONNECTION, next);
            level.setBlock(lowerPos, newLower, 3);
            BlockPos upperPos = lowerPos.above();
            if (level.getBlockState(upperPos).getBlock() instanceof TrenchBlock) {
                level.setBlock(upperPos, newLower.setValue(HALF, DoubleBlockHalf.UPPER), 3);
            }
            level.playSound(null, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 1.0f, 1.0f);
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            BlockPos below = pos.below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        }
        BlockState below = level.getBlockState(pos.below());
        return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);

        if (direction.getAxis() == Direction.Axis.Y) {
            boolean towardMate = half == DoubleBlockHalf.LOWER ? direction == Direction.UP : direction == Direction.DOWN;
            if (towardMate) {
                if (neighbor.is(this) && neighbor.getValue(HALF) != half) {
                    return state.setValue(CONNECTION, neighbor.getValue(CONNECTION));
                }
                return Blocks.AIR.defaultBlockState();
            }
            if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, pos)) {
                return Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
        }

        return withConnections(state, level, pos);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            preventCreativeDropFromBottomPart(level, pos, state, player);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    private static void preventCreativeDropFromBottomPart(Level level, BlockPos pos, BlockState state, Player player) {
        if (state.getValue(HALF) != DoubleBlockHalf.UPPER) return;
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.is(state.getBlock()) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER) {
            BlockState replacement = belowState.getFluidState().is(Fluids.WATER)
                    ? Blocks.WATER.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
            level.setBlock(below, replacement, 35);
            level.levelEvent(player, 2001, below, Block.getId(belowState));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    private static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos) {
        boolean n = connects(level.getBlockState(pos.north()));
        boolean e = connects(level.getBlockState(pos.east()));
        boolean s = connects(level.getBlockState(pos.south()));
        boolean w = connects(level.getBlockState(pos.west()));
        return state.setValue(CONNECTION, TrenchConnection.fromNeighbors(n, e, s, w));
    }

    /** Regular trench segments and the manual {@code +} both open ends toward each other. */
    static boolean connects(BlockState neighbor) {
        Block block = neighbor.getBlock();
        return block instanceof TrenchBlock || block instanceof TrenchXCrossBlock;
    }

    private static VoxelShape buildLower(TrenchConnection c) {
        VoxelShape shape = Block.box(0, 0, 0, 16, 6, 16);
        return orWallRows(shape, c, 8, 10, 11, 13, 14, 16);
    }

    private static VoxelShape buildUpper(TrenchConnection c) {
        return orWallRows(Shapes.empty(), c, 1, 3, 4, 6, 7, 9, 10, 12, 13, 15);
    }

    private static VoxelShape orWallRows(VoxelShape shape, TrenchConnection c, int... yPairs) {
        for (int i = 0; i + 1 < yPairs.length; i += 2) {
            shape = orBands(shape, c, yPairs[i], yPairs[i + 1]);
        }
        return shape;
    }

    private static VoxelShape orBands(VoxelShape shape, TrenchConnection c, int yMin, int yMax) {
        if (c.wallNorth()) shape = Shapes.or(shape, Block.box(0, yMin, 0, 16, yMax, 1));
        if (c.wallSouth()) shape = Shapes.or(shape, Block.box(0, yMin, 15, 16, yMax, 16));
        if (c.wallWest()) shape = Shapes.or(shape, Block.box(0, yMin, 0, 1, yMax, 16));
        if (c.wallEast()) shape = Shapes.or(shape, Block.box(15, yMin, 0, 16, yMax, 16));
        return shape;
    }
}
