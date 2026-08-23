package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.client.skin.VehicleSkinClient;
import com.neoalive.tacz_sewv.client.skin.VehicleSkinRegistry;

/**
 * Swap in a filesystem faction skin on SBW's SBM vehicle path ({@link GeoVehicleRenderer}).
 *
 * <p>0.8.9.1 retired the GeckoLib {@code VehicleRenderer}: hulls render through
 * {@code entry.texture} inside {@code render}, with a late {@code getTextureLocation} only for
 * a secondary pass. Both sites are covered. Sewv's sticky paint wins over SBW's native
 * {@code skinId} datapack skins when a faction PNG exists — we do not write {@code skinId}.
 *
 * <p>Wreck darkening multiplies the DynamicTexture locally: SBW's brightener reads via
 * {@code ResourceManager} and would crash on TextureManager-only skins.
 *
 * <p>{@code @ModifyArg} can only see the invoke's own args, so the hull is stashed for the
 * duration of {@code render} rather than listed on the arg handlers.
 */
@Mixin(value = GeoVehicleRenderer.class, remap = false)
public abstract class MixinVehicleRenderer {

    @Unique
    private static final ThreadLocal<VehicleEntity> tacz_sewv$rendering = new ThreadLocal<>();

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void tacz_sewv$captureHull(VehicleEntity entity, float yaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        tacz_sewv$rendering.set(entity);
    }

    @Inject(method = "render", at = @At("RETURN"), remap = false)
    private void tacz_sewv$clearHull(CallbackInfo ci) {
        tacz_sewv$rendering.remove();
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;entityTranslucent(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;",
                    ordinal = 0,
                    remap = true
            ),
            index = 0,
            remap = false
    )
    private ResourceLocation tacz_sewv$factionSkinTranslucent(ResourceLocation texture) {
        return resolve(texture, tacz_sewv$rendering.get());
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/mcmodderanchor/simplebedrockmodel/v1/client/renderer/BedrockModelRenderTypes;polyMeshCutout(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;",
                    ordinal = 0,
                    remap = false
            ),
            index = 0,
            remap = false
    )
    private ResourceLocation tacz_sewv$factionSkinCutout(ResourceLocation texture) {
        return resolve(texture, tacz_sewv$rendering.get());
    }

    @Inject(method = "getTextureLocation", at = @At("RETURN"), cancellable = true, remap = false)
    private void tacz_sewv$factionSkinLocation(VehicleEntity animatable,
            CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation skin = resolve(null, animatable);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }

    /** {@code fallback} kept when no sticky paint; {@code null} means "no skin → leave alone". */
    @Unique
    private static ResourceLocation resolve(ResourceLocation fallback, VehicleEntity entity) {
        if (entity == null) {
            return fallback;
        }
        ResourceLocation skin = VehicleSkinClient.textureFor(entity);
        if (skin == null) {
            return fallback;
        }
        if (entity.isWreck()) {
            return VehicleSkinRegistry.darkened(skin, 0.3F);
        }
        return skin;
    }
}
