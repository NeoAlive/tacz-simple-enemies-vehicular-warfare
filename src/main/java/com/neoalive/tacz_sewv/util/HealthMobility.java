package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

/**
 * Drive-speed / turret-slew multiplier for a damaged AI-crewed vehicle: 1.0 at full health,
 * falling linearly to {@code healthMobilityFloor} at 0. Shared by the two mobility mixins.
 *
 * <p>Returns 1.0 (no change) for a disabled feature, a player-driven hull, or an empty one.
 * SBW's own penalties are a different axis — binary component-damage flags (dead track/engine/
 * turret) that scale {@code power}/turret speed by fixed factors — so this stacks on top rather
 * than overwriting them.
 */
public final class HealthMobility {

    private HealthMobility() {}

    public static float multiplier(VehicleEntity vehicle) {
        if (!SewvConfig.HEALTH_MOBILITY_ENABLED.get()) return 1.0f;
        // AI-crewed only: a hull with a unit at the wheel. Player-driven hulls are untouched.
        if (!(vehicle.getFirstPassenger() instanceof AbstractUnit)) return 1.0f;
        float max = vehicle.getMaxHealth();
        if (max <= 0.0f) return 1.0f;
        float frac = Mth.clamp(vehicle.getHealth() / max, 0.0f, 1.0f);
        float floor = SewvConfig.HEALTH_MOBILITY_FLOOR.get().floatValue();
        return floor + (1.0f - floor) * frac;
    }
}
