package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

/**
 * @deprecated Prefer {@link NpcMobility} — health × addon speed caps for NPC crews.
 * Kept as a delegate so older call sites keep compiling.
 */
@Deprecated
public final class HealthMobility {

    private HealthMobility() {}

    public static float multiplier(VehicleEntity vehicle) {
        return NpcMobility.multiplier(vehicle);
    }
}
