package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Combined NPC drive/turret mobility: health penalty × addon speed cap. Player-driven and
 * empty hulls always return 1.0. Shared by the engine and turret mobility mixins.
 *
 * <p>{@link HealthMobility} remains as a thin alias for callers that only need the health
 * axis; new code should call this.
 */
public final class NpcMobility {

    private NpcMobility() {}

    public static float multiplier(VehicleEntity vehicle) {
        if (!(vehicle.getFirstPassenger() instanceof AbstractUnit)) return 1.0f;
        return healthFactor(vehicle) * NpcVehicleOverrides.speedScale(vehicle);
    }

    private static float healthFactor(VehicleEntity vehicle) {
        if (!SewvConfig.HEALTH_MOBILITY_ENABLED.get()) return 1.0f;
        float max = vehicle.getMaxHealth();
        if (max <= 0.0f) return 1.0f;
        float frac = Mth.clamp(vehicle.getHealth() / max, 0.0f, 1.0f);
        float floor = SewvConfig.HEALTH_MOBILITY_FLOOR.get().floatValue();
        return floor + (1.0f - floor) * frac;
    }
}
