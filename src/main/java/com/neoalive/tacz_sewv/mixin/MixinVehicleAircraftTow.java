package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;

/**
 * Aircraft must never participate in SBW vehicle tow chains (physics + {@code TowingChainRenderer}).
 * Catapult-shuttle links are preserved — see {@link TowRecoverySupport#scrubIllegalAircraftTow}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleAircraftTow {

    @Inject(method = "towingTick", at = @At("HEAD"), remap = false)
    private void tacz_sewv$scrubAircraftTow(CallbackInfo ci) {
        TowRecoverySupport.scrubIllegalAircraftTow((VehicleEntity) (Object) this);
    }
}
