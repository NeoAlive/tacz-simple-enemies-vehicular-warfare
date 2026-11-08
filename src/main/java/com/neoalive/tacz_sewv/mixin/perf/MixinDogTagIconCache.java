package com.neoalive.tacz_sewv.mixin.perf;

import java.util.List;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.tools.SpritePixelHelper;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.neoalive.tacz_sewv.client.perf.DogTagTextureCache;

/** Routes SBW's per-frame dog tag texture rebuild through {@link DogTagTextureCache}. */
@Mixin(value = {GeoVehicleRenderer.class, VehicleRenderer.class}, remap = false)
public abstract class MixinDogTagIconCache {

    private static final String GET_DOG_TAG_ICON =
            "Lcom/atsuishio/superbwarfare/tools/SpritePixelHelper;getDogTagIcon(Ljava/util/List;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;";

    // A bare name only selects the first overload and both targets have generic bridges, so match
    // every overload; only the real one holds the call.
    @SuppressWarnings("unchecked")
    @Redirect(
            method = {"render*", "renderRecursively*"},
            at = @At(value = "INVOKE", target = GET_DOG_TAG_ICON, remap = false),
            remap = false,
            require = 0)
    private ResourceLocation tacz_sewv$cachedDogTagIcon(SpritePixelHelper helper, List<?> grid, String path) {
        return DogTagTextureCache.icon(helper, (List<List<Short>>) grid, path);
    }
}
