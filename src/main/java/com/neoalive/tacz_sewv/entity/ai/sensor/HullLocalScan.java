package com.neoalive.tacz_sewv.entity.ai.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;

/**
 * One LivingEntity fill per hull per scan interval, shared by target acquisition and
 * {@link com.neoalive.tacz_sewv.entity.ai.utility.Facts} force counts.
 *
 * <p>The fill is a query of {@link CombatantIndex}'s snapshot (no world query). Keyed on hull network id. Identity mismatch (via {@link Level#getEntity(int)}) or expiry
 * forces a refill; hits are free list reuse. No live {@link VehicleEntity} is stored — leave-level
 * {@link #invalidate(int)} plus server-stop {@link #clearAll()} drop abandoned rows. Counters
 * expose fill vs hit rate for Spark-adjacent MSPT diagnosis.
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

    public static void clearAll() {
        BY_HULL.clear();
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
        if (e != null) {
            if (now < e.expiresAt && stillSameHull(level, id, v)) {
                HITS.increment();
                return e;
            }
            BY_HULL.remove(id, e);
        }

        // Fill from the per-level combatant index, never from a world query. No snapshot yet (first tick
        // of a level, or after a stop/reload) means "nothing known": answer empty and do NOT cache it, or
        // the emptiness would stand for a whole scan interval.
        CombatantIndex.Snapshot snapshot = CombatantIndex.snapshot(level);
        if (snapshot == null) return new Entry();

        double radius = SewvConfig.VEHICLE_TARGET_SCAN_RADIUS.get();
        double halfHeight = SewvConfig.VEHICLE_TARGET_SCAN_HEIGHT.get() / 2.0;
        double slack = altitudeSlack(v);
        List<LivingEntity> living = new ArrayList<>();
        for (CombatantIndex.Entry found : snapshot.query(v.getX(), v.getZ(), radius,
                v.getY() - halfHeight - slack, v.getY() + halfHeight)) {
            LivingEntity le = found.living;
            if (le != null && le.isAlive()) living.add(le);
        }
        int interval = SewvConfig.VEHICLE_TARGET_SCAN_INTERVAL_TICKS.get();

        Entry fresh = new Entry();
        fresh.expiresAt = now + interval;
        fresh.living = living;
        fresh.units = null;
        BY_HULL.put(id, fresh);
        FILLS.increment();
        return fresh;
    }

    private static boolean stillSameHull(Level level, int id, VehicleEntity v) {
        Entity live = level.getEntity(id);
        return live == v;
    }

    private static double altitudeSlack(VehicleEntity v) {
        // Cached engine type, not isHelicopterHull/isPlaneHull: those re-run computed() on every call.
        EngineType type = HullFacts.engineType(v);
        if (type != EngineType.HELICOPTER && type != EngineType.AIRCRAFT) {
            return 0.0;
        }
        int surface = v.level().getHeight(Heightmap.Types.WORLD_SURFACE, v.getBlockX(), v.getBlockZ());
        // Cap so a 200 AGL cruise does not inflate the LivingEntity AABB by full altitude while
        // the hull is ticketed far from players.
        return Math.min(64.0, Math.max(0.0, v.getY() - surface));
    }

    private static final class Entry {
        long expiresAt;
        List<LivingEntity> living = List.of();
        /** Lazily filtered from {@link #living}; null until first units request. */
        List<AbstractUnit> units;
    }
}
