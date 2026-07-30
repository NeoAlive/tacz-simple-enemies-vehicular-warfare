package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import com.neoalive.tacz_sewv.compat.FcpMortarCompat;
import com.neoalive.tacz_sewv.entity.ai.VehicleMissileAim;
import com.neoalive.tacz_sewv.entity.ai.VehicleMortarSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Corrects AI turret aim for selected beam-rider / seeker missiles, and for FCP mortar
 * vehicles replaces SBW's RangeTool loft with TrajectoryCalculator-grade aim.
 *
 * <p>{@code VehicleEntity.tick} hands an AI-crewed turret to {@code turretAutoAimFromUuid},
 * which solves a BALLISTIC firing solution from {@code GunProp.GRAVITY}. Missile datapacks
 * omit Gravity (schema default 0.05) while flight is gravity 0 — wire-guide ATGMs ride the
 * lofted barrel over the target; seekers also launch on the wrong line. See
 * {@link VehicleMissileAim}.
 *
 * <p>FCP mortar hulls need the same hook for a different reason: {@code turretXRot}/
 * {@code turretYRot} are <b>not</b> synched entity data. Players drive them from look on
 * both sides via {@code adjustTurretAngle}; a server-only goal left the client tube frozen
 * while the server still fired. Aiming here (client + server, every vehicle tick) matches
 * the missile pattern. When the target entity is outside client tracking (mortar range),
 * the gunner's synced look — pointed at the solution on the server — drives the tube via
 * {@link VehicleMortarSupport#aimFromLook}.
 *
 * <p>Hooked here rather than from a goal so there is one writer per tick on both sides. Only
 * {@link AbstractUnit} controllers are touched.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleMissileAim {

    @Inject(
            method = "turretAutoAimFromUuid(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$aimMissileByPhysics(
            String targetUuid, LivingEntity controller, CallbackInfo ci) {

        if (!(controller instanceof AbstractUnit)) return;
        VehicleEntity vehicle = (VehicleEntity) (Object) this;

        if (FcpMortarCompat.isMortarHull(vehicle)) {
            tacz_sewv$aimFcpMortar(vehicle, controller, targetUuid);
            ci.cancel();
            return;
        }

        Entity target = EntityFindUtil.findEntity(vehicle.level(), targetUuid);
        // Nothing to aim at — fall through so SBW's own null bail stays the single path.
        if (target == null) return;

        // A target riding a hull means the hull, the way SBW resolves it.
        if (target.getVehicle() != null) target = target.getVehicle();

        Vec3 aim = VehicleMissileAim.aimOverride(vehicle, controller, target);
        if (aim == null) return; // ballistic / unknown weapon — leave SBW alone

        vehicle.turretAutoAimFromVector(aim);
        ci.cancel();
    }

    private static void tacz_sewv$aimFcpMortar(
            VehicleEntity vehicle, LivingEntity controller, String targetUuid) {
        Entity target = EntityFindUtil.findEntity(vehicle.level(), targetUuid);
        if (target != null) {
            if (target.getVehicle() != null) target = target.getVehicle();
            Vec3 launch = VehicleMortarSupport.solveAim(vehicle, controller, target.position());
            if (launch != null) {
                VehicleMortarSupport.faceLaunch(controller, launch);
                vehicle.turretAutoAimFromVector(launch);
                return;
            }
        }
        // No local target entity (common at mortar range on the client): follow gunner look,
        // which the server keeps pointed at the ballistic solution.
        if (!"undefined".equals(targetUuid) && targetUuid != null && !targetUuid.isEmpty()) {
            VehicleMortarSupport.aimFromLook(vehicle, controller);
        }
    }
}
