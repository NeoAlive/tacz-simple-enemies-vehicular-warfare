package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.HeliTrace;

/**
 * Phase 0 of the helicopter physics rework: records SBW's {@code helicopterEngine} input and
 * output for traced hulls ({@link HeliTrace}). It also proves the target resolves.
 * {@code helicopterEngine} is a static on {@code VehicleEngineUtils} itself (javap-verified, not a
 * {@code ...Kt} file-class), and {@code defaultRequire: 1} turns a moved target into a load-time
 * failure instead of a silent no-op.
 *
 * <p>RETURN rather than TAIL so every exit of the Kotlin body is seen, including the early
 * no-energy one. Phase 2 replaces the HEAD half with the cancel that hands the hull to our
 * integrator.
 */
@Mixin(value = VehicleEngineUtils.class, remap = false)
public abstract class MixinHeliEngineTrace {

    @Inject(method = "helicopterEngine", at = @At("HEAD"), remap = false)
    private static void tacz_sewv$traceHead(VehicleEntity vehicle, EngineInfo.Helicopter engine, CallbackInfo ci) {
        HeliTrace.head(vehicle);
    }

    @Inject(method = "helicopterEngine", at = @At("RETURN"), remap = false)
    private static void tacz_sewv$traceReturn(VehicleEntity vehicle, EngineInfo.Helicopter engine, CallbackInfo ci) {
        HeliTrace.ret(vehicle);
    }
}
