package com.neoalive.tacz_sewv.entity.ai.navigation;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Type63Entity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Occupancy index of vehicle <em>hull</em> hitboxes for SEM infantry pathfinding and LOS.
 *
 * <p>SBW OBB hulls return {@code canBeCollidedWith() == false}, so vanilla
 * {@code WalkNodeEvaluator} treats their volume as open air, and SEM / vanilla
 * line-of-sight is {@code Level.clip} (blocks only). This cache rasterizes each
 * loaded hull's chassis volume into packed block cells;
 * {@link com.neoalive.tacz_sewv.mixin.MixinWalkNodeEvaluator} overlays
 * {@code BLOCKED} on those cells for on-foot {@code AbstractUnit}s, and
 * {@link #occludes} is the same map walked as a ray so infantry stop dumping
 * rounds into a hull that sits between them and their target.
 *
 * <p>Only {@link OBB.Part#BODY} / {@link OBB.Part#COLLISION} are stamped — never the
 * turret or interactive parts. The old combined-AABB path inflated a continuously
 * mutating cube that included the turret sweep and blocked shots through empty air
 * under the barrel. Entity {@code getBoundingBox()} is the fallback when a hull
 * publishes no body OBBs.
 *
 * <p>MCSP / ASH / FCP hulls subclass SBW {@link VehicleEntity}, so one scan covers all of them.
 * {@link TurretWreckEntity} is a separate type (the blown-off turret), indexed from the
 * same rebuild. Refresh is throttled — never rebuild per path-search node.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class VehiclePathObstacles {

    private static final int REFRESH_INTERVAL = 15;
    private static final double INFLATE = 0.35;
    /** Half-block samples along a LOS ray — enough to not skip a 1-block cell. */
    private static final double SAMPLE_PER_BLOCK = 2.0;

    private static final Map<ServerLevel, Cache> CACHES = new IdentityHashMap<>();

    private VehiclePathObstacles() {}

    /** Whether this cell is occupied by a vehicle other than {@code excludeEntityId}. */
    public static boolean blocks(ServerLevel level, int x, int y, int z, int excludeEntityId) {
        Cache cache = CACHES.get(level);
        if (cache == null) return false;
        int id = cache.cells.get(BlockPos.asLong(x, y, z));
        return id != 0 && id != excludeEntityId;
    }

    /**
     * True when a hull other than {@code shooter}'s ride and {@code target}'s ride
     * straddles eye-to-eye. Used by SEM infantry LOS mixins.
     */
    public static boolean occludesLos(Entity shooter, LivingEntity target) {
        if (!(shooter.level() instanceof ServerLevel level)) return false;
        return occludes(level, shooter.getEyePosition(), target.getEyePosition(),
                rideId(shooter), rideId(target));
    }

    /** Network id of whatever {@code entity} is riding, or {@code -1} if on foot. */
    public static int rideId(Entity entity) {
        Entity ride = entity.getVehicle();
        return ride != null ? ride.getId() : -1;
    }

    /**
     * True when a rasterized hull other than {@code excludeA}/{@code excludeB}
     * sits on the segment {@code from}→{@code to}. The origin cell is skipped so
     * a unit hugging a wreck does not fail LOS looking away from it.
     */
    public static boolean occludes(ServerLevel level, Vec3 from, Vec3 to, int excludeA, int excludeB) {
        Cache cache = CACHES.get(level);
        if (cache == null || cache.cells.isEmpty()) return false;
        return occludesRay(cache.cells, from, to, excludeA, excludeB);
    }

    /** Package-visible for the headless self-check. */
    static boolean occludesRay(Long2IntMap cells, Vec3 from, Vec3 to, int excludeA, int excludeB) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4) return false;

        int originX = Mth.floor(from.x);
        int originY = Mth.floor(from.y);
        int originZ = Mth.floor(from.z);
        int steps = Math.max(1, Mth.ceil(dist * SAMPLE_PER_BLOCK));
        double inv = 1.0 / steps;
        for (int i = 1; i < steps; i++) {
            double t = i * inv;
            int x = Mth.floor(from.x + dx * t);
            int y = Mth.floor(from.y + dy * t);
            int z = Mth.floor(from.z + dz * t);
            if (x == originX && y == originY && z == originZ) continue;
            int id = cells.get(BlockPos.asLong(x, y, z));
            if (id != 0 && id != excludeA && id != excludeB) return true;
        }
        return false;
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
            stampHull(cache, hull);
        }
        for (TurretWreckEntity wreck : level.getEntities(EntityTypeTest.forClass(TurretWreckEntity.class), w -> true)) {
            if (!wreck.isAlive()) continue;
            stamp(cache, wreck.getBoundingBox().inflate(INFLATE, 0.0, INFLATE), wreck.getId());
        }
    }

    /**
     * Chassis only: {@code BODY}/{@code COLLISION} world AABBs. Turret / interactive /
     * empty parts are skipped so a barrel sweeping overhead does not paint empty air
     * as solid for infantry LoS and pathing.
     */
    private static void stampHull(Cache cache, VehicleEntity hull) {
        int id = hull.getId();
        List<OBB> obbs = hull.getOBBs();
        boolean stamped = false;
        if (obbs != null) {
            for (OBB obb : obbs) {
                OBB.Part part = obb.part;
                if (part != OBB.Part.BODY && part != OBB.Part.COLLISION) continue;
                stamp(cache, OBB.getWorldAABB(obb).inflate(INFLATE, 0.0, INFLATE), id);
                stamped = true;
            }
        }
        if (!stamped) {
            // No body OBBs published (rare / addon) — entity BB is the chassis, not the turret.
            stamp(cache, hull.getBoundingBox().inflate(INFLATE, 0.0, INFLATE), id);
        }
    }

    private static void stamp(Cache cache, AABB box, int id) {
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cache.cells.put(BlockPos.asLong(x, y, z), id);
                }
            }
        }
    }

    private static boolean include(VehicleEntity hull) {
        if (!hull.isAlive()) return false;
        // Seatless recon / mortar footprints are noise next to where infantry must stand.
        if (hull instanceof DroneEntity || hull instanceof MortarEntity || hull instanceof Type63Entity) {
            return false;
        }
        return true;
    }

    /** Headless DDA checks — run from {@code GroundMobilitySelfCheck}. */
    public static void selfCheck() {
        Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
        cells.defaultReturnValue(0);
        // A 3×2×3 hull occupying (4..6, 64..65, 10..12), entity id 7.
        for (int x = 4; x <= 6; x++) {
            for (int y = 64; y <= 65; y++) {
                for (int z = 10; z <= 12; z++) {
                    cells.put(BlockPos.asLong(x, y, z), 7);
                }
            }
        }
        Vec3 west = new Vec3(0.5, 64.5, 11.5);
        Vec3 east = new Vec3(10.5, 64.5, 11.5);
        assert occludesRay(cells, west, east, -1, -1) : "a hull on the line must occlude";
        assert !occludesRay(cells, west, east, 7, -1) : "the shooter's own hull must not occlude";
        assert !occludesRay(cells, west, east, -1, 7) : "the target's hull must not occlude";
        Vec3 north = new Vec3(5.5, 64.5, 0.5);
        Vec3 stillNorth = new Vec3(5.5, 64.5, 5.5);
        assert !occludesRay(cells, north, stillNorth, -1, -1) : "a ray that never enters the hull is clear";
        Vec3 hug = new Vec3(4.4, 64.5, 11.5);
        Vec3 away = new Vec3(-4.5, 64.5, 11.5);
        assert !occludesRay(cells, hug, away, -1, -1) : "hugging a wreck looking away must not self-block";
    }

    private static final class Cache {
        final Long2IntMap cells = new Long2IntOpenHashMap();
        int nextRefresh;

        Cache() {
            this.cells.defaultReturnValue(0);
        }
    }
}
