package com.neoalive.tacz_sewv.procedural.events;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

import com.neoalive.tacz_sewv.spawn.SpawnDeny;

/**
 * Picks where an SEM event (ours or SEM's own) sets up. SEM rolls one point in a ring and reads its
 * height through {@code Level.getHeight}, which answers the bottom of the world for an unloaded chunk
 * and can block on one that is still loading. Measured at 8 view and 12 simulation chunks on dry land,
 * a far roll passed SEM's spawn gate in a ticking chunk 2% of the time for {@code far_combat} and never
 * for this mod's wide events. This samples the ring instead, reads only loaded, entity ticking chunks,
 * and keeps the farthest point that passes the event's own ground test.
 */
public final class EventSpots {

    private static final int SAMPLES = 16;

    private EventSpots() {}

    public static EventBand band(ServerLevel level, int min, int max) {
        return band(level, min, max, 0);
    }

    /** As above for an event whose pieces reach {@code reach} blocks either side of its centre. */
    public static EventBand band(ServerLevel level, int min, int max, int reach) {
        PlayerList list = level.getServer().getPlayerList();
        return EventBand.clamp(min, max,
                EventBand.simulatedRadius(list.getViewDistance(), list.getSimulationDistance()), reach);
    }

    /**
     * A point in the band for SEM's biome weighting, which runs before {@code execute}. Never loads a
     * chunk: an unloaded column reads the player's own height.
     */
    public static BlockPos rough(ServerLevel level, ServerPlayer player, EventBand band, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double d = band.min() + random.nextDouble() * (band.max() - band.min());
        int x = player.getBlockX() + (int) (Math.cos(angle) * d);
        int z = player.getBlockZ() + (int) (Math.sin(angle) * d);
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        int y = chunk == null ? player.getBlockY() : surface(chunk, x, z);
        return new BlockPos(x, y, z);
    }

    /**
     * The farthest sampled point in a loaded, entity ticking chunk whose ground suits the event, or
     * null with {@link SpawnDeny} set: {@code UNLOADED} when no sample landed on simulated ground,
     * {@code SPOT} or {@code WATER} when some did and none had the right footing.
     */
    @Nullable
    public static BlockPos pick(ServerLevel level, ServerPlayer player, EventBand band, boolean water,
                                RandomSource random) {
        BlockPos best = null;
        double bestD = -1.0;
        boolean simulated = false;
        for (int i = 0; i < SAMPLES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double d = band.min() + random.nextDouble() * (band.max() - band.min());
            int x = player.getBlockX() + (int) (Math.cos(angle) * d);
            int z = player.getBlockZ() + (int) (Math.sin(angle) * d);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            BlockPos pos = new BlockPos(x, surface(chunk, x, z), z);
            if (!level.isPositionEntityTicking(pos)) continue;
            simulated = true;
            boolean fits = water
                    ? level.getFluidState(pos.below()).is(FluidTags.WATER)
                    : SpawnHelper.isValidSpawn(level, pos);
            if (fits && d > bestD) {
                best = pos;
                bestD = d;
            }
        }
        if (best == null) {
            SpawnDeny.set(!simulated ? SpawnDeny.Why.UNLOADED : water ? SpawnDeny.Why.WATER : SpawnDeny.Why.SPOT);
        }
        return best;
    }

    /** Same answer as {@code Level.getHeight} for a loaded chunk, without the chunk lookup. */
    private static int surface(LevelChunk chunk, int x, int z) {
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }
}
