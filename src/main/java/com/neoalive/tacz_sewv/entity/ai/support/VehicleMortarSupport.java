package com.neoalive.tacz_sewv.entity.ai.support;

import java.util.List;

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;

import com.neoalive.tacz_sewv.bridge.FireMission;
import com.neoalive.tacz_sewv.bridge.IIssuedAmmo;
import com.neoalive.tacz_sewv.bridge.IMortarCrew;
import com.neoalive.tacz_sewv.compat.FcpMortarCompat;

/**
 * Mortar ballistics and magazine reload for crewed <b>vehicle</b> mortars (FCP Hilux /
 * Stryker), as opposed to seatless Fixed {@link com.atsuishio.superbwarfare.entity.vehicle.MortarEntity}
 * tubes owned by {@link MortarSupport}.
 *
 * <p>Boarding is ordinary; aiming uses {@link TrajectoryCalculator} (Fixed-mortar path);
 * firing is owned by {@link ManVehicleMortarGoal} because a lofted tube never passes SBW's
 * look-angle fire gate.
 */
public final class VehicleMortarSupport {

    private VehicleMortarSupport() {}

    /** Whether this unit is the mortar gunner (turret-controller seat) on an FCP mortar hull. */
    public static boolean isCrewing(AbstractUnit unit) {
        if (!(unit.getVehicle() instanceof VehicleEntity hull)) return false;
        if (!FcpMortarCompat.isMortarHull(hull)) return false;
        int seat = hull.getSeatIndex(unit);
        return seat >= 0 && seat == hull.getTurretControllerIndex();
    }

    /**
     * True when any SEM gunner on this hull is laying or about to fire — the driver must park.
     * Checked from {@link com.neoalive.tacz_sewv.entity.ai.goal.DriveVehicleGoal}.
     */
    public static boolean shouldPark(VehicleEntity hull) {
        if (!FcpMortarCompat.isMortarHull(hull)) return false;
        for (Entity passenger : hull.getPassengers()) {
            if (!(passenger instanceof AbstractUnit unit)) continue;
            if (!isCrewing(unit)) continue;
            if (aimpoint(unit) != null) return true;
        }
        return false;
    }

    /** Clear drive inputs so the hull sits still while the tube lays/fires. */
    public static void stopMovement(VehicleEntity hull) {
        hull.setForwardInputDown(false);
        hull.setBackInputDown(false);
        hull.setLeftInputDown(false);
        hull.setRightInputDown(false);
    }

    @Nullable
    public static FireMission fireMissionOf(AbstractUnit unit) {
        if (!(unit instanceof IMortarCrew crew)) return null;
        FireMission mission = crew.sewv$getFireMission();
        if (mission == null) return null;
        if (mission.isExpired(unit.level().getGameTime())) {
            crew.sewv$setFireMission(null);
            return null;
        }
        return mission;
    }

    /** Live target position, else a standing fire-mission mark, else null. */
    @Nullable
    public static Vec3 aimpoint(AbstractUnit unit) {
        LivingEntity target = unit.getTarget();
        if (target != null && target.isAlive()) {
            return target.position();
        }
        FireMission mission = fireMissionOf(unit);
        return mission == null ? null : Vec3.atCenterOf(mission.pos());
    }

    /**
     * Launch vector that puts a shell on {@code aimPos}, or null if unreachable.
     * Same solver and pitch-stop filter as {@link MortarSupport#solveAim}.
     */
    @Nullable
    public static Vec3 solveAim(VehicleEntity hull, LivingEntity gunner, Vec3 aimPos) {
        try {
            Vec3 from = hull.getShootPos(gunner, 1.0F);
            if (from == null) from = hull.getEyePosition();

            List<Vec3> solutions = TrajectoryCalculator.INSTANCE.calculateShootVectors(
                    from, aimPos.add(0.0, -1.0, 0.0),
                    hull.getProjectileVelocity(gunner),
                    hull.getProjectileGravity(gunner));

            for (Vec3 launch : solutions) {
                float pitch = (float) -VehicleVecUtils.getXRotFromVector(launch);
                if (pitch < -hull.getTurretMaxPitch() || pitch > -hull.getTurretMinPitch()) continue;
                return launch;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Lays the tube along {@code launch} via SBW's public turret aim path. */
    public static void aimAt(VehicleEntity hull, Vec3 launch) {
        hull.turretAutoAimFromVector(launch);
    }

    /**
     * Points the gunner along {@code launch} so vanilla entity-rotation sync carries the
     * demand to clients that do not have the target entity loaded (mortar range ≫ tracking).
     * Client aim then follows {@link #aimFromLook} the way players drive the tube.
     */
    public static void faceLaunch(LivingEntity gunner, Vec3 launch) {
        if (launch == null || launch.lengthSqr() < 1.0e-8) return;
        float yaw = (float) -VehicleVecUtils.getYRotFromVector(launch);
        float pitch = (float) -VehicleVecUtils.getXRotFromVector(launch);
        gunner.setYRot(yaw);
        gunner.yRotO = yaw;
        gunner.setYBodyRot(yaw);
        gunner.setYHeadRot(yaw);
        gunner.setXRot(pitch);
        gunner.xRotO = pitch;
    }

    /**
     * Player-shaped turret demand from the gunner's look (bbox centre + look·512). Used on
     * the client when the live target entity is outside tracking range.
     */
    public static void aimFromLook(VehicleEntity hull, LivingEntity gunner) {
        try {
            Vec3 barrelPos = hull.getBarrelPosition();
            if (barrelPos == null) {
                hull.turretAutoAimFromVector(gunner.getViewVector(1.0F));
                return;
            }
            Vec3 aimPos = hull.getBoundingBox().getCenter().add(gunner.getViewVector(1.0F).scale(512.0));
            var transform = hull.getTurretTransform(1.0F);
            var world = com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
                    .transformPosition(transform, barrelPos.x, barrelPos.y, barrelPos.z);
            Vec3 aimVec = new Vec3(world.x, world.y, world.z).vectorTo(aimPos);
            hull.turretAutoAimFromVector(aimVec);
        } catch (Throwable ignored) {
            hull.turretAutoAimFromVector(gunner.getViewVector(1.0F));
        }
    }

    /**
     * Whether the barrel is within {@code toleranceDeg} of the demanded launch vector.
     * Uses the live shoot direction, not stored TARGET_YAW/PITCH (those are Fixed-mortar only).
     */
    public static boolean aimSettled(VehicleEntity hull, LivingEntity gunner, Vec3 launch,
                                     float toleranceDeg) {
        try {
            Vec3 current = hull.getShootVec(gunner, 1.0F);
            if (current == null || launch == null) return false;
            if (current.lengthSqr() < 1.0e-8 || launch.lengthSqr() < 1.0e-8) return false;

            double cos = current.normalize().dot(launch.normalize());
            double angleDeg = Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0, 1.0)));
            return angleDeg <= toleranceDeg;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Tops up the gunner-seat magazine from issued ammo or backup (crew / hull inventory).
     * Same gap SBW leaves for mobs that {@link TowSupport#reload} fills for a TOW.
     */
    public static boolean reload(VehicleEntity hull, AbstractUnit unit) {
        try {
            int seat = hull.getTurretControllerIndex();
            GunData gun = hull.getGunData(seat);
            if (gun == null) return false;

            Entity supplier = hull.getAmmoSupplier();
            Entity ammoEntity = supplier != null ? supplier : hull;

            if (gun.hasEnoughAmmoToShoot(ammoEntity) || gun.hasEnoughAmmoToShoot(unit)) {
                return false;
            }

            boolean issued = hasIssuedShell(gun, unit);
            Entity source;
            if (issued) {
                source = unit;
            } else if (gun.countBackupAmmo(ammoEntity) > 0) {
                source = ammoEntity;
            } else if (gun.countBackupAmmo(unit) > 0) {
                source = unit;
            } else {
                return false;
            }

            hull.modifyGunData(seat, data -> {
                if (issued) data.virtualAmmo.set(data.get(GunProp.MAGAZINE));
                data.reloadAmmo(source);
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasIssuedShell(GunData gun, AbstractUnit unit) {
        if (!(unit instanceof IIssuedAmmo crew)) return false;
        Item issued = crew.sewv$getIssuedAmmo();
        if (issued == null) return false;
        AmmoConsumer consumer = gun.selectedAmmoConsumer();
        return consumer != null && consumer.isAmmoItem(new ItemStack(issued));
    }
}
