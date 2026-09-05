package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.sensor.AwarenessCues;

/**
 * Track-engine loops are client-only; wheel steps are intermittent. Register throttled cues from
 * server movement: ground hulls as {@code VEHICLE_ENGINE}, drones (incl. vertical) as
 * {@code DRONE}.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleAwarenessEngine {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$registerEngineCue(CallbackInfo ci) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (vehicle.level().isClientSide()) return;
        if (!(vehicle.level() instanceof ServerLevel level)) return;

        if (isDroneHull(vehicle)) {
            // Hover / climb must count — horizontal-only would miss loitering recon drones.
            if (vehicle.getDeltaMovement().lengthSqr() <= 0.0025) return;
            AwarenessCues.registerDrone(level, vehicle);
            return;
        }

        if (vehicle.getDeltaMovement().horizontalDistanceSqr() <= 0.01) return;
        AwarenessCues.registerEngine(level, vehicle);
    }

    private static boolean isDroneHull(VehicleEntity vehicle) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        if (id == null || !"superbwarfare".equals(id.getNamespace())) return false;
        String path = id.getPath();
        return path.equals("drone") || path.contains("drone");
    }
}
