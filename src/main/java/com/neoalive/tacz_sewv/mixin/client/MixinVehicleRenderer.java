package com.neoalive.tacz_sewv.mixin.client;

import java.util.List;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 *
 * <p>SBW hides {@code *_dogTag_*} bones on purpose (they are placement anchors) and only draws
 * the custom icon overlay when {@code DisplayConfig.DOG_TAG_ICON_VISIBLE} is on — that toggle
 * defaults to false and is documented as a kill-message preference. The single
 * {@code BooleanValue.get()} in {@code render} is that gate; when the hull already carries a
 * non-blank dogTag grid (player item or our PMC stamp), force the overlay on.
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

    /** Only {@code BooleanValue.get()} in {@code GeoVehicleRenderer.render} is the dogTag gate. */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/common/ForgeConfigSpec$BooleanValue;get()Ljava/lang/Object;",
                    remap = false
            ),
            remap = false
    )
    private Object tacz_sewv$forceDogTagIconVisible(ForgeConfigSpec.BooleanValue value) {
        VehicleEntity hull = tacz_sewv$rendering.get();
        if (hull != null && tacz_sewv$hasDogTagArt(hull)) {
            return Boolean.TRUE;
        }
        return value.get();
    }

    @Unique
    private static boolean tacz_sewv$hasDogTagArt(VehicleEntity hull) {
        List<List<Short>> grid = hull.getDogTagIcon();
        if (grid == null || grid.isEmpty()) return false;
        for (List<Short> col : grid) {
            if (col == null) continue;
            for (Short s : col) {
                if (s != null && s != -1) return true;
            }
        }
        return false;
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
