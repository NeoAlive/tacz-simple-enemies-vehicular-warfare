package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Soft-kill reaction to SBW lock / missile warnings: pop smoke (ground) or flares (air)
 * the same way a player holding the decoy key would. Processes immediately so an AI goal
 * that clears {@code decoyInputDown} the same tick cannot cancel the volley.
 *
 * <p><b>AI crews only.</b> Player-driven, empty and mixed hulls are skipped — lock and missile
 * warnings fire on a cadence (every ~3 ticks while locking; distance-scaled for in-flight
 * missiles), so without that gate a player hull would dump its launcher without the key.
 * One volley per threat episode; the same cadence would otherwise empty the magazine across
 * reloads for a single lock.
 */
public final class ThreatDecoy {

    /** Salvos within this window are one episode — mirrors the decoy voiceline grace. */
    private static final int EPISODE_TICKS = 120;
    private static final String EPISODE_KEY = "sewv:threat_decoy_ep";

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

    /** @return true if a volley was armed (ready AI-crewed launcher) */
    public static boolean pop(VehicleEntity hull) {
        if (hull.level().isClientSide || !hull.hasDecoy() || hull.getDecoyCount() <= 0) {
            return false;
        }
        // Empty / mixed / player aboard — CrewFacts already answers null for all three.
        if (CrewFacts.factionOf(hull) == null) {
            return false;
        }
        CompoundTag data = hull.getPersistentData();
        long now = hull.level().getGameTime();
        if (now < data.getLong(EPISODE_KEY)) {
            return false;
        }
        data.putLong(EPISODE_KEY, now + EPISODE_TICKS);

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
