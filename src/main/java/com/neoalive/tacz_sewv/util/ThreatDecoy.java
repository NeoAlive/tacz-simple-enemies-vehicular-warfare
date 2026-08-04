package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Soft-kill reaction to SBW lock / missile warnings: pop smoke (ground) or flares (air)
 * the same way a player holding the decoy key would. Processes immediately so an AI goal
 * that clears {@code decoyInputDown} the same tick cannot cancel the volley.
 */
public final class ThreatDecoy {

    private ThreatDecoy() {}

    /** Warned entity from {@code SeekingWeaponWarningMessage} (hull or a passenger). */
    public static void popForWarned(@Nullable Entity warned) {
        if (warned == null || warned.level().isClientSide) return;
        VehicleEntity hull = resolveHull(warned);
        if (hull != null) pop(hull);
    }

    /** In-flight missile warning plays at {@code onPos} — find a decoy-capable hull there. */
    public static void popNear(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        AABB box = new AABB(pos).inflate(2.5);
        for (VehicleEntity hull : level.getEntitiesOfClass(VehicleEntity.class, box)) {
            if (pop(hull)) return;
        }
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (living.getVehicle() instanceof VehicleEntity hull && pop(hull)) return;
        }
    }

    /** @return true if a volley was armed (ready launcher) */
    public static boolean pop(VehicleEntity hull) {
        if (hull.level().isClientSide || !hull.hasDecoy() || !hull.getDecoyReady()) {
            return false;
        }
        hull.setDecoyInputDown(true);
        VehicleType type = hull.getVehicleType();
        if (type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER) {
            hull.releaseDecoy();
        } else {
            hull.releaseSmokeDecoy(hull.getTurretVector(1.0F));
        }
        return true;
    }

    @Nullable
    private static VehicleEntity resolveHull(Entity warned) {
        if (warned instanceof VehicleEntity hull) return hull;
        if (warned.getVehicle() instanceof VehicleEntity hull) return hull;
        return null;
    }
}
