package com.neoalive.tacz_sewv.entity.ai.core;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

/**
 * Which crewed vehicle a projectile or a damage source came from. Bombs in one stick are siblings
 * when this answers the same hull for both, so they can ignore each other's hitboxes and blasts.
 */
public final class ProjectileOwners {

    private ProjectileOwners() {}

    /**
     * The root vehicle behind {@code source}: the vehicle itself, or the vehicle the entity rides.
     * Null unless that vehicle carries an SEM unit, so player-only hulls keep SBW's behaviour.
     */
    @Nullable
    public static VehicleEntity crewedHull(@Nullable Entity source) {
        if (source == null) return null;
        if (source instanceof Projectile projectile) source = projectile.getOwner();
        if (source == null) return null;
        Entity root = source instanceof VehicleEntity ? source : source.getRootVehicle();
        if (!(root instanceof VehicleEntity hull)) return null;
        for (Entity passenger : hull.getPassengers()) {
            if (passenger instanceof AbstractUnit) return hull;
        }
        return null;
    }

    /** True when both resolve to the same crewed hull. */
    public static boolean sameCrewedHull(@Nullable Entity a, @Nullable Entity b) {
        VehicleEntity ha = crewedHull(a);
        return ha != null && ha == crewedHull(b);
    }
}
