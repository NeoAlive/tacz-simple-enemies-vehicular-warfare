package com.neoalive.tacz_sewv.fob;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.init.ModBlocks;

/**
 * Re-discovers stockpile/parking blocks inside the master AABB and scans their footprints for
 * obstructions — the same shape of check as {@link AirportClearance#check}, but for FOB pads.
 */
public final class FobClearance {

    public record Result(boolean valid, String reason, @Nullable BlockPos blocker) {

        public static Result pass() {
            return new Result(true, "", null);
        }

        public static Result fail(String reason) {
            return new Result(false, reason, null);
        }

        public static Result obstructed(BlockPos blocker, String reason) {
            return new Result(false, reason, blocker);
        }
    }

    private FobClearance() {}

    /**
     * Scan the master AABB for sub-blocks and refresh {@link FobInstance} links. Closest block to
     * the command post wins when several of the same type exist.
     */
    public static void rescanSubBlocks(FobInstance fob, Level level) {
        AABB box = fob.cachedMasterAabb;
        if (box == null) {
            FobSupport.refreshCachedAabbs(fob, level);
            box = fob.cachedMasterAabb;
        }
        if (box == null) return;

        BlockPos cmd = fob.commandPos;
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);
        int minY = Math.max(level.getMinBuildHeight(), cmd.getY() - 8);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, cmd.getY() + 8);

        BlockPos stockpile = null;
        BlockPos parking = null;
        double bestStock = Double.MAX_VALUE;
        double bestPark = Double.MAX_VALUE;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModBlocks.STOCKPILE_AMMO.get())) {
                        double d = pos.distSqr(cmd);
                        if (d < bestStock) {
                            bestStock = d;
                            stockpile = pos.immutable();
                        }
                    } else if (state.is(ModBlocks.PARKING_FIELD.get())) {
                        double d = pos.distSqr(cmd);
                        if (d < bestPark) {
                            bestPark = d;
                            parking = pos.immutable();
                        }
                    }
                }
            }
        }
        fob.stockpilePos = stockpile;
        fob.parkingPos = parking;
        FobSupport.refreshCachedAabbs(fob, level);
    }

    /** Layout + footprint scan after {@link #rescanSubBlocks}. */
    public static Result check(FobInstance fob, ServerLevel level) {
        rescanSubBlocks(fob, level);

        if (fob.stockpilePos == null || fob.parkingPos == null) {
            return Result.fail("Missing stockpile or parking field");
        }
        if (fob.cachedMasterAabb != null) {
            if (!containsXZ(fob.cachedMasterAabb, fob.stockpilePos)) {
                return Result.fail("Stockpile outside FOB area");
            }
            if (!containsXZ(fob.cachedMasterAabb, fob.parkingPos)) {
                return Result.fail("Parking field outside FOB area");
            }
        }

        for (FobInstance other : FobManager.get(level).all()) {
            if (other == fob || other.cachedMasterAabb == null || fob.cachedMasterAabb == null) continue;
            if (other.cachedMasterAabb.intersects(fob.cachedMasterAabb)) {
                return Result.fail("Overlaps another FOB");
            }
        }

        BlockPos stockBlocker = padClear(level, fob.stockpilePos, FobSupport.stockpileSize());
        if (stockBlocker != null) {
            return Result.obstructed(stockBlocker, "Stockpile pad obstructed");
        }
        BlockPos parkBlocker = padClear(level, fob.parkingPos, FobSupport.parkingSize());
        if (parkBlocker != null) {
            return Result.obstructed(parkBlocker, "Parking pad obstructed");
        }
        return Result.pass();
    }

    @Nullable
    private static BlockPos padClear(Level level, BlockPos center, int size) {
        AABB box = FobSupport.horizontalAabb(center, size, level);
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);
        int airY = center.getY() + 1;
        return AirportClearance.footprintClear(level,
                new BlockPos(minX, airY, minZ),
                new BlockPos(maxX, airY, maxZ),
                airY);
    }

    private static boolean containsXZ(AABB box, BlockPos pos) {
        return box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
