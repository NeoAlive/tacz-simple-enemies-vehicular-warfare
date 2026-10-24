package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import com.neoalive.tacz_sewv.compat.NpcVehicleOverrides;
import com.neoalive.tacz_sewv.config.EasyMode;
import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Combined NPC drive/turret mobility: health penalty × addon speed cap × Easy Mode's slowdown for
 * a hostile crew (separate factors for the engine and the turret; the turret also takes the global
 * {@code aiTurretTraverseScale}). Player-driven and empty hulls
 * always return 1.0. Shared by the engine and turret mobility mixins.
 */
public final class NpcMobility {

    private NpcMobility() {}

    public static float multiplier(VehicleEntity vehicle) {
        if (!(vehicle.getFirstPassenger() instanceof AbstractUnit)) return 1.0f;
        return healthFactor(vehicle) * NpcVehicleOverrides.speedScale(vehicle);
    }

    /** Engine thrust factor. */
    public static float driveMultiplier(VehicleEntity vehicle) {
        Entity driver = vehicle.getFirstPassenger();
        if (!(driver instanceof AbstractUnit)) return 1.0f;
        return multiplier(vehicle) * EasyMode.driveThrustMult(driver);
    }

    /** Turret traverse factor. */
    public static float turretMultiplier(VehicleEntity vehicle) {
        Entity driver = vehicle.getFirstPassenger();
        if (!(driver instanceof AbstractUnit)) return 1.0f;
        return multiplier(vehicle) * SewvConfig.AI_TURRET_TRAVERSE_SCALE.get().floatValue()
                * EasyMode.turretTraverseMult(driver);
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
