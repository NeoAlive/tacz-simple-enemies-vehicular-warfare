package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.support.TowRecoverySupport;

/**
 * Server-side tow link hygiene, once per hull tick, ahead of SBW's tow physics. Aircraft must never
 * participate in SBW vehicle tow chains (physics + {@code TowingChainRenderer}); catapult-shuttle links
 * are preserved — see {@link TowRecoverySupport#scrubIllegalAircraftTow}. An AI tower drops a link with no
 * live order behind it or to a wrecked hull — see {@link TowRecoverySupport#scrubStaleAiTow}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleAircraftTow {

    @Inject(method = "towingTick", at = @At("HEAD"), remap = false)
    private void tacz_sewv$scrubAircraftTow(CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        // Cheapest test first: almost no hull is in a tow link, and the aircraft test re-runs computed().
        if (hull.level().isClientSide || (!hull.isTowingAny() && hull.getTowedByUUID().isBlank())) return;
        TowRecoverySupport.scrubIllegalAircraftTow(hull);
        TowRecoverySupport.scrubStaleAiTow(hull);
    }
}
