package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.compat.NeoArmsCompat;

/**
 * While Neo Arms {@code DeckPark} is set, SBW must not run {@code travel}/{@code move}/gravity.
 * Allowing {@code aircraftEngine} on a Body-OBB deck clears {@code onGround} and pitches empty
 * hulls (+0.1°/tick) or lets a boarded plane treat the pad as airborne (nose-down circles).
 * Neo Arms floor-clamp + pose drag own the stood pose instead.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleDeckParkPhysics {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipTravelWhileDeckParked(CallbackInfo ci) {
        if (!NeoArmsCompat.present()) {
            return;
        }
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!NeoArmsCarrierAccess.isDeckParked(self)) {
            return;
        }
        self.setDeltaMovement(Vec3.ZERO);
        self.setDeltaMovementO(Vec3.ZERO);
        self.setXRot(0.0F);
        self.setZRot(0.0F);
        self.setOnGround(true);
        self.fallDistance = 0f;
        ci.cancel();
    }

    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/atsuishio/superbwarfare/data/vehicle/DefaultVehicleData;getGravity()D",
                    remap = false))
    private double tacz_sewv$zeroGravityWhileDeckParked(DefaultVehicleData data) {
        if (NeoArmsCompat.present()
                && NeoArmsCarrierAccess.isDeckParked((VehicleEntity) (Object) this)) {
            return 0.0;
        }
        return data.getGravity();
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$skipMoveWhileDeckParked(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (!NeoArmsCompat.present()) {
            return;
        }
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (!NeoArmsCarrierAccess.isDeckParked(self)) {
            return;
        }
        self.setDeltaMovement(Vec3.ZERO);
        self.setDeltaMovementO(Vec3.ZERO);
        self.setOnGround(true);
        self.fallDistance = 0f;
        ci.cancel();
    }
}
