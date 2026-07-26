package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import com.neoalive.tacz_sewv.entity.ai.VehicleMissileAim;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Corrects AI turret aim for selected beam-rider / seeker missiles.
 *
 * <p>{@code VehicleEntity.tick} hands an AI-crewed turret to {@code turretAutoAimFromUuid},
 * which solves a BALLISTIC firing solution from {@code GunProp.GRAVITY}. Missile datapacks
 * omit Gravity (schema default 0.05) while flight is gravity 0 — wire-guide ATGMs ride the
 * lofted barrel over the target; seekers also launch on the wrong line. See
 * {@link VehicleMissileAim}.
 *
 * <p>Gated on the <b>selected weapon's projectile</b>, not the hull type: a Bradley on Cannon
 * keeps SBW's solver; the same hull on Missile gets a flat / zero-g aim. Replaces the old
 * TowEntity-only special case.
 *
 * <p>Hooked here rather than from a goal so there is one writer per tick. Only
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
}
