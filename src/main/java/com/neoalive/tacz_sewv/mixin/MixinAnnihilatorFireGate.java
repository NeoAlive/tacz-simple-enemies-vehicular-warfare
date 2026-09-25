package com.neoalive.tacz_sewv.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.bridge.IAiFireTracker;

/**
 * {@code AnnihilatorEntity} overrides {@code canShoot} without calling super (and both its
 * {@code vehicleShoot} overloads likewise), so {@link MixinVehicleFireCooldown}'s HEAD injection
 * on {@code VehicleEntity} never runs for it: a crewed Annihilator had no line-of-fire, smoke or
 * CEASE_FIRE gate at all. Its {@code canShoot} is the only gate every caller (SBW's crew loop,
 * the fire-assist path) consults, so gating it here is sufficient.
 */
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.AnnihilatorEntity")
public abstract class MixinAnnihilatorFireGate {

    @Inject(method = "canShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$gateAiFire(LivingEntity living, CallbackInfoReturnable<Boolean> cir) {
        if (((IAiFireTracker) this).tacz_sewv$aiFireDenied(living)) cir.setReturnValue(false);
    }
}
