package com.neoalive.tacz_sewv.procedural.marks;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;

import com.neoalive.tacz_sewv.zone.SpawnZones;

/**
 * Where a mark goes, judged without loading a chunk, and where its event actually sets up once the
 * chunk is real.
 *
 * <p>Election reads the world generator's own height noise ({@code getBaseHeight}, what vanilla
 * uses to place structures) on a grid over the event's footprint, plus the biome from the biome
 * source, on a background thread. That catches water, slopes and dense biomes before anyone walks
 * there, so almost every mark that reaches its trigger is a usable site. Trees, structures and player builds are not in the
 * noise, so {@link #settle} still checks real blocks, in the elected area only.
 */
public final class MarkSites {

    /** Footprint half width when an event declares no reach. */
    private static final int MIN_HALF = 16;
    /** How far from the mark {@link #settle} may move the event. */
    private static final int SETTLE_RADIUS = 24;
    private static final int SETTLE_SAMPLES = 24;

    private MarkSites() {}

    /** A spot the server thread has already cleared of players, marks and zones. */
    public record Candidate(int x, int z) {}

    /**
     * Off the server thread: the index of the flattest candidate whose footprint suits the profile,
     * or -1. Reads only the world generator's height noise and biome source, which vanilla also
     * calls from worldgen workers, so nothing here touches loaded chunks or level state. A height
     * read costs about 2 ms, which is why this is not done inline.
     */
    public static CompletableFuture<Integer> elect(ServerLevel level, List<Candidate> candidates,
                                                   MarkRules.Profile profile, int reach) {
        if (profile == MarkRules.Profile.ANY) return CompletableFuture.completedFuture(candidates.isEmpty() ? -1 : 0);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState noise = level.getChunkSource().randomState();
        BiomeSource biomes = generator.getBiomeSource();
        Climate.Sampler sampler = noise.sampler();
        int sea = level.getSeaLevel();
        List<Candidate> copy = List.copyOf(candidates);
        return CompletableFuture.supplyAsync(() -> {
            int best = -1;
            double bestScore = MarkRules.REJECT;
            for (int i = 0; i < copy.size(); i++) {
                Candidate c = copy.get(i);
                // At the surface: sampled at sea level, a hill reads as the cave biome under it.
                int y = profile == MarkRules.Profile.WATER ? sea
                        : generator.getBaseHeight(c.x(), c.z(), Heightmap.Types.OCEAN_FLOOR_WG, level, noise);
                Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(c.x()), QuartPos.fromBlock(y),
                        QuartPos.fromBlock(c.z()), sampler);
                if (!biomeSuits(biome, profile)) continue;
                double score = footprint(generator, noise, level, c.x(), c.z(), profile, reach);
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            return best;
        }, Util.backgroundExecutor());
    }

    private static boolean biomeSuits(Holder<Biome> biome, MarkRules.Profile profile) {
        if (profile == MarkRules.Profile.WATER) {
            return biome.is(BiomeTags.IS_OCEAN) && !biome.is(Biomes.FROZEN_OCEAN) && !biome.is(Biomes.DEEP_FROZEN_OCEAN);
        }
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) return false;
        return profile != MarkRules.Profile.VEHICLE || !dense(biome);
    }

    private static double footprint(ChunkGenerator generator, RandomState noise, LevelHeightAccessor heights,
                                    int x, int z, MarkRules.Profile profile, int reach) {
        int half = Math.max(MIN_HALF, reach);
        int n = MarkRules.GRID;
        double spacing = 2.0 * half / (n - 1);
        int[] surface = new int[n * n];
        int[] floor = new int[n * n];
        // Stop reading once the footprint can no longer reach the profile's share: each read is ~2 ms.
        int allowedMisses = n * n - (int) Math.ceil(n * n * profile.share);
        int misses = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int i = r * n + c;
                int sx = x - half + (int) Math.round(spacing * c);
                int sz = z - half + (int) Math.round(spacing * r);
                floor[i] = generator.getBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, heights, noise);
                surface[i] = generator.getBaseHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, heights, noise);
                boolean wet = surface[i] - floor[i] >= (profile == MarkRules.Profile.WATER ? MarkRules.MIN_DEPTH : 1);
                boolean good = profile == MarkRules.Profile.WATER ? wet : surface[i] == floor[i];
                if (!good && ++misses > allowedMisses) return MarkRules.REJECT;
            }
        }
        return MarkRules.score(profile, surface, floor, n, spacing);
    }

    /** Jungle, mountain peaks and dark forest: a hull cannot move through them. */
    private static boolean dense(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_MOUNTAIN)) return true;
        ResourceLocation id = biome.unwrapKey().map(k -> k.location()).orElse(null);
        return id != null && id.getPath().contains("dark_forest");
    }

    /**
     * Where the event sets up once the mark's chunk is simulated: the closest sampled point within
     * {@link #SETTLE_RADIUS} of the mark, in a simulated chunk, with real footing for the profile.
     * Null when the area has none; the mark is then dropped and a new one elected.
     */
    @Nullable
    public static BlockPos settle(ServerLevel level, double markX, double markZ, MarkRules.Profile profile,
                                  RandomSource random) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < SETTLE_SAMPLES; i++) {
            double r = i == 0 ? 0.0 : random.nextDouble() * SETTLE_RADIUS;
            double a = random.nextDouble() * Math.PI * 2.0;
            int x = (int) Math.floor(markX + Math.cos(a) * r);
            int z = (int) Math.floor(markZ + Math.sin(a) * r);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isPositionEntityTicking(pos)) continue;
            if (!SpawnZones.allows(level, pos)) continue;
            boolean fits = switch (profile) {
                case WATER -> level.getFluidState(pos.below()).is(FluidTags.WATER);
                // Every other event, overflight included, checks SEM's own gate when it runs.
                default -> SpawnHelper.isValidSpawn(level, pos);
            };
            if (fits && r < bestD) {
                best = pos;
                bestD = r;
            }
        }
        return best;
    }
}
