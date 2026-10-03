package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.HeliFlight;
import com.neoalive.tacz_sewv.heli.HeliTrace;
import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * Hands an AI-crewed helicopter to our flight stack: HEAD of SBW's {@code helicopterEngine}
 * (a static on {@code VehicleEngineUtils} itself, javap-verified), cancelled when we fly the hull.
 *
 * <p>Runs on both sides, because SBW runs the engine on both. Server: {@link HeliFlight#serverTick}
 * integrates and publishes. Client: no physics, only the rotor visual and the synced roll. The
 * flight mode is written on every call, i.e. every tick inside {@code travel()}, before the gravity
 * and terrain-compat reads later in the same {@code baseTick} that depend on it.
 *
 * <p>The Phase 0 trace stays attached to both models: a cancelled call is recorded as
 * {@code sewv}, SBW's own run (RETURN, every exit of the Kotlin body) as {@code sbw}.
 */
@Mixin(value = VehicleEngineUtils.class, remap = false)
public abstract class MixinHeliEngine {

    @Inject(method = "helicopterEngine", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$flyOurs(VehicleEntity vehicle, EngineInfo.Helicopter engine, CallbackInfo ci) {
        HeliTrace.head(vehicle);
        int mode = vehicle.level().isClientSide ? HeliFlight.clientTick(vehicle) : HeliFlight.serverTick(vehicle);
        ((IFlightDynamics) vehicle).sewv$setFlightMode(mode);
        if (mode != IFlightDynamics.NONE) {
            HeliTrace.ret(vehicle, "sewv");
            ci.cancel();
        }
    }

    @Inject(method = "helicopterEngine", at = @At("RETURN"), remap = false)
    private static void tacz_sewv$traceSbw(VehicleEntity vehicle, EngineInfo.Helicopter engine, CallbackInfo ci) {
        HeliTrace.ret(vehicle, "sbw");
    }
}
