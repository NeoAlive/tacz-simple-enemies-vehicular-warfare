package com.neoalive.tacz_sewv.airport;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.goal.DrivePlaneGoal;

/**
 * Pure strip geometry + the two world scans a strip has to pass: an XZ footprint air check, and
 * the glideslope corridor leading in to it. Orientation is deterministic from the corners;
 * nothing here reads vehicle kinematics.
 *
 * <p>A runway has two directions, and checking both is what makes the corridor test useful rather
 * than merely strict — a strip against a hillside is landable from the open end, and the end that
 * works is decided once here instead of being rediscovered (or not) on every approach.
 *
 * <p>A cleared strip carries its {@link RunwaySlots} segmentation with it, so the touchdown point
 * and the parking slots are one calculation rather than two that can disagree.
 */
public final class AirportClearance {

    public enum Status {
        NONE,
        OK,
        ASPECT,
        TOO_SHORT,
        TOO_LARGE,
        OBSTRUCTED,
        NOT_POOLED
    }

    /**
     * Everything the check is judged against, in one argument. Grouped because the segmentation
     * added three more numbers to what was already a long parameter list, and because the headless
     * self-check has to pass literals where the game passes config.
     */
    public record Rules(double minAspect, int minLength, int maxArea,
                        double slotFactor, double bufferFactor, double extraFactor) {

        /**
         * Shape gates from the server's config, segmentation from the runway itself: the first
         * are a server's rules about what may be called a runway, the second are the owner's
         * choice about how to use one.
         */
        public static Rules forRunway(double slotFactor, double bufferFactor, double extraFactor) {
            return new Rules(
                    SewvConfig.AIRPORT_MIN_ASPECT_RATIO.get(),
                    SewvConfig.AIRPORT_MIN_LENGTH_BLOCKS.get(),
                    SewvConfig.AIRPORT_MAX_AREA_BLOCKS.get(),
                    slotFactor, bufferFactor, extraFactor);
        }
    }

    public record Result(
            Status status,
            @Nullable BlockPos blocker,
            @Nullable BlockPos touchdown,
            float headingDeg,
            int length,
            int width,
            BlockPos threshold,
            @Nullable RunwaySlots slots) {

        public static Result fail(Status status, int length, int width) {
            return new Result(status, null, null, 0.0F, length, width, BlockPos.ZERO, null);
        }

        public static Result obstructed(BlockPos blocker, int length, int width) {
            return new Result(Status.OBSTRUCTED, blocker, null, 0.0F, length, width,
                    BlockPos.ZERO, null);
        }
    }

    private AirportClearance() {}

    /** Geometry for the default direction (low-XZ end first). */
    public static Result evaluate(BlockPos corner1, BlockPos corner2, int runwayBlockY,
                                  Rules rules) {
        return evaluate(corner1, corner2, runwayBlockY, rules, false);
    }

    /**
     * Geometry only — no world reads. Used by Check Clearance before the world scans and by the
     * headless self-check.
     *
     * @param fromHighEnd land and launch the other way down the same strip
     */
    public static Result evaluate(BlockPos corner1, BlockPos corner2, int runwayBlockY,
                                  Rules rules, boolean fromHighEnd) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int length = Math.max(sizeX, sizeZ);
        int width = Math.min(sizeX, sizeZ);
        long area = (long) sizeX * (long) sizeZ;

        if (area > rules.maxArea()) {
            return Result.fail(Status.TOO_LARGE, length, width);
        }
        if (width <= 0 || (double) length / (double) width < rules.minAspect()) {
            return Result.fail(Status.ASPECT, length, width);
        }
        if (length < rules.minLength()) {
            return Result.fail(Status.TOO_SHORT, length, width);
        }

        boolean longIsX = sizeX >= sizeZ;
        int sign = fromHighEnd ? -1 : 1;
        double dirX = longIsX ? sign : 0.0;
        double dirZ = longIsX ? 0.0 : sign;
        // atan2(dirX, dirZ) — same convention as DrivePlaneGoal.approachAxis.
        float headingDeg = (float) Math.toDegrees(Math.atan2(dirX, dirZ));

        int padY = runwayBlockY + 1;
        BlockPos threshold;
        if (longIsX) {
            int end = fromHighEnd ? maxX : minX;
            threshold = new BlockPos(end, padY, (minZ + maxZ) / 2);
        } else {
            int end = fromHighEnd ? maxZ : minZ;
            threshold = new BlockPos((minX + maxX) / 2, padY, end);
        }

        RunwaySlots slots = RunwaySlots.of(threshold, headingDeg, length, width,
                rules.slotFactor(), rules.bufferFactor(), rules.extraFactor());
        return new Result(Status.OK, null, slots.touchdown(), headingDeg, length, width,
                threshold, slots);
    }

    /**
     * Geometry gates, then the footprint, then which way round to fly it.
     *
     * <p>The approach corridor <b>picks a direction and never rejects a strip</b>. It cannot be a
     * gate: {@code WORLD_SURFACE} counts leaves, so a runway cut through a forest reads as walled
     * in at both ends, and failing it there un-clears an airport that is otherwise perfectly good —
     * which then silently drops land orders back onto whatever block the player was looking at.
     * The aircraft is placed onto the glideslope by the alignment line anyway, so terrain off the
     * end is a preference between two ends rather than a veto.
     */
    public static Result check(Level level, BlockPos runwayBlock, BlockPos corner1,
                               BlockPos corner2, Rules rules) {
        return check(level, runwayBlock, corner1, corner2, rules, false);
    }

    /**
     * As above, with the owner's own preference for which end the strip is flown (and parked, and
     * numbered — {@link RunwaySlots} always starts slot 0 at the threshold) from.
     *
     * <p>The preference still yields to an obstructed approach on that end — the corridor test
     * above this one is a direction-picker, not a veto, and that has to stay true even when the
     * direction was chosen on purpose: a strip the owner set up facing a hillside is still landable
     * from the open end, and silently un-clearing it because their pick was the blocked one would
     * be a worse outcome than quietly flying it the other way.
     */
    public static Result check(Level level, BlockPos runwayBlock, BlockPos corner1,
                               BlockPos corner2, Rules rules, boolean preferHighEnd) {
        Result geo = evaluate(corner1, corner2, runwayBlock.getY(), rules, preferHighEnd);
        if (geo.status() != Status.OK) return geo;
        BlockPos blocker = footprintClear(level, corner1, corner2, runwayBlock.getY() + 1);
        if (blocker != null) {
            return Result.obstructed(blocker, geo.length(), geo.width());
        }
        if (approachClear(level, geo)) return geo;

        Result reversed = evaluate(corner1, corner2, runwayBlock.getY(), rules, !preferHighEnd);
        return approachClear(level, reversed) ? reversed : geo;
    }

    /**
     * Does the glideslope the landing AI actually flies clear the ground the whole way in to this
     * touchdown point? Same rule and same numbers as {@code DrivePlaneGoal.approachCorridorClear};
     * the constants are read from there so a strip that passes here is one the aircraft can fly.
     */
    public static boolean approachClear(Level level, Result result) {
        BlockPos touchdown = result.touchdown();
        if (touchdown == null) return false;
        double rad = Math.toRadians(result.headingDeg());
        double axisX = Math.sin(rad);
        double axisZ = Math.cos(rad);
        double padX = touchdown.getX() + 0.5;
        double padZ = touchdown.getZ() + 0.5;
        int padY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(padX), Mth.floor(padZ));
        for (double d = DrivePlaneGoal.APPROACH_SAMPLE_STEP;
                d <= DrivePlaneGoal.FINAL_LEG_LENGTH;
                d += DrivePlaneGoal.APPROACH_SAMPLE_STEP) {
            int x = Mth.floor(padX - axisX * d);
            int z = Mth.floor(padZ - axisZ * d);
            int surf = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            double allowed = padY + Math.max(2.0,
                    d * DrivePlaneGoal.LAND_GLIDE_RATIO - DrivePlaneGoal.APPROACH_CLEARANCE);
            if (surf > allowed) return false;
        }
        return true;
    }

    /**
     * Returns the first non-air column at {@code airY}, or null if the footprint is clear.
     * Strictly {@code isAir()} for now — solid-only (grass/snow) is a later one-liner.
     */
    @Nullable
    public static BlockPos footprintClear(Level level, BlockPos corner1, BlockPos corner2, int airY) {
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos pos = new BlockPos(x, airY, z);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) return pos;
            }
        }
        return null;
    }

    /** Display-only id: last 6 hex chars of the runway block's packed pos. */
    public static String airportId(BlockPos runwayPos) {
        String hex = Long.toHexString(runwayPos.asLong()).toUpperCase();
        return hex.length() <= 6 ? hex : hex.substring(hex.length() - 6);
    }
}
