package com.neoalive.tacz_sewv.mixin.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockCubePerFace;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.perf.TaczCubeEmitter;
import com.neoalive.tacz_sewv.config.PerfFlags;

/** TaCZ cube vertex emission through {@link TaczCubeEmitter}: same vertices, no allocations. */
@Mixin(value = {BedrockCubePerFace.class, BedrockCubeBox.class}, remap = false)
public abstract class MixinTaczCubeCompile {

    @Unique
    private static final TaczCubeEmitter TACZ_SEWV$EMITTER = new TaczCubeEmitter();

    @Shadow(remap = false)
    @Final
    private BedrockPolygon[] polygons;

    @Inject(
            method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void tacz_sewv$fastCompile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
                                       float red, float green, float blue, float alpha, CallbackInfo ci) {
        if (!PerfFlags.taczFastCubeEmit() || !RenderSystem.isOnRenderThread()) return;
        TACZ_SEWV$EMITTER.emit(polygons, pose, consumer, light, overlay, red, green, blue, alpha);
        ci.cancel();
    }
}
