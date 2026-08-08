package com.neoalive.tacz_sewv.util.vehiclemelee;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * Hull-side inputs for {@link DamageEvaluator}: max HP and how many mobs are currently focused
 * on this hull or its passengers (cheap focus-fire proxy).
 */
public record VehicleFacts(float maxHealth, float healthFrac, int attackerCount) {

    private static final double SCAN_INFLATE = 4.0;

    public static VehicleFacts of(VehicleEntity hull) {
        float max = hull.getMaxHealth();
        float frac = max <= 0.0F ? 0.0F : Math.min(1.0F, hull.getHealth() / max);
        return new VehicleFacts(max, frac, countAttackers(hull));
    }

    private static int countAttackers(VehicleEntity hull) {
        AABB box = hull.getBoundingBox().inflate(SCAN_INFLATE);
        int n = 0;
        for (Mob mob : hull.level().getEntitiesOfClass(Mob.class, box, Mob::isAlive)) {
            Entity target = mob.getTarget();
            if (target == null) continue;
            if (target == hull || target.getVehicle() == hull) n++;
        }
        return Math.max(1, n);
    }
}
