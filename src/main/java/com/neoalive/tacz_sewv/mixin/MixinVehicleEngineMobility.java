package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.util.HealthMobility;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Slows an AI-crewed vehicle's drive speed as it loses health.
 *
 * <p>Both engine functions apply thrust the same way in each of their movement branches:
 * {@code deltaMovement = deltaMovement.add(getViewVector(1f).scale(<0.03|0.15> * targetSpeed * power))}.
 * The scalar there is the whole drive contribution, so redirecting the {@code Vec3.scale} that builds
 * it — at every branch — scales the tank's speed by the health multiplier. Nothing else on the hull
 * is a {@code getViewVector().scale()}, and at full health the multiplier is 1.0, so there is no
 * change for an undamaged or player-driven vehicle. The receiver arg is unused; the vehicle comes in
 * as the engine function's first (captured) parameter.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils")
public abstract class MixinVehicleEngineMobility {

    @Redirect(method = "trackEngine",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 tacz_sewv$slowTrack(Vec3 view, double factor, VehicleEntity vehicle, EngineInfo.Track engine) {
        return view.scale(factor * HealthMobility.multiplier(vehicle));
    }

    @Redirect(method = "wheelEngine",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;scale(D)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 tacz_sewv$slowWheel(Vec3 view, double factor, VehicleEntity vehicle, EngineInfo.Wheel engine) {
        return view.scale(factor * HealthMobility.multiplier(vehicle));
    }
}
