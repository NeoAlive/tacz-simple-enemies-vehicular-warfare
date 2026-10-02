package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.support.GrenadeBail;

/**
 * Frags share {@link FastThrowableProjectile#tick}. Hand grenades reach it through
 * {@code BounceProjectile}; RGO calls it directly. Rockets hit this too and are ignored.
 */
@Mixin(FastThrowableProjectile.class)
public abstract class MixinGrenadeBail {

    @Inject(method = "tick", at = @At("TAIL"))
    private void tacz_sewv$bailFromFrag(CallbackInfo ci) {
        GrenadeBail.warn((FastThrowableProjectile) (Object) this);
    }
}
