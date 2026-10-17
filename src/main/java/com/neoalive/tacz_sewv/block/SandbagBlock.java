package com.neoalive.tacz_sewv.block;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.neoalive.tacz_sewv.entity.SandbagSeatEntity;

/**
 * Directional sandbag fighting position. Right-click mounts a living entity onto an
 * invisible seat ({@link #tryMount} is shared with AI).
 *
 * <p>Collision is a single AABB covering the whole berm (model root extents in the north pose),
 * rotated with {@code FACING} — one box instead of a per-brick union. The hollow is not
 * preserved in collision (performance); {@link PathComputationType#LAND} +
 * {@link TrenchPathTypes#TRENCH} still let infantry path in.
 */
public class SandbagBlock extends BaseEntityBlock {

    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    /** Model root extents (px): X −7…23, Y 0…10, Z −2…13. */
    private static final VoxelShape COLLISION_NORTH = Block.box(-7.0D, 0.0D, -2.0D, 23.0D, 10.0D, 13.0D);
    private static final VoxelShape COLLISION_EAST = rotateShape(COLLISION_NORTH, Direction.EAST);
    private static final VoxelShape COLLISION_SOUTH = rotateShape(COLLISION_NORTH, Direction.SOUTH);
    private static final VoxelShape COLLISION_WEST = rotateShape(COLLISION_NORTH, Direction.WEST);

    public SandbagBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.SAND)
                .strength(0.5f, 6.0f)
                .sound(SoundType.SAND)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.PASS;
        if (player.isPassenger()) return InteractionResult.PASS;
        return tryMount(server, pos, player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** Mount {@code rider} if the seat is free. Shared by player use and AI. */
    public static boolean tryMount(ServerLevel level, BlockPos pos, LivingEntity rider) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SandbagBlockEntity sandbag)) return false;
        SandbagSeatEntity seat = sandbag.ensureSeat(level);
        return seat.tryMount(rider);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SandbagBlockEntity sandbag) {
                sandbag.discardSeat(server);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return collisionFor(state.getValue(FACING));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext ctx) {
        return collisionFor(state.getValue(FACING));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  PathComputationType type) {
        // Same as TrenchBlock / FoxholeBlock — without LAND, WalkNodeEvaluator marks the cell
        // BLOCKED and infantry freeze at the rim.
        return type == PathComputationType.LAND;
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos,
                                           @Nullable Mob mob) {
        return TrenchPathTypes.TRENCH;
    }

    private static VoxelShape collisionFor(Direction facing) {
        return switch (facing) {
            case EAST -> COLLISION_EAST;
            case SOUTH -> COLLISION_SOUTH;
            case WEST -> COLLISION_WEST;
            default -> COLLISION_NORTH;
        };
    }

    /** Rotate a north-facing shape around Y to {@code facing} (90° steps). */
    private static VoxelShape rotateShape(VoxelShape north, Direction facing) {
        VoxelShape[] buffer = new VoxelShape[]{north, Shapes.empty()};
        int turns = switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        for (int i = 0; i < turns; i++) {
            buffer[0].forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                    buffer[1] = Shapes.or(buffer[1], Shapes.box(
                            1.0D - maxZ, minY, minX,
                            1.0D - minZ, maxY, maxX)));
            buffer[0] = buffer[1];
            buffer[1] = Shapes.empty();
        }
        return buffer[0];
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SandbagBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
