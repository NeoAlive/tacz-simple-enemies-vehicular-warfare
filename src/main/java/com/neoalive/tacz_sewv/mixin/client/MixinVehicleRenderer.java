package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.client.VehicleSkinClient;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * After the stock Geo texture is resolved and before thermal/wreck post-process, swap in a
 * filesystem faction skin when the hull carries a synced sticky paint.
 */
@Mixin(value = VehicleRenderer.class, remap = false)
public abstract class MixinVehicleRenderer {

    @ModifyVariable(method = "getTextureLocation", at = @At("STORE"), ordinal = 0)
    private ResourceLocation tacz_sewv$factionSkin(ResourceLocation original, VehicleEntity animatable) {
        ResourceLocation skin = VehicleSkinClient.textureFor(animatable);
        return skin != null ? skin : original;
    }
}
