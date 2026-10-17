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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Two-tall earthworks trench. Absolute NSEW connection via {@link TrenchConnection}.
 * Connection shapes recompute only on player place / break / axe — not when neighbours
 * vanish to explosions or other non-player block updates.
 */
public class TrenchBlock extends Block {

    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<TrenchConnection> CONNECTION =
            EnumProperty.create("connection", TrenchConnection.class);
    /** Leaf camouflage on the upper half — two model variants, no BlockEntity. */
    public static final BooleanProperty NETTING = BooleanProperty.create("netting");

    /** Set around player destroy so {@link #onRemove} refreshes orthogonal neighbours. */
    private static final ThreadLocal<Boolean> PLAYER_EDIT = ThreadLocal.withInitial(() -> false);

    private static final VoxelShape[] LOWER = new VoxelShape[TrenchConnection.values().length];
    private static final VoxelShape[] UPPER = new VoxelShape[TrenchConnection.values().length];
    private static final VoxelShape[] UPPER_NETTED = new VoxelShape[TrenchConnection.values().length];

    /** Solid leaf-cover slab when netting is on (collision only — no forced crouch). */
    private static final VoxelShape NETTING_COVER = Block.box(0, 14, 0, 16, 16, 16);

    static {
        for (TrenchConnection c : TrenchConnection.values()) {
            LOWER[c.ordinal()] = buildLower(c);
            UPPER[c.ordinal()] = buildUpper(c);
            UPPER_NETTED[c.ordinal()] = Shapes.or(UPPER[c.ordinal()], NETTING_COVER);
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
                .setValue(CONNECTION, TrenchConnection.LONE)
                .setValue(NETTING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, CONNECTION, NETTING);
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
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) return LOWER[c.ordinal()];
        return state.getValue(NETTING) ? UPPER_NETTED[c.ordinal()] : UPPER[c.ordinal()];
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
        if (!level.isClientSide) {
            onPlayerTopologyEdit(level, pos);
        }
    }

    /**
     * Stick toggles leaf netting on both halves. Axe cut: end → mid, tcross / walled face →
     * plinth (player edit — neighbours refresh; explosions still leave shapes alone).
     */
    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);

        if (stack.is(Items.STICK)) {
            if (!level.isClientSide) {
                boolean next = !state.getValue(NETTING);
                applyNetting(level, pos, state, next);
                level.playSound(null, pos, SoundEvents.AZALEA_LEAVES_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!stack.is(ItemTags.AXES)) return InteractionResult.PASS;

        TrenchConnection next = state.getValue(CONNECTION).axeConvert(hit.getDirection());
        if (next == null) return InteractionResult.PASS;

        if (!level.isClientSide) {
            BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            BlockState lower = level.getBlockState(lowerPos);
            if (!(lower.getBlock() instanceof TrenchBlock)) return InteractionResult.PASS;

            applyConnection(level, lowerPos, lower, next);
            onPlayerTopologyEdit(level, lowerPos);
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

        // Vertical half-link only — never recompute CONNECTION from horizontal neighbour loss
        // (explosions / natural events must leave shapes stuck as the player last set them).
        if (direction.getAxis() == Direction.Axis.Y) {
            boolean towardMate = half == DoubleBlockHalf.LOWER ? direction == Direction.UP : direction == Direction.DOWN;
            if (towardMate) {
                if (neighbor.is(this) && neighbor.getValue(HALF) != half) {
                    return state
                            .setValue(CONNECTION, neighbor.getValue(CONNECTION))
                            .setValue(NETTING, neighbor.getValue(NETTING));
                }
                return Blocks.AIR.defaultBlockState();
            }
            if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, pos)) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        PLAYER_EDIT.set(true);
        if (!level.isClientSide && player.isCreative()) {
            preventCreativeDropFromBottomPart(level, pos, state, player);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        boolean playerEdit = Boolean.TRUE.equals(PLAYER_EDIT.get());
        try {
            if (!state.is(newState.getBlock()) && playerEdit && !level.isClientSide) {
                BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
                // Mate may still be present for a tick — force both cells absent for neighbour resolve.
                refreshOrthogonalNeighbors(level, lower, lower, lower.above());
                TrenchTracker.onTopologyChanged(level, lower);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        } finally {
            if (playerEdit) {
                PLAYER_EDIT.set(false);
            }
        }
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
        // LAND must be true or WalkNodeEvaluator marks every trench cell BLOCKED and
        // infantry freeze at the rim. WATER/AIR stay closed — this is a ditch, not a duct.
        return type == PathComputationType.LAND;
    }

    /** Lower floor is footing; upper wall/netting cell stays OPEN so the body fits above. */
    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? TrenchPathTypes.TRENCH : BlockPathTypes.OPEN;
    }

    private static void applyConnection(Level level, BlockPos lowerPos, BlockState lower, TrenchConnection connection) {
        BlockState newLower = lower.setValue(CONNECTION, connection);
        level.setBlock(lowerPos, newLower, 3);
        BlockPos upperPos = lowerPos.above();
        if (level.getBlockState(upperPos).getBlock() instanceof TrenchBlock) {
            level.setBlock(upperPos, newLower.setValue(HALF, DoubleBlockHalf.UPPER), 3);
        }
    }

    private static void applyNetting(Level level, BlockPos pos, BlockState state, boolean netting) {
        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        BlockState lower = level.getBlockState(lowerPos);
        if (!(lower.getBlock() instanceof TrenchBlock)) return;
        BlockState newLower = lower.setValue(NETTING, netting);
        level.setBlock(lowerPos, newLower, 3);
        BlockPos upperPos = lowerPos.above();
        if (level.getBlockState(upperPos).getBlock() instanceof TrenchBlock) {
            level.setBlock(upperPos, newLower.setValue(HALF, DoubleBlockHalf.UPPER), 3);
        }
    }

    /** Re-resolve orthogonal trench neighbours — player edits only. */
    private static void refreshOrthogonalNeighbors(Level level, BlockPos pos, BlockPos... treatAsEmpty) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos at = pos.relative(dir);
            BlockState cur = level.getBlockState(at);
            if (!(cur.getBlock() instanceof TrenchBlock)) continue;
            BlockPos lowerPos = cur.getValue(HALF) == DoubleBlockHalf.LOWER ? at : at.below();
            BlockState lower = level.getBlockState(lowerPos);
            if (!(lower.getBlock() instanceof TrenchBlock)) continue;
            BlockState updated = withConnections(lower, level, lowerPos, treatAsEmpty);
            if (updated != lower) {
                applyConnection(level, lowerPos, lower, updated.getValue(CONNECTION));
            }
        }
    }

    private static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos,
                                              BlockPos... treatAsEmpty) {
        boolean n = connectsAt(level, pos.north(), treatAsEmpty);
        boolean e = connectsAt(level, pos.east(), treatAsEmpty);
        boolean s = connectsAt(level, pos.south(), treatAsEmpty);
        boolean w = connectsAt(level, pos.west(), treatAsEmpty);
        return state.setValue(CONNECTION, TrenchConnection.fromNeighbors(n, e, s, w));
    }

    private static boolean connectsAt(BlockGetter level, BlockPos at, BlockPos... treatAsEmpty) {
        for (BlockPos empty : treatAsEmpty) {
            if (empty != null && empty.equals(at)) return false;
        }
        return connects(level.getBlockState(at));
    }

    private static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos) {
        return withConnections(state, level, pos, new BlockPos[0]);
    }

    /** Regular trench, manual {@code +}, and foxhole slabs all count as connected neighbours. */
    static boolean connects(BlockState neighbor) {
        Block block = neighbor.getBlock();
        return block instanceof TrenchBlock
                || block instanceof TrenchXCrossBlock
                || block instanceof FoxholeBlock;
    }

    /** Player placed/broke a related block at {@code pos} — re-resolve adjacent trenches. */
    static void onPlayerTopologyEdit(Level level, BlockPos pos, BlockPos... treatAsEmpty) {
        if (level.isClientSide) return;
        refreshOrthogonalNeighbors(level, pos, treatAsEmpty);
        TrenchTracker.onTopologyChanged(level, pos);
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
