package com.neoalive.tacz_sewv.entity.ai.cover;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.debug.SewvDiag;

/**
 * Killzone-style worst-case visibility table over Minecraft columns.
 *
 * <p>Per 2×2 cell, stores distance (blocks) to the nearest opaque occluder in each of 8 compass
 * directions at tank turret eye height. Shared across all crews on a {@link ServerLevel} —
 * lookups are O(1) after warm. Lazy bake + event dirty + budgeted per-tick rebuild.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class CoverVisibilityCache {

    public static final int CELL = 2;
    public static final int DIRS = 8;
    public static final int MAX_RANGE = 48;
    public static final int TURRET_EYE = 2;
    /** Cells along one chunk edge: 16 / 2 = 8. */
    public static final int CELLS_PER_EDGE = 16 / CELL;
    public static final int CELLS_PER_CHUNK = CELLS_PER_EDGE * CELLS_PER_EDGE;
    public static final int BYTES_PER_CHUNK = CELLS_PER_CHUNK * DIRS;

    /** Unit steps for N, NE, E, SE, S, SW, W, NW (index 0 = +Z north in MC). */
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {1, 1, 0, -1, -1, -1, 0, 1};

    private static final Map<ServerLevel, LevelCache> CACHES = new IdentityHashMap<>();

    private CoverVisibilityCache() {}

    /** Packed chunk key from block coords. */
    public static long chunkKey(int blockX, int blockZ) {
        return ChunkPosKey.of(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
    }

    /** Packed 2×2 cell key (world cell coords, not block). */
    public static long cellKey(int blockX, int blockZ) {
        return BlockPos.asLong(blockX >> 1, 0, blockZ >> 1);
    }

    /** Nearest of 8 compass dirs for a horizontal delta. */
    public static int compass8(double dx, double dz) {
        // atan2(dx, dz): 0 = +Z (N), increasing clockwise toward +X (E).
        double ang = Math.atan2(dx, dz);
        if (ang < 0.0) ang += Math.PI * 2.0;
        int dir = (int) Math.round(ang / (Math.PI * 0.25)) & 7;
        return dir;
    }

    /**
     * Distance to nearest occluder in {@code dir} from the 2×2 cell containing
     * ({@code blockX},{@code blockZ}). Returns {@link #MAX_RANGE} when clear or cache disabled /
     * not yet baked (optimistic — treat as exposed).
     */
    public static int distance(ServerLevel level, int blockX, int blockZ, int dir) {
        if (!enabled()) return MAX_RANGE;
        dir &= 7;
        LevelCache cache = CACHES.computeIfAbsent(level, l -> new LevelCache());
        long ck = chunkKey(blockX, blockZ);
        ChunkCoverGrid grid = cache.grids.get(ck);
        if (grid == null) {
            cache.dirtyChunks.add(ck);
            return MAX_RANGE;
        }
        int lx = Mth.positiveModulo((blockX >> 1), CELLS_PER_EDGE);
        int lz = Mth.positiveModulo((blockZ >> 1), CELLS_PER_EDGE);
        return grid.distance(lx, lz, dir);
    }

    public static boolean enabled() {
        try {
            return SewvConfig.COVER_CACHE_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    static int bakeBudget() {
        try {
            return SewvConfig.COVER_CACHE_BAKE_CELLS_PER_TICK.get();
        } catch (Throwable ignored) {
            return 32;
        }
    }

    /** Package-visible bake of one cell into {@code out[base..base+7]}. */
    static void bakeCell(ServerLevel level, int cellX, int cellZ, byte[] out, int base) {
        int centerX = (cellX << 1) + 1;
        int centerZ = (cellZ << 1) + 1;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        int eyeY = surface + TURRET_EYE;
        for (int dir = 0; dir < DIRS; dir++) {
            int d = 0;
            for (; d < MAX_RANGE; d++) {
                int x = centerX + DX[dir] * d;
                int z = centerZ + DZ[dir] * d;
                // Re-read height along the walk so berms / trenches at different Y still occlude.
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + TURRET_EYE;
                // Prefer the walk eye band: sample at eyeY and at local surface+eye.
                if (occludes(level, x, eyeY, z) || (y != eyeY && occludes(level, x, y, z))) {
                    break;
                }
            }
            out[base + dir] = (byte) Math.min(d, MAX_RANGE);
        }
    }

    static boolean occludes(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        // Leaves are soft cover for ContactSight UNCERTAIN — treat as transparent here so the
        // worst-case table matches "hard" occlusion only.
        if (state.is(BlockTags.LEAVES)) return false;
        if (!state.canOcclude()) return false;
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty();
    }

    static void bakeChunk(ServerLevel level, int chunkX, int chunkZ, ChunkCoverGrid grid) {
        int baseCellX = chunkX * CELLS_PER_EDGE;
        int baseCellZ = chunkZ * CELLS_PER_EDGE;
        byte[] dist = grid.dist;
        for (int lz = 0; lz < CELLS_PER_EDGE; lz++) {
            for (int lx = 0; lx < CELLS_PER_EDGE; lx++) {
                int base = (lz * CELLS_PER_EDGE + lx) * DIRS;
                bakeCell(level, baseCellX + lx, baseCellZ + lz, dist, base);
            }
        }
        grid.ready = true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!enabled()) return;
        int budget = bakeBudget();
        Set<ServerLevel> live = new HashSet<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            live.add(level);
            LevelCache cache = CACHES.computeIfAbsent(level, l -> new LevelCache());
            drain(level, cache, budget);
        }
        CACHES.keySet().retainAll(live);
    }

    private static void drain(ServerLevel level, LevelCache cache, int budget) {
        int spent = 0;
        while (spent < budget && !cache.dirtyChunks.isEmpty()) {
            long ck = cache.dirtyChunks.removeFirst();
            if (!cache.dirtySet.remove(ck)) continue;
            int cx = ChunkPosKey.x(ck);
            int cz = ChunkPosKey.z(ck);
            if (!level.hasChunk(cx, cz)) continue;
            ChunkCoverGrid grid = cache.grids.computeIfAbsent(ck, k -> new ChunkCoverGrid());
            bakeChunk(level, cx, cz, grid);
            SewvDiag.cover("baked chunk={},{} dim={}", cx, cz, level.dimension().location());
            spent += CELLS_PER_CHUNK;
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        dirtyAround(level, event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        dirtyAround(level, event.getPos());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LevelCache cache = CACHES.get(level);
        if (cache == null) return;
        long ck = ChunkPosKey.of(event.getChunk().getPos().x, event.getChunk().getPos().z);
        cache.grids.remove(ck);
        if (cache.dirtySet.remove(ck)) {
            cache.dirtyChunks.remove(ck);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CACHES.clear();
    }

    private static void dirtyAround(ServerLevel level, BlockPos pos) {
        if (!enabled()) return;
        LevelCache cache = CACHES.computeIfAbsent(level, l -> new LevelCache());
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        // Halo of 1 chunk — distances ≤48 can reach into a neighbour.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                enqueueDirty(cache, ChunkPosKey.of(cx + dx, cz + dz));
            }
        }
    }

    private static void enqueueDirty(LevelCache cache, long ck) {
        if (cache.dirtySet.add(ck)) {
            cache.dirtyChunks.addLast(ck);
        }
        // Drop stale grid so lookups re-queue until rebaked.
        cache.grids.remove(ck);
    }

    /** Force-enqueue a chunk for bake (tests / first touch). */
    public static void requestBake(ServerLevel level, int blockX, int blockZ) {
        LevelCache cache = CACHES.computeIfAbsent(level, l -> new LevelCache());
        enqueueDirty(cache, chunkKey(blockX, blockZ));
    }

    @Nullable
    static ChunkCoverGrid gridIfReady(ServerLevel level, int blockX, int blockZ) {
        LevelCache cache = CACHES.get(level);
        if (cache == null) return null;
        ChunkCoverGrid grid = cache.grids.get(chunkKey(blockX, blockZ));
        return grid != null && grid.ready ? grid : null;
    }

    static final class ChunkCoverGrid {
        final byte[] dist = new byte[BYTES_PER_CHUNK];
        boolean ready;

        int distance(int lx, int lz, int dir) {
            int v = dist[(lz * CELLS_PER_EDGE + lx) * DIRS + dir] & 0xFF;
            return Math.min(v, MAX_RANGE);
        }
    }

    private static final class LevelCache {
        final Long2ObjectMap<ChunkCoverGrid> grids = new Long2ObjectOpenHashMap<>();
        final ArrayDeque<Long> dirtyChunks = new ArrayDeque<>();
        final LongSet dirtySet = new LongOpenHashSet();
    }

    /** Pack/unpack chunk XZ without depending on ChunkPos allocation. */
    static final class ChunkPosKey {
        static long of(int x, int z) {
            return ((long) x & 0xFFFFFFFFL) | (((long) z) << 32);
        }

        static int x(long key) {
            return (int) key;
        }

        static int z(long key) {
            return (int) (key >>> 32);
        }
    }
}
