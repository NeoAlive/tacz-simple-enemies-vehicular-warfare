package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;

/**
 * Track-engine loop sounds are client-only; wheel step sounds only fire intermittently. Register a
 * throttled engine cue from server movement so idle crews can hear nearby armour.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleAwarenessEngine {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$registerEngineCue(CallbackInfo ci) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (vehicle.level().isClientSide()) return;
        if (!(vehicle.level() instanceof ServerLevel level)) return;
        if (vehicle.getDeltaMovement().horizontalDistanceSqr() <= 0.01) return;
        AwarenessCues.registerEngine(level, vehicle);
    }
}
