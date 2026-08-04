package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.client.VehicleSkinClient;
import com.neoalive.tacz_sewv.client.VehicleSkinRegistry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swap in a filesystem faction skin after SBW's getTextureLocation finishes.
 *
 * <p>Must be RETURN, not a mid-method rewrite of {@code res}: SBW's wreck/thermal brighteners
 * read via {@code ResourceManager.getResource(...).orElseThrow()}, and our skins are
 * TextureManager-only DynamicTextures — feeding them in earlier spam-crashes every frame on
 * wrecks (caught + printStackTrace, still lethal to the log).
 */
@Mixin(value = VehicleRenderer.class, remap = false)
public abstract class MixinVehicleRenderer {

    @Inject(method = "getTextureLocation", at = @At("RETURN"), cancellable = true)
    private void tacz_sewv$factionSkin(VehicleEntity animatable, CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation skin = VehicleSkinClient.textureFor(animatable);
        if (skin == null) return;

        // Wreck darkening without ResourceManager: multiply DynamicTexture pixels locally.
        if (animatable.isWreck()) {
            cir.setReturnValue(VehicleSkinRegistry.darkened(skin, 0.3F));
        } else {
            cir.setReturnValue(skin);
        }
    }
}
