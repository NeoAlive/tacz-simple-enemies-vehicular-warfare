package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.entity.GeoVehicleRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.neoalive.tacz_sewv.client.DogTagLighting;

/**
 * Replaces the packed light on SBW's dog-tag overlay quad. The other {@code vertex} overload
 * hardcodes full-bright for lasers and is not this target.
 *
 * <p>The {@link VertexConsumer} parameter is the target method's first argument, so {@code pose}
 * is the bone matrix the quad is drawn with.
 */
@Mixin(value = GeoVehicleRenderer.Companion.class, remap = false)
public abstract class MixinDogTagLighting {

    @ModifyVariable(
            // NeoForge port: SBW 1.21 passes the PoseStack.Pose where 1.20 passed the normal Matrix3f;
            // packedLight is still the first int.
            method = "vertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;IFFII)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private int tacz_sewv$decalLight(int packedLight, VertexConsumer consumer, Matrix4f pose) {
        return DogTagLighting.atDecal(pose, packedLight);
    }
}
