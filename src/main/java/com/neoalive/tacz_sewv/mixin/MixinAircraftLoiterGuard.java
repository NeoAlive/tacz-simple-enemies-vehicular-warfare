package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.heli.IFlightDynamics;

/**
 * SBW's fixed-wing auto-loiter ({@code VehicleEngineUtils.aircraftLoiter}, called from
 * {@code baseTick} after {@code travel()} when airborne, engine started, energy > 1024, crewed and
 * {@code loiterActive}) writes the hull's attitude itself. While our integrator owns the hull that
 * would be a second writer, and the bridge would read its writes as an outside re-seat every tick,
 * so it is skipped whenever the flight mode is not {@code NONE}.
 */
@Mixin(value = VehicleEngineUtils.class, remap = false)
public abstract class MixinAircraftLoiterGuard {

    @Inject(method = "aircraftLoiter", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tacz_sewv$notWhileOurs(VehicleEntity vehicle, CallbackInfo ci) {
        if (((IFlightDynamics) vehicle).sewv$flightMode() != IFlightDynamics.NONE) {
            ci.cancel();
        }
    }
}
