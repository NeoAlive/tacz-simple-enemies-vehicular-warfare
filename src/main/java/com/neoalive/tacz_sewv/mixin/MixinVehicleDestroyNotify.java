package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.notify.HudNotify;

/** PMC-owned hull loss toast at the SBW destroy seam (vehicles are not LivingEntities). */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleDestroyNotify {

    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void tacz_sewv$notifyDestroy(CallbackInfo ci) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (self.level().isClientSide()) return;
        HudNotify.vehicleDestroyed(self);
    }
}
