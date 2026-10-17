package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.compat.NeoArmsCompat;

/**
 * SBW {@code preventStacking} AABB-bounces overlapping vehicles. Skip it near a Neo Arms
 * carrier — uses the carrier loaded-set distance check, not a per-vehicle entity scan.
 */
@Mixin(VehicleEntity.class)
public abstract class MixinVehicleCarrierDeckStacking {

    @Inject(method = "preventStacking", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipCarrierDeckStacking(CallbackInfo ci) {
        if (!NeoArmsCompat.present()) {
            return;
        }
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (NeoArmsCarrierAccess.isCarrier(self) || NeoArmsCarrierAccess.isCarrierParked(self)) {
            ci.cancel();
            return;
        }
        // Cheap O(carriers) distance — only skip stacking when actually near a carrier.
        if (NeoArmsCarrierAccess.isNearCarrier(self, 48.0)) {
            ci.cancel();
        }
    }
}
