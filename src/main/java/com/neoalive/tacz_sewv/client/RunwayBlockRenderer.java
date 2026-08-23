package com.neoalive.tacz_sewv.client;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.block.RunwayBlockEntity;

/**
 * Draws the radar mast with Simple Bedrock Model, replacing the old GeckoLib block renderer. The
 * 1×1 cube stays a baked model so neighbouring faces still cull against it; this renderer is only
 * the bits that stick out, and the spin only advances while this is actually submitted — a
 * frustum miss freezes it.
 */
public class RunwayBlockRenderer implements BlockEntityRenderer<RunwayBlockEntity> {

    public RunwayBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RunwayBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModelInstance instance = RunwayBlockClient.instance(be);
        if (instance == null) return;

        poseStack.pushPose();
        // Bedrock models bake centred on the block origin; same anchor the old GeoBlockRenderer used.
        poseStack.translate(0.5D, 0.0D, 0.5D);

        instance.resetPose();
        if (be.hasCachedAirport()) {
            var bone = instance.getBone("spinning_radar");
            if (bone != null) {
                bone.rotation.mul(Axis.YP.rotationDegrees(RunwayBlockClient.spinAngleDeg(be)));
            }
        }

        // Light resampled two blocks above the cube (where the mast actually sits), so the dish is
        // not shaded by its own base — a real world lightmap, not full-bright/emissive.
        BlockPos pos = be.getBlockPos();
        int light = be.getLevel() != null
                ? LevelRenderer.getLightColor(be.getLevel(), pos.above(2))
                : packedLight;
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(RunwayBlockClient.TEXTURE));
        instance.renderToBuffer(poseStack, vc, light, packedOverlay);
        poseStack.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(RunwayBlockEntity animatable) {
        return false;
    }
}
