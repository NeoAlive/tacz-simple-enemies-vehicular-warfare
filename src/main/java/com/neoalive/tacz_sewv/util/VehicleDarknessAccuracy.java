package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-hull darkness accuracy fraction for {@link com.neoalive.tacz_sewv.mixin.MixinAiAimSpread}.
 * Light + rendered-seat NVG are scanned on a ~1s game-time cadence, not per pellet.
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

    private static Entry entry(VehicleEntity v) {
        Level level = v.level();
        long now = level.getGameTime();
        int id = v.getId();
        Entry e = BY_HULL.get(id);
        if (e != null && e.hull == v && now < e.expiresAt) {
            return e;
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
        fresh.hull = v;
        fresh.expiresAt = now + REFRESH_TICKS;
        fresh.fraction = fraction;
        BY_HULL.put(id, fresh);
        return fresh;
    }

    private static final class Entry {
        VehicleEntity hull;
        long expiresAt;
        double fraction = 1.0;
    }
}
