package com.neoalive.tacz_sewv.mixin.temp;

import com.tacz.guns.client.resource.pojo.display.gun.MuzzleFlash;
import net.nekoyuni.SimpleEnemyMod.entity.client.NpcMuzzleFlashRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY. SEM 0.1.6's {@code NpcMuzzleFlashRenderer.doRender} checks that a gun's display has a muzzle
 * flash but not that the flash has a texture, and passes a null one into {@code RenderType.entityTranslucent},
 * which crashes the client the moment such a gun fires in view. Skip that flash. Delete this class (and its
 * line in {@code tacz_sewv.temp.mixins.json}) once SEM null-checks {@code getTexture()} itself.
 */
@Mixin(value = NpcMuzzleFlashRenderer.class, remap = false)
public abstract class MixinSemMuzzleFlashGuard {

    @Inject(method = "doRender", at = @At("HEAD"), cancellable = true, require = 0)
    private static void tacz_sewv$skipUntexturedFlash(MuzzleFlash muzzleFlash, @Coerce Object data, long elapsed,
                                                      CallbackInfo ci) {
        if (muzzleFlash == null || muzzleFlash.getTexture() == null) ci.cancel();
    }
}
