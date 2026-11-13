package com.neoalive.tacz_sewv.mixin;

import com.atsuishio.superbwarfare.entity.projectile.Blu43Entity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.entity.ai.support.mines.MineFriends;

/**
 * Per-candidate trigger filter of SBW's BLU-43. A mine laid by the player's engineers does not go off under the
 * owner's own side. Kotlin lambda name, hence remap = false and require = 0.
 */
@Mixin(Blu43Entity.class)
public abstract class MixinBlu43FriendlyFilter {

    @Inject(method = "touchEntity$lambda$2", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$spareFriendly(Blu43Entity self, Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (MineFriends.spares(self, e)) cir.setReturnValue(false);
    }
}
