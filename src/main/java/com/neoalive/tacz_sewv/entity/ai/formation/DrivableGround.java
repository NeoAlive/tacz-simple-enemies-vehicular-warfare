package com.neoalive.tacz_sewv.entity.ai.formation;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import com.neoalive.tacz_sewv.compat.EnhancedFallingTreesCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Is a column somewhere a ground hull can stand: loaded, dry, and not the top of a tree. SERVER ONLY.
 *
 * <p>The NO_LEAVES heightmap every ground snap reads still counts logs, so a trunk top reads as ground. A hull
 * put or sent there climbs the canopy and ends up perched, with every way down reading as a wall. Logs only
 * count as drivable while tree felling is active, because the hull then knocks the tree down; leaves never do.
 * Reads loaded chunks only, never forces a load.
 */
public final class DrivableGround {

    private DrivableGround() {}

    /** Column test for the pure searches below. */
    @FunctionalInterface
    public interface Column {
        boolean drivable(int x, int z);
    }

    public static boolean fellingActive() {
        try {
            return EnhancedFallingTreesCompat.available() && SewvConfig.VEHICLE_TREE_FELLING_ENABLED.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A block a ground hull must not end up resting on. */
    public static boolean treeBlock(BlockState state, boolean felling) {
        return state.is(BlockTags.LEAVES) || (!felling && state.is(BlockTags.LOGS));
    }

    public static boolean drivable(Level level, int x, int z) {
        return drivable(level, x, z, fellingActive());
    }

    static boolean drivable(Level level, int x, int z, boolean felling) {
        LevelChunk chunk = level.getChunkSource() instanceof net.minecraft.server.level.ServerChunkCache cache
                ? cache.getChunkNow(x >> 4, z >> 4) : null;
        if (chunk == null) return false;
        // getHeight is the first free Y above the top block. NO_LEAVES, as every ground snap reads it: a canopy
        // over open ground is fine, the surface under it is what the hull stands on. It still counts fluids.
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) - 1;
        if (top <= level.getMinBuildHeight()) return false;
        BlockPos pos = new BlockPos(x, top, z);
        BlockState state = chunk.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return false;
        return !treeBlock(state, felling);
    }

    public static Column column(Level level) {
        boolean felling = fellingActive();
        return (x, z) -> drivable(level, x, z, felling);
    }

    /** Nearest drivable column to (x, z) within {@code radius}, ring by ring; the nearest ring wins. */
    @Nullable
    public static int[] nearest(Column col, int x, int z, int radius) {
        if (col.drivable(x, z)) return new int[] {x, z};
        for (int r = 1; r <= radius; r++) {
            int[] best = null;
            int bestD = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int d = dx * dx + dz * dz;
                    if (d >= bestD || !col.drivable(x + dx, z + dz)) continue;
                    bestD = d;
                    best = new int[] {x + dx, z + dz};
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * First drivable point walking from (fromX, fromZ) toward (toX, toZ) in {@code step} increments, the target
     * itself included. Keeps a point on its own side of the reference, unlike a ring search. Null if none.
     */
    @Nullable
    public static double[] pullTowards(Column col, double fromX, double fromZ, double toX, double toZ, double step) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = len < 1.0E-6 ? 0 : (int) Math.ceil(len / step);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : Math.min(1.0, (double) i / steps);
            double x = fromX + dx * t;
            double z = fromZ + dz * t;
            if (col.drivable(Mth.floor(x), Mth.floor(z))) return new double[] {x, z};
        }
        return null;
    }

    /** Hull resting on a tree: the centre and at least two footprint corners are over log or leaf tops. */
    public static boolean perched(VehicleEntity hull) {
        Level level = hull.level();
        boolean felling = fellingActive();
        double half = Math.max(0.5, hull.getBbWidth() * 0.5 - 0.5);
        int y = Mth.floor(hull.getY() - 0.2);
        if (!onTree(level, hull.getX(), y, hull.getZ(), felling)) return false;
        int corners = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                if (onTree(level, hull.getX() + sx * half, y, hull.getZ() + sz * half, felling)) corners++;
            }
        }
        return corners >= 2;
    }

    private static boolean onTree(Level level, double x, int y, double z, boolean felling) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) state = level.getBlockState(pos.below());
        return treeBlock(state, felling);
    }
}
