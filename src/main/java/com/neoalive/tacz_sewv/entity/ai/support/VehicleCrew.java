package com.neoalive.tacz_sewv.entity.ai.support;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Shared seat test for goals that must not run infantry pathfinding while a unit is crewing an
 * SBW hull. Mortars are seatless and stand beside the tube — they are not passengers, so they
 * keep SEM's on-foot tactical goals.
 */
public final class VehicleCrew {

    private VehicleCrew() {
    }

    /** True when the mob is riding any SuperbWarfare {@link VehicleEntity}. */
    public static boolean suppressOnFootAi(Mob mob) {
        return mob != null && mob.getVehicle() instanceof VehicleEntity;
    }

    /** Overload for callers that already hold an {@link Entity}. */
    public static boolean suppressOnFootAi(Entity entity) {
        return entity instanceof Mob mob && suppressOnFootAi(mob);
    }
}
