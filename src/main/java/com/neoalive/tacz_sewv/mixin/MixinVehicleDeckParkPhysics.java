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
 * While Neo Arms {@code DeckPark} is set, SBW must not apply gravity + {@code move} —
 * the carrier deck is Body OBBs, not blocks, so {@code onGround} never sticks and the
 * plane falls through. Also skip {@code travel()} so aircraftEngine cannot shove the hull.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleDeckParkPhysics {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipTravelWhileDeckParked(CallbackInfo ci) {
        if (!NeoArmsCompat.present()) {
            return;
        }
        VehicleEntity self = (VehicleEntity) (Object) this;
        if (NeoArmsCarrierAccess.isDeckParked(self)) {
            self.setDeltaMovement(Vec3.ZERO);
            self.setDeltaMovementO(Vec3.ZERO);
            ci.cancel();
        }
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

    /**
     * VehicleEntity overrides {@code move}; cancel while deck-parked so baseTick's
     * gravity integration cannot sink the hull through OBB deck.
     */
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
