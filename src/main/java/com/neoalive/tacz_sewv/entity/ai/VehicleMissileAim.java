package com.neoalive.tacz_sewv.entity.ai;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo;
import com.atsuishio.superbwarfare.entity.projectile.Agm65Entity;
import com.atsuishio.superbwarfare.entity.projectile.IglaMissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.JavelinMissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.Kh39Entity;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.projectile.Ru9m336MissileEntity;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.RangeTool;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * AI aim overrides for vehicle weapons whose <b>flight</b> physics do not match SBW's
 * ballistic turret solver.
 *
 * <p>SBW's {@code turretAutoAimFromUuid} always feeds {@code GunProp.GRAVITY} into
 * {@code RangeTool.calculateFiringSolution}. Missile datapacks omit Gravity, so the schema
 * default {@code 0.05} lofts the barrel — fatal for beam-riders ({@link WireGuideMissileEntity}
 * rides {@code getBarrelVector} at gravity 0) and wrong for seekers that home with g=0.
 *
 * <p>Classification uses the <b>selected</b> weapon's projectile entity class, not the hull type
 * and not {@code GunType} (always SPECIAL). Cannon/MG return null so SBW keeps its solver.
 */
public final class VehicleMissileAim {

    public enum AimMode {
        /** Wire-guide ATGM: point straight at the target; no lead. */
        BEAM_RIDER,
        /** Active / IR seeker: zero-gravity lead solution. */
        SEEKER
    }

    private VehicleMissileAim() {}

    /**
     * Aim vector for the selected weapon, or {@code null} when SBW's ballistic path should run.
     * {@code target} should already be the hull if the living target is riding one.
     */
    @Nullable
    public static Vec3 aimOverride(VehicleEntity vehicle, LivingEntity controller, Entity target) {
        AimMode mode = modeOfSelected(vehicle, controller);
        if (mode == null) return null;

        Vec3 shootPos = vehicle.getShootPos(controller, 1.0F);
        Vec3 targetPos = target.getBoundingBox().getCenter();

        if (mode == AimMode.BEAM_RIDER) {
            // Beam tracks the barrel every tick — leading aims at empty air.
            return targetPos.subtract(shootPos);
        }

        // SEEKER: same solver SBW uses, but gravity 0 to match MissileProjectile flight.
        Vec3 targetVel = target.getDeltaMovement();
        double velocity = vehicle.getProjectileVelocity(controller);
        if (velocity <= 0.0) {
            return targetPos.subtract(shootPos);
        }
        try {
            Vec3 solution = RangeTool.calculateFiringSolution(
                    shootPos, targetPos, targetVel, velocity, 0.0);
            if (solution != null && solution.lengthSqr() > 1.0e-8) {
                return solution;
            }
        } catch (Throwable ignored) {
            // Fall through to flat LOS.
        }
        return targetPos.subtract(shootPos);
    }

    @Nullable
    public static AimMode modeOfSelected(VehicleEntity vehicle, LivingEntity controller) {
        try {
            int seat = vehicle.getSeatIndex(controller);
            if (seat < 0) return null;
            int weapon = vehicle.getSelectedWeapon(seat);
            GunData gun = vehicle.getGunData(seat, weapon);
            if (gun == null) return null;
            ProjectileInfo pi = gun.get(GunProp.PROJECTILE);
            if (pi == null) return null;
            return modeOfProjectile(pi.getId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    static AimMode modeOfProjectile(@Nullable String projectileId) {
        if (projectileId == null || projectileId.isEmpty()) return null;

        // Prefer registry class — covers addon subclasses of WireGuide / seekers.
        try {
            ResourceLocation rl = ResourceLocation.tryParse(projectileId);
            if (rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
                if (type != null) {
                    AimMode fromClass = modeOfClass(type.getBaseClass());
                    if (fromClass != null) return fromClass;
                }
            }
        } catch (Throwable ignored) {
            // Headless / unbootstrapped — fall through to id tokens.
        }
        return modeOfProjectileId(projectileId);
    }

    /**
     * Id-token guided detection only (no registry). Safe for headless self-checks.
     * Same needles as the fallback half of {@link #modeOfProjectile}.
     */
    @Nullable
    static AimMode modeOfProjectileId(@Nullable String projectileId) {
        if (projectileId == null || projectileId.isEmpty()) return null;
        String id = projectileId.toLowerCase();
        if (id.contains("wire_guide")) return AimMode.BEAM_RIDER;
        if (id.contains("ru_9m336") || id.contains("igla") || id.contains("javelin")
                || id.contains("agm_65") || id.contains("agm65") || id.contains("kh_39")
                || id.contains("kh39")) {
            return AimMode.SEEKER;
        }
        return null;
    }

    @Nullable
    private static AimMode modeOfClass(Class<?> cls) {
        if (cls == null || cls == Entity.class) return null;
        if (WireGuideMissileEntity.class.isAssignableFrom(cls)) return AimMode.BEAM_RIDER;
        // Known seekers (and any other MissileProjectile that is not wire-guide).
        if (Ru9m336MissileEntity.class.isAssignableFrom(cls)
                || IglaMissileEntity.class.isAssignableFrom(cls)
                || JavelinMissileEntity.class.isAssignableFrom(cls)
                || Agm65Entity.class.isAssignableFrom(cls)
                || Kh39Entity.class.isAssignableFrom(cls)) {
            return AimMode.SEEKER;
        }
        // Generic MissileProjectile that is not WireGuide: treat as seeker (g=0 home),
        // never as ballistic loft. Swarm drones etc. still prefer a near-LOS launch.
        if (MissileProjectile.class.isAssignableFrom(cls)
                && !WireGuideMissileEntity.class.isAssignableFrom(cls)) {
            return AimMode.SEEKER;
        }
        return null;
    }
}
