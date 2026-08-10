package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * One LivingEntity AABB fill per hull per scan interval, shared by target acquisition and
 * {@link com.neoalive.tacz_sewv.entity.ai.utility.Facts} force counts.
 *
 * <p>Keyed on hull network id. Identity mismatch or expiry forces a refill; hits are free list
 * reuse. Counters expose fill vs hit rate for Spark-adjacent MSPT diagnosis.
 */
public final class HullLocalScan {

    private static final ConcurrentHashMap<Integer, Entry> BY_HULL = new ConcurrentHashMap<>();

    private static final LongAdder FILLS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();

    private HullLocalScan() {}

    /** Snapshot of cache traffic since process start (or last {@link #resetStats()}). */
    public static String stats() {
        return "fills=" + FILLS.sum() + " hits=" + HITS.sum();
    }

    public static void resetStats() {
        FILLS.reset();
        HITS.reset();
    }

    public static void invalidate(int hullId) {
        BY_HULL.remove(hullId);
    }

    /**
     * Living entities in the mounted target-scan cylinder (same bounds as
     * {@link VehicleTargetScanGoal}). Filter further in the caller.
     */
    public static List<LivingEntity> livingInScanCylinder(VehicleEntity v) {
        return entry(v).living;
    }

    /**
     * {@link AbstractUnit}s inside the same horizontal/vertical box Facts uses for force ratio.
     * Derived from the LivingEntity fill — no second world query.
     */
    public static List<AbstractUnit> unitsInScanBox(VehicleEntity v) {
        Entry e = entry(v);
        if (e.units == null) {
            List<AbstractUnit> units = new ArrayList<>();
            for (LivingEntity living : e.living) {
                if (living instanceof AbstractUnit u && u.isAlive()) {
                    units.add(u);
                }
            }
            e.units = units;
        }
        return e.units;
    }

    private static Entry entry(VehicleEntity v) {
        Level level = v.level();
        long now = level.getGameTime();
        int id = v.getId();
        Entry e = BY_HULL.get(id);
        if (e != null && e.hull == v && now < e.expiresAt) {
            HITS.increment();
            return e;
        }

        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        double slack = altitudeSlack(v);
        AABB bounds = new AABB(
                v.getX() - radius, v.getY() - halfHeight - slack, v.getZ() - radius,
                v.getX() + radius, v.getY() + halfHeight, v.getZ() + radius);

        List<LivingEntity> living = level.getEntitiesOfClass(LivingEntity.class, bounds, LivingEntity::isAlive);
        int interval = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();

        Entry fresh = new Entry();
        fresh.hull = v;
        fresh.expiresAt = now + interval;
        fresh.living = living;
        fresh.units = null;
        BY_HULL.put(id, fresh);
        FILLS.increment();
        return fresh;
    }

    private static double altitudeSlack(VehicleEntity v) {
        if (!HullFacts.isHelicopterHull(v) && !HullFacts.isPlaneHull(v)) {
            return 0.0;
        }
        int surface = v.level().getHeight(Heightmap.Types.WORLD_SURFACE, v.getBlockX(), v.getBlockZ());
        return Math.max(0.0, v.getY() - surface);
    }

    private static final class Entry {
        VehicleEntity hull;
        long expiresAt;
        List<LivingEntity> living = List.of();
        /** Lazily filtered from {@link #living}; null until first units request. */
        List<AbstractUnit> units;
    }
}
