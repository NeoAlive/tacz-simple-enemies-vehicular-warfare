package com.neoalive.tacz_sewv.entity.ai.plane;

import javax.annotation.Nullable;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.item.PlaneRole;

/**
 * Airplane Mode target filter. CAS takes everything but fixed-wing aircraft, FIGHTER takes only
 * fixed-wing aircraft, AUTO takes everything. Applies only to the pilot of a plane hull.
 */
public final class PlaneRoleFilter {

    public enum TargetClass {
        PLANE, OTHER
    }

    private PlaneRoleFilter() {}

    public static boolean allows(PlaneRole role, TargetClass cls) {
        return role.allows(cls == TargetClass.PLANE);
    }

    public static TargetClass classify(Entity target) {
        Entity root = target.getRootVehicle();
        if (root instanceof VehicleEntity hull && HullFacts.isPlaneHull(hull)) return TargetClass.PLANE;
        return TargetClass.OTHER;
    }

    /** The role of the pilot of the plane hull the unit rides, otherwise AUTO. Gunners follow the pilot. */
    public static PlaneRole roleOf(@Nullable Entity unit) {
        if (unit == null || !(unit.getVehicle() instanceof VehicleEntity hull)) return PlaneRole.AUTO;
        if (!(hull.getFirstPassenger() instanceof IHelicopterPilot pilot)) return PlaneRole.AUTO;
        PlaneRole role = pilot.sewv$getPlaneRole();
        if (role == PlaneRole.AUTO || !HullFacts.isPlaneHull(hull)) return PlaneRole.AUTO;
        return role;
    }

    /** True when the unit's Airplane Mode rules this target out. */
    public static boolean rejects(@Nullable Entity unit, @Nullable LivingEntity target) {
        if (target == null) return false;
        PlaneRole role = roleOf(unit);
        return role != PlaneRole.AUTO && !allows(role, classify(target));
    }
}
