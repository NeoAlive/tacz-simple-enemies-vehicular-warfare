package com.neoalive.tacz_sewv.zone;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Spawn zone queries for every spawn path. A lookup is a hash read and touches no world state,
 * so it is safe inside an entity join.
 */
public final class SpawnZones {

    private SpawnZones() {}

    public static boolean enabled() {
        try {
            return SewvConfig.ZONES_ENABLED.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean onlyPriority() {
        try {
            return SewvConfig.ONLY_SPAWN_IN_PRIORITIZATION.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static ZoneType typeAt(ServerLevel level, BlockPos pos) {
        if (!enabled()) return ZoneType.NEUTRAL;
        return SpawnZoneData.get(level).type(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Whether a unit may spawn at this position under the current zones and config. */
    public static boolean allows(ServerLevel level, BlockPos pos) {
        return allowsChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean allowsChunk(ServerLevel level, int cx, int cz) {
        if (!enabled()) return true;
        ZoneLayers layers = SpawnZoneData.get(level).layers();
        boolean only = onlyPriority();
        if (!only && layers.isEmpty()) return true;
        ZoneType t = layers.type(ZoneGeometry.pack(cx, cz));
        if (t == ZoneType.NOZONE) return false;
        return t == ZoneType.PRIORITY || !only;
    }

    /**
     * Moves an event spot onto a prioritization chunk inside [min, max] blocks of the anchor.
     * With "only spawn in prioritization" off this happens on a config-weighted roll; with it on
     * the move is forced. Returns the spot to use, or null when the event must not run.
     */
    @Nullable
    public static BlockPos eventSpot(ServerLevel level, BlockPos anchor, BlockPos pos, double min, double max,
            RandomSource random) {
        if (!enabled()) return pos;
        SpawnZoneData data = SpawnZoneData.get(level);
        ZoneType here = data.type(pos.getX() >> 4, pos.getZ() >> 4);
        if (here == ZoneType.PRIORITY) return pos;
        boolean forced = onlyPriority();
        boolean move = forced || random.nextDouble() < SewvConfig.PRIORITIZATION_REROLL_CHANCE.get();
        if (move) {
            BlockPos moved = priorityInRing(level, data, anchor, min, max, random);
            if (moved != null) return moved;
        }
        if (forced) return null;
        return here == ZoneType.NOZONE ? null : pos;
    }

    @Nullable
    private static BlockPos priorityInRing(ServerLevel level, SpawnZoneData data, BlockPos anchor, double min,
            double max, RandomSource random) {
        ZoneLayers layers = data.layers();
        long[] all = layers.priorityChunks();
        if (all.length == 0) return null;
        // Ring test first: it is arithmetic, the other two are lookups.
        long pick = ZoneGeometry.pickInRing(all, anchor.getX(), anchor.getZ(), min, max,
                k -> !layers.beaconCovered(k) && level.hasChunk(ZoneGeometry.x(k), ZoneGeometry.z(k)),
                random.nextDouble());
        if (pick == Long.MIN_VALUE) return null;
        int x = (ZoneGeometry.x(pick) << 4) + random.nextInt(16);
        int z = (ZoneGeometry.z(pick) << 4) + random.nextInt(16);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /**
     * The given position if allowed, otherwise the same offset inside the nearest allowed chunk
     * within {@code maxChunks} rings. Null when nothing nearby is allowed. Y is left unchanged.
     */
    @Nullable
    public static BlockPos snapToAllowed(ServerLevel level, BlockPos pos, int maxChunks) {
        if (allows(level, pos)) return pos;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        long k = ZoneGeometry.nearestAllowed(cx, cz, maxChunks,
                key -> allowsChunk(level, ZoneGeometry.x(key), ZoneGeometry.z(key)));
        if (k == Long.MIN_VALUE) return null;
        return new BlockPos((ZoneGeometry.x(k) << 4) + (pos.getX() & 15), pos.getY(),
                (ZoneGeometry.z(k) << 4) + (pos.getZ() & 15));
    }

    public static Iterable<ServerLevel> levels(MinecraftServer server) {
        return server.getAllLevels();
    }
}
