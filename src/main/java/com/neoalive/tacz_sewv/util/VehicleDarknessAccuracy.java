package com.neoalive.tacz_sewv.util;

import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.NvgSupport;

/**
 * Per-hull darkness accuracy fraction for {@link com.neoalive.tacz_sewv.mixin.MixinAiAimSpread}.
 * Light + rendered-seat NVG are scanned on a ~1s game-time cadence, not per pellet.
 *
 * <p>Entries are keyed by network id and hold no live {@link VehicleEntity} — leave-level
 * invalidation plus identity checks via {@link Level#getEntity(int)} keep the map from pinning
 * destroyed hulls.
 */
public final class VehicleDarknessAccuracy {

    private static final int REFRESH_TICKS = 20;

    private static final ConcurrentHashMap<Integer, Entry> BY_HULL = new ConcurrentHashMap<>();

    private VehicleDarknessAccuracy() {
    }

    /**
     * Accuracy multiplier in {@code (0, 1]} — {@code 1.0} means no darkness penalty. Spread is
     * scaled by {@code 1 / fraction} at the shot hook.
     */
    public static double accuracyFraction(VehicleEntity vehicle) {
        return entry(vehicle).fraction;
    }

    public static void invalidate(int hullId) {
        BY_HULL.remove(hullId);
    }

    public static void clearAll() {
        BY_HULL.clear();
    }

    private static Entry entry(VehicleEntity v) {
        Level level = v.level();
        long now = level.getGameTime();
        int id = v.getId();
        Entry e = BY_HULL.get(id);
        if (e != null) {
            if (now < e.expiresAt && stillSameHull(level, id, v)) {
                return e;
            }
            BY_HULL.remove(id, e);
        }

        double fraction = 1.0;
        if (NvgSupport.isDark(level, v.blockPosition())) {
            fraction = NvgSupport.vehicleHasRenderedNvg(v)
                    ? SewvConfig.NVG_ACCURACY_FRACTION.get()
                    : SewvConfig.DARK_ACCURACY_FRACTION.get();
            // Never worse than the hard spread-scale ceiling (1 / maxScale).
            double floor = 1.0 / SewvConfig.DARK_SPREAD_SCALE_MAX.get();
            if (fraction < floor) fraction = floor;
        }

        Entry fresh = new Entry();
        fresh.expiresAt = now + REFRESH_TICKS;
        fresh.fraction = fraction;
        BY_HULL.put(id, fresh);
        return fresh;
    }

    private static boolean stillSameHull(Level level, int id, VehicleEntity v) {
        Entity live = level.getEntity(id);
        return live == v;
    }

    private static final class Entry {
        long expiresAt;
        double fraction = 1.0;
    }
}
