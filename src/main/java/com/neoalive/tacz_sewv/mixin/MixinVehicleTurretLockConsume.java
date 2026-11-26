package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes SBW's {@code turretYRotLock} a one tick value. It is the main turret's last yaw step, and its
 * only reader is the empty cupola step in {@code baseTick}, which adds it to {@code gunYRot} every tick.
 * SBW clears it only when the turret seat is empty, so under a mob it stays latched: turret writers that
 * step every other tick gave the cupola each step twice, and a write left mid slew spun an empty cupola
 * until the next one. Player and SBW writers set it every tick before the read, so they are unaffected;
 * a goal writes after the vehicle ticks, so its step is used once, on the next tick. Both sides on
 * purpose: the client only gets the lock in the spawn packet.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity")
public abstract class MixinVehicleTurretLockConsume {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void tacz_sewv$consumeTurretLock(CallbackInfo ci) {
        VehicleEntity hull = (VehicleEntity) (Object) this;
        if (hull.getTurretYRotLock() != 0.0F) hull.setTurretYRotLock(0.0F);
    }
}
