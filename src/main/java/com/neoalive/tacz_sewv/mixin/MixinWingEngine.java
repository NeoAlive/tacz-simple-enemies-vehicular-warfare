package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.IFlightDynamics;
import com.neoalive.tacz_sewv.wing.WingFlight;

/**
 * Hands an AI-crewed fixed-wing hull to our flight stack: HEAD of SBW's {@code aircraftEngine}
 * (a static on {@code VehicleEngineUtils}, javap-verified; {@code tom_6} runs {@code tomEngine}
 * instead and is never seen here), cancelled when we fly the hull. Same shape as
 * {@code MixinHeliEngine}, and like it runs on both sides because SBW runs the engine on both.
 *
 * <p>The flight mode is written on every call, i.e. every tick inside {@code travel()}, before the
 * gravity / terrain-compat / inertia reads and the loiter call later in the same {@code baseTick},
 * all of which are gated on that mode.
 */
@Mixin(value = VehicleEngineUtils.class, remap = false)
public abstract class MixinWingEngine {

    @Inject(method = "aircraftEngine", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$flyOurs(VehicleEntity vehicle, EngineInfo.Aircraft engine, CallbackInfo ci) {
        int mode = vehicle.level().isClientSide ? WingFlight.clientTick(vehicle, engine) : WingFlight.serverTick(vehicle);
        ((IFlightDynamics) vehicle).sewv$setFlightMode(mode);
        if (mode != IFlightDynamics.NONE) {
            ci.cancel();
        }
    }
}
