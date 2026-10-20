package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.DogTagLighting;

/**
 * Draws SBW's dog-tag overlay vertex itself, with the light sampled at the decal. The other
 * {@code vertex} overload hardcodes full-bright for lasers and is not this target.
 *
 * <p>NeoForge port: SBW 1.21 folded this helper into the laser one's shape, {@code (x, 0, -y)},
 * which lays the decal flat in the bone's XZ plane. 1.20 drew it upright at
 * {@code (x - 0.5, y - 0.25, 0)}, and that is the placement {@link DogTagLighting} samples, so
 * the 1.20 vertex is restored here rather than only its light.
 */
@Mixin(value = GeoVehicleRenderer.Companion.class, remap = false)
public abstract class MixinDogTagLighting {

    @Inject(
            method = "vertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;IFFII)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void tacz_sewv$decalVertex(VertexConsumer consumer, Matrix4f pose, PoseStack.Pose normal,
            int packedLight, float x, float y, int u, int v, CallbackInfo ci) {
        consumer.addVertex(pose, x - 0.5F, y - 0.25F, 0.0F)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(DogTagLighting.atDecal(pose, packedLight))
                .setNormal(normal, 0.0F, 1.0F, 0.0F);
        ci.cancel();
    }
}
