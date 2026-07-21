package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.HealthMobility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slows an AI-crewed vehicle's turret slew as it loses health. Both getters feed
 * {@code VehicleWeaponUtils.turretAutoAimFromVector}, which clamps the per-tick turret rotation to
 * them, so scaling the returned value scales the slew rate directly. SBW's own {@code TURRET_DAMAGED}
 * x0.2 (a knocked-out turret) is separate and still applies on top.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleTurretMobility {

    @Inject(method = {"getTurretTurnXSpeed", "getTurretTurnYSpeed"},
            at = @At("RETURN"), cancellable = true, remap = false)
    private void tacz_sewv$slowTurret(CallbackInfoReturnable<Float> cir) {
        float mult = HealthMobility.multiplier((VehicleEntity) (Object) this);
        if (mult < 1.0f) cir.setReturnValue(cir.getReturnValue() * mult);
    }
}
