package com.neoalive.tacz_sewv.airport;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.init.ModBlocks;

/**
 * Helipad geometry and the one world scan it has to pass: all nine footprint columns clear for
 * {@link #HEIGHT} blocks above the pad. The pad origin is the centre of the 3x3, so the footprint
 * runs from the east-north corner (x+1, z-1) to the south-west corner (x-1, z+1).
 */
public final class HelipadClearance {

    public enum Status {
        NONE,
        OK,
        OBSTRUCTED
    }

    public record Result(Status status, @Nullable BlockPos blocker) {}

    /** Blocks of open air above the pad that a helicopter needs to come and go. */
    public static final int HEIGHT = 50;

    private HelipadClearance() {}

    /** The volume the pad reserves: the 3x3 footprint, from the pad's own layer up {@link #HEIGHT}. */
    public static AABB volume(BlockPos pad) {
        return new AABB(pad.getX() - 1, pad.getY(), pad.getZ() - 1,
                pad.getX() + 2, pad.getY() + 1 + HEIGHT, pad.getZ() + 2);
    }

    /** The 3x3 footprint at pad level only (snap and occupancy tests). */
    public static AABB footprint(BlockPos pad) {
        return new AABB(pad.getX() - 1, pad.getY(), pad.getZ() - 1,
                pad.getX() + 2, pad.getY() + 1, pad.getZ() + 2);
    }

    public static Result check(Level level, BlockPos pad) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // The pad's own layer must be this pad's parts; everything above it must be open.
                p.set(pad.getX() + dx, pad.getY(), pad.getZ() + dz);
                if (!level.getBlockState(p).is(ModBlocks.HELIPAD.get())) {
                    return new Result(Status.OBSTRUCTED, p.immutable());
                }
                for (int dy = 1; dy <= HEIGHT; dy++) {
                    p.set(pad.getX() + dx, pad.getY() + dy, pad.getZ() + dz);
                    if (p.getY() >= level.getMaxBuildHeight()) break;
                    if (!isOpen(level, p)) return new Result(Status.OBSTRUCTED, p.immutable());
                }
            }
        }
        return new Result(Status.OK, null);
    }

    /** Air, or something with no collision and no fluid (grass, snow layer): nothing that blocks a rotor. */
    public static boolean isOpen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir()
                || (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty());
    }

    /** True when a cell can take a pad part: replaceable, and no fluid. */
    public static boolean isPlaceable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /**
     * Placement gate: the pad layer replaceable and the two layers above it open, over the whole
     * 3x3. Returns the first cell that is not, or null when there is room.
     */
    @Nullable
    public static BlockPos placementBlocker(Level level, BlockPos pad) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos ground = pad.offset(dx, 0, dz);
                boolean centre = dx == 0 && dz == 0;
                // The centre is the cell the player is placing into, which the item already vetted.
                if (!centre && !isPlaceable(level, ground)) return ground;
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos above = ground.above(dy);
                    if (above.getY() >= level.getMaxBuildHeight() || !isOpen(level, above)) return above;
                }
            }
        }
        return null;
    }
}
