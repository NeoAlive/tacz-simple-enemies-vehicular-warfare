package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Water queries shared by the ship pathfinder, sensor and drive goal: how deep a column is, and
 * where the nearest navigable water to an arbitrary point is.
 */
public final class WaterSupport {

    private WaterSupport() {}

    /** Depth a ship can float in at all. */
    public static final int MIN_NAVIGABLE_DEPTH = 1;
    /** How far from a land destination to look for water to sit in instead. */
    private static final int PROJECT_RADIUS = 24;

    /** Water blocks at or below (x,y,z), counted downward and capped at {@code max}. */
    public static int depthAt(BlockGetter level, int x, int y, int z, int max) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int depth = 0;
        for (int dy = 0; dy < max; dy++) {
            if (!level.getFluidState(pos.set(x, y - dy, z)).is(FluidTags.WATER)) break;
            depth++;
        }
        return depth;
    }

    /** Surface Y of the water column at (x,z), or null when there is none. */
    @Nullable
    public static Integer surfaceY(Level level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int dy = 0; dy <= 2; dy++) {
            if (level.getFluidState(new BlockPos(x, y - dy, z)).is(FluidTags.WATER)) return y - dy;
        }
        return null;
    }

    /** True when a hull can float at this column. */
    public static boolean navigable(Level level, int x, int z) {
        Integer y = surfaceY(level, x, z);
        return y != null && depthAt(level, x, y, z, MIN_NAVIGABLE_DEPTH) >= MIN_NAVIGABLE_DEPTH;
    }

    /**
     * Cheap sensor-grade check: water at or just under {@code y}, where {@code y} is the hull's own
     * level. Two block reads, no heightmap — {@link #navigable} costs five and the whisker fan runs
     * this hundreds of times a tick.
     */
    public static boolean floatableAt(BlockGetter level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)
                || level.getFluidState(pos.set(x, y - 1, z)).is(FluidTags.WATER);
    }

    /**
     * The navigable water nearest {@code target}, preferring depth then closeness. Returns
     * {@code target} unchanged when it is already navigable, and null when nothing is in range —
     * this is what lets a ship take a FOLLOW/PATROL order aimed at dry land and hold the water off
     * that point instead of driving at the beach.
     */
    @Nullable
    public static BlockPos projectToWater(Level level, BlockPos target) {
        if (navigable(level, target.getX(), target.getZ())) return target;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 1; r <= PROJECT_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = target.getX() + dx, z = target.getZ() + dz;
                    Integer y = surfaceY(level, x, z);
                    if (y == null) continue;
                    int depth = depthAt(level, x, y, z, 3);
                    if (depth < MIN_NAVIGABLE_DEPTH) continue;
                    // Closest first, with a discount for water actually worth sitting in.
                    double score = (double) dx * dx + (double) dz * dz - depth * 4.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
            if (best != null) return best; // nearest ring wins — don't widen once something is found
        }
        return null;
    }

    /** A random navigable point within {@code radius} of {@code anchor}, or null. */
    @Nullable
    public static BlockPos pickWaterWaypoint(Level level, BlockPos anchor, int radius, RandomSource random) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = radius * (0.3 + 0.7 * random.nextDouble());
            int x = anchor.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * dist);
            if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) continue;
            Integer y = surfaceY(level, x, z);
            if (y == null) continue;
            if (depthAt(level, x, y, z, 2) < 2) continue; // idle drifting stays off the shallows
            return new BlockPos(x, y, z);
        }
        return null;
    }
}
