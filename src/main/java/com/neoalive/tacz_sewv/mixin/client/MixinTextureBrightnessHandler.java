package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.TextureBrightnessHandler;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;

/**
 * Sewv faction skins are TextureManager-only {@code DynamicTexture}s. SBW's wreck brightener
 * reads via {@code ResourceManager.getResource(...).orElseThrow()} and {@code printStackTrace()}
 * on every miss — once per frame per painted hull. Skip that path for our dynamic ids.
 */
@Mixin(value = TextureBrightnessHandler.class, remap = false)
public abstract class MixinTextureBrightnessHandler {

    @Inject(method = "getBrightenedTexture", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$dynamicSafe(ResourceLocation original, float multiplier,
            CallbackInfoReturnable<ResourceLocation> cir) {
        if (!isSewvDynamic(original)) return;
        if (multiplier < 1.0F) {
            cir.setReturnValue(VehicleSkinRegistry.darkened(original, multiplier));
        } else {
            cir.setReturnValue(original);
        }
    }

    private static boolean isSewvDynamic(ResourceLocation loc) {
        return loc != null
                && TaczSewv.MODID.equals(loc.getNamespace())
                && loc.getPath().startsWith("dynamic/");
    }
}
