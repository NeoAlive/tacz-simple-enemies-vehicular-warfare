package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.SmartTextureBrightener;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Same DynamicTexture hole as {@link MixinTextureBrightnessHandler}: thermal brightening must
 * not {@code orElseThrow} on sewv skins every frame.
 */
@Mixin(value = SmartTextureBrightener.class, remap = false)
public abstract class MixinSmartTextureBrightener {

    @Inject(method = "getSmartBrightenedTexture", at = @At("HEAD"), cancellable = true)
    private static void tacz_sewv$dynamicSafe(ResourceLocation original, float targetBrightness,
            CallbackInfoReturnable<ResourceLocation> cir) {
        if (original != null
                && TaczSewv.MODID.equals(original.getNamespace())
                && original.getPath().startsWith("dynamic/")) {
            cir.setReturnValue(original);
        }
    }
}
