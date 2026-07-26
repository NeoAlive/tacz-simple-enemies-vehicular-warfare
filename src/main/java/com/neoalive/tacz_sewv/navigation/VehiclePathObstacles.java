package com.neoalive.tacz_sewv.navigation;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils;
import com.neoalive.tacz_sewv.TaczSewv;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Occupancy index of vehicle hitboxes for SEM infantry pathfinding.
 *
 * <p>SBW OBB hulls return {@code canBeCollidedWith() == false}, so vanilla
 * {@code WalkNodeEvaluator} treats their volume as open air. This cache rasterizes each
 * loaded hull's combined AABB into packed block cells; {@link com.neoalive.tacz_sewv.mixin.MixinWalkNodeEvaluator}
 * overlays {@code BLOCKED} on those cells for on-foot {@code AbstractUnit}s.
 *
 * <p>MCSP / ASH / FCP hulls subclass SBW {@link VehicleEntity}, so one scan covers all of them.
 * Refresh is throttled — never rebuild per path-search node.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehiclePathObstacles {

    private static final int REFRESH_INTERVAL = 15;
    private static final double INFLATE = 0.35;

    private static final Map<ServerLevel, Cache> CACHES = new IdentityHashMap<>();

    private VehiclePathObstacles() {}

    /** Whether this cell is occupied by a vehicle other than {@code excludeEntityId}. */
    public static boolean blocks(ServerLevel level, int x, int y, int z, int excludeEntityId) {
        Cache cache = CACHES.get(level);
        if (cache == null) return false;
        int id = cache.cells.get(BlockPos.asLong(x, y, z));
        return id != 0 && id != excludeEntityId;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int now = event.getServer().getTickCount();
        Set<ServerLevel> live = new HashSet<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            live.add(level);
            Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
            if (now < cache.nextRefresh) continue;
            cache.nextRefresh = now + REFRESH_INTERVAL;
            rebuild(level, cache);
        }
        CACHES.keySet().retainAll(live);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CACHES.clear();
    }

    private static void rebuild(ServerLevel level, Cache cache) {
        cache.cells.clear();
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            if (!include(hull)) continue;
            AABB box = VehicleMotionUtils.INSTANCE.calculateCombinedAABBOptimized(hull)
                    .inflate(INFLATE, 0.0, INFLATE);
            int minX = Mth.floor(box.minX);
            int maxX = Mth.floor(box.maxX);
            int minY = Mth.floor(box.minY);
            int maxY = Mth.floor(box.maxY);
            int minZ = Mth.floor(box.minZ);
            int maxZ = Mth.floor(box.maxZ);
            int id = hull.getId();
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        cache.cells.put(BlockPos.asLong(x, y, z), id);
                    }
                }
            }
        }
    }

    private static boolean include(VehicleEntity hull) {
        if (!hull.isAlive()) return false;
        // Seatless recon / mortar footprints are noise next to where infantry must stand.
        if (hull instanceof DroneEntity || hull instanceof MortarEntity) return false;
        return true;
    }

    private static final class Cache {
        final Long2IntMap cells = new Long2IntOpenHashMap();
        int nextRefresh;

        Cache() {
            this.cells.defaultReturnValue(0);
        }
    }
}
