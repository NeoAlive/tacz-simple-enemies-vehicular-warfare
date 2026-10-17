package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.util.ChunkTicket;

/** Shared low-level controls and terrain references for helicopter and plane pilots. */
public final class AirframeSupport {

    private static final double TERRAIN_SAMPLE_STEP = 8.0;
    /** Perpendicular offset (blocks) sampled beside the centerline at each step. */
    private static final double LATERAL_BAND = 8.0;
    private static final int CACHE_TTL_TICKS = 10;
    /** Cache size cap. Well past any plausible number of airframes ticking in one level. */
    private static final int CACHE_MAX_ENTRIES = 64;

    /**
     * Highest-ground cache, keyed by hull. This used to be a single set of statics, which meant two
     * aircraft in the air raced for it and each could be served the other's answer for up to
     * {@link #CACHE_TTL_TICKS} — a cruise altitude computed for somebody else's leg. Rare and
     * usually harmless, but the failure it produces (an aircraft holding the wrong height over a
     * ridge) is exactly the one that is impossible to reproduce from a report.
     *
     * <p>Entries are per-thread because the integrated server and the client tick separately, and
     * bounded by insertion order so a level full of transient hulls cannot grow it without limit.
     */
    private static final ThreadLocal<LinkedHashMap<Integer, GroundSample>> GROUND_CACHE =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, GroundSample> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private static final class GroundSample {
        private int destX;
        private int destZ;
        private int lookaheadBucket;
        private long tick = Long.MIN_VALUE;
        private int highest;

        boolean matches(int x, int z, int bucket, long now, int ttl) {
            return x == this.destX && z == this.destZ && bucket == this.lookaheadBucket
                    && now - this.tick < ttl;
        }

        void store(int x, int z, int bucket, long now, int value) {
            this.destX = x;
            this.destZ = z;
            this.lookaheadBucket = bucket;
            this.tick = now;
            this.highest = value;
        }
    }

    private AirframeSupport() {}

    public static void releaseInputs(VehicleEntity vehicle) {
        vehicle.setForwardInputDown(false);
        vehicle.setBackInputDown(false);
        vehicle.setLeftInputDown(false);
        vehicle.setRightInputDown(false);
        vehicle.setDownInputDown(false);
        vehicle.setMouseMoveSpeedX(0.0F);
        vehicle.setMouseMoveSpeedY(0.0F);
    }

    public static void clearDecoy(VehicleEntity vehicle) {
        vehicle.setDecoyInputDown(false);
    }

    public static void updateDecoy(VehicleEntity vehicle, AbstractUnit unit, DecoyEpisode episode,
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

    public static void updateChunkLoading(ChunkTicket ticket, VehicleEntity vehicle, boolean enabled) {
        if (enabled) {
            ticket.follow(vehicle);
        } else {
            ticket.release(vehicle);
        }
    }

    public static int surfaceBelow(VehicleEntity vehicle) {
        return vehicle.level().getHeight(
                Heightmap.Types.WORLD_SURFACE, vehicle.getBlockX(), vehicle.getBlockZ());
    }

    public static double cruiseAltitudeHere(VehicleEntity vehicle, double flightAltitude) {
        return surfaceBelow(vehicle) + flightAltitude;
    }

    public static double cruiseAltitudeToward(VehicleEntity vehicle, double toX, double toZ,
                                       double flightAltitude, double lookahead) {
        return highestGroundToward(vehicle, toX, toZ, lookahead) + flightAltitude;
    }

    public static double cruiseAltitudeToward(VehicleEntity vehicle, double toX, double toZ,
                                       double flightAltitude, double lookahead, int cacheTtl) {
        return highestGroundToward(vehicle, toX, toZ, lookahead, cacheTtl) + flightAltitude;
    }

    /**
     * Highest {@link Heightmap.Types#WORLD_SURFACE} along the leg to {@code (toX, toZ)}, sampling
     * the centerline and a ±{@link #LATERAL_BAND} band so a ridge beside the course still raises
     * cruise altitude. Cached ~{@link #CACHE_TTL_TICKS} game ticks per hull/dest.
     *
     * <p>Unloaded columns are skipped — never {@code getHeight} into a chunk the ticket does not
     * hold, or a far cruise streams worldgen along its look-ahead.
     */
    public static int highestGroundToward(VehicleEntity vehicle, double toX, double toZ, double lookahead) {
        return highestGroundToward(vehicle, toX, toZ, lookahead, CACHE_TTL_TICKS);
    }

    public static int highestGroundToward(VehicleEntity vehicle, double toX, double toZ,
                                          double lookahead, int cacheTtl) {
        int destX = Mth.floor(toX);
        int destZ = Mth.floor(toZ);
        int lookaheadBucket = Mth.floor(lookahead);
        long now = vehicle.level().getGameTime();
        GroundSample sample = GROUND_CACHE.get()
                .computeIfAbsent(vehicle.getId(), id -> new GroundSample());
        if (sample.matches(destX, destZ, lookaheadBucket, now, cacheTtl)) {
            return sample.highest;
        }

        int highest = sampleHighestGround(vehicle, toX, toZ, lookahead);
        sample.store(destX, destZ, lookaheadBucket, now, highest);
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
            highest = Math.max(highest, surfaceAtLoadedOr(level, cx, cz, highest));
            highest = Math.max(highest, surfaceAtLoadedOr(level,
                    cx + px * LATERAL_BAND, cz + pz * LATERAL_BAND, highest));
            highest = Math.max(highest, surfaceAtLoadedOr(level,
                    cx - px * LATERAL_BAND, cz - pz * LATERAL_BAND, highest));
        }
        return highest;
    }

    /**
     * Heightmap read that never sync-loads. Returns {@link Integer#MIN_VALUE} when the column's
     * chunk is unloaded — callers treat that as "no sample" (clear for corridor routing).
     */
    public static int surfaceAtLoaded(Level level, int blockX, int blockZ) {
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) return Integer.MIN_VALUE;
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
    }

    private static int surfaceAtLoadedOr(Level level, double x, double z, int fallback) {
        int h = surfaceAtLoaded(level, Mth.floor(x), Mth.floor(z));
        return h == Integer.MIN_VALUE ? fallback : h;
    }
}
