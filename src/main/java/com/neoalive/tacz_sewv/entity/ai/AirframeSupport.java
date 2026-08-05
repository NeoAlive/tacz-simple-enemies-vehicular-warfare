package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.ChunkTicket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/** Shared low-level controls and terrain references for helicopter and plane pilots. */
final class AirframeSupport {

    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    /** Perpendicular offset (blocks) sampled beside the centerline at each step. */
    private static final double LATERAL_BAND = 8.0;
    private static final int CACHE_TTL_TICKS = 10;

    // Per-hull highest-ground cache — keyed on dest cell + lookahead bucket, TTL in game ticks.
    private static int cacheVehicleId = Integer.MIN_VALUE;
    private static int cacheDestX;
    private static int cacheDestZ;
    private static int cacheLookaheadBucket;
    private static long cacheTick = Long.MIN_VALUE;
    private static int cacheHighest;

    private AirframeSupport() {}

    static void releaseInputs(VehicleEntity vehicle) {
        vehicle.setForwardInputDown(false);
        vehicle.setBackInputDown(false);
        vehicle.setLeftInputDown(false);
        vehicle.setRightInputDown(false);
        vehicle.setDownInputDown(false);
        vehicle.setMouseMoveSpeedX(0.0F);
        vehicle.setMouseMoveSpeedY(0.0F);
    }

    static void clearDecoy(VehicleEntity vehicle) {
        vehicle.setDecoyInputDown(false);
    }

    static void updateDecoy(VehicleEntity vehicle, AbstractUnit unit, DecoyEpisode episode,
                            float healthFraction, float chance) {
        float max = vehicle.getMaxHealth();
        if (!(max > 0.0F) || vehicle.getHealth() > max * healthFraction || vehicle.onGround()) {
            clearDecoy(vehicle);
            return;
        }
        if (episode.roll(unit.level().getGameTime(), unit.getRandom(), chance) && vehicle.hasDecoy()) {
            vehicle.setDecoyInputDown(true);
        }
    }

    static void updateChunkLoading(ChunkTicket ticket, VehicleEntity vehicle, boolean enabled) {
        if (enabled) {
            ticket.follow(vehicle);
        } else {
            ticket.release(vehicle);
        }
    }

    static int surfaceBelow(VehicleEntity vehicle) {
        return vehicle.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, vehicle.getBlockX(), vehicle.getBlockZ());
    }

    static double cruiseAltitudeHere(VehicleEntity vehicle, double flightAltitude) {
        return surfaceBelow(vehicle) + flightAltitude;
    }

    static double cruiseAltitudeToward(VehicleEntity vehicle, double toX, double toZ,
                                       double flightAltitude, double lookahead) {
        return highestGroundToward(vehicle, toX, toZ, lookahead) + flightAltitude;
    }

    /**
     * Highest {@link Heightmap.Types#WORLD_SURFACE} along the leg to {@code (toX, toZ)}, sampling
     * the centerline and a ±{@link #LATERAL_BAND} band so a ridge beside the course still raises
     * cruise altitude. Cached ~{@link #CACHE_TTL_TICKS} game ticks per hull/dest.
     */
    static int highestGroundToward(VehicleEntity vehicle, double toX, double toZ, double lookahead) {
        int destX = Mth.floor(toX);
        int destZ = Mth.floor(toZ);
        int lookaheadBucket = Mth.floor(lookahead);
        long now = vehicle.level().getGameTime();
        if (vehicle.getId() == cacheVehicleId
                && destX == cacheDestX
                && destZ == cacheDestZ
                && lookaheadBucket == cacheLookaheadBucket
                && now - cacheTick < CACHE_TTL_TICKS) {
            return cacheHighest;
        }

        int highest = sampleHighestGround(vehicle, toX, toZ, lookahead);
        cacheVehicleId = vehicle.getId();
        cacheDestX = destX;
        cacheDestZ = destZ;
        cacheLookaheadBucket = lookaheadBucket;
        cacheTick = now;
        cacheHighest = highest;
        return highest;
    }

    private static int sampleHighestGround(VehicleEntity vehicle, double toX, double toZ,
                                           double lookahead) {
        int highest = surfaceBelow(vehicle);
        double dx = toX - vehicle.getX();
        double dz = toZ - vehicle.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 1.0E-4) return highest;

        Level level = vehicle.level();
        double nx = dx / distance;
        double nz = dz / distance;
        // Perpendicular to the bearing in XZ.
        double px = -nz;
        double pz = nx;
        double reach = Math.min(distance, lookahead);
        double ox = vehicle.getX();
        double oz = vehicle.getZ();
        for (double d = TERRAIN_SAMPLE_STEP; d <= reach; d += TERRAIN_SAMPLE_STEP) {
            double cx = ox + nx * d;
            double cz = oz + nz * d;
            highest = Math.max(highest, surfaceAt(level, cx, cz));
            highest = Math.max(highest, surfaceAt(level, cx + px * LATERAL_BAND, cz + pz * LATERAL_BAND));
            highest = Math.max(highest, surfaceAt(level, cx - px * LATERAL_BAND, cz - pz * LATERAL_BAND));
        }
        return highest;
    }

    private static int surfaceAt(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(x), Mth.floor(z));
    }
}
