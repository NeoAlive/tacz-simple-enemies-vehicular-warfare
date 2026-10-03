package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.HeliTrace;

/**
 * Helicopter physics rework, Phase 0: a probe on the {@code getTerrainCompat()} read inside
 * {@code baseTick}. Nothing else redirects this call (checked over {@code src/main} and every
 * non-SBW jar in {@code libs/}), so Phase 2 turns this into the {@code @Redirect} that keeps
 * SBW's landing-gear alignment off a hull our integrator is flying. Until then it only proves the
 * call site resolves and tells the trace when it ran.
 *
 * <p>{@code baseTick} is vanilla's override (SRG in production), so the method name keeps remap
 * ON; the SBW-owned call target is {@code remap = false}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleFlightDynamics {

    @Inject(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/data/vehicle/DefaultVehicleData;getTerrainCompat()Ljava/util/List;",
                    remap = false))
    private void tacz_sewv$probeTerrainCompat(CallbackInfo ci) {
        HeliTrace.noteTerrainCompat((VehicleEntity) (Object) this);
    }
}
