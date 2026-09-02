package com.neoalive.tacz_sewv.client;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;

public class FobBlockRenderer implements BlockEntityRenderer<BlockEntity> {

    public FobBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        FobBlockClient.Kind kind = FobBlockClient.kindFor(be.getBlockState());
        if (kind == null) return;
        BakedModelInstance instance = FobBlockClient.instance(be);
        if (instance == null) return;

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        instance.resetPose();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(FobBlockClient.texture(kind)));
        instance.renderToBuffer(poseStack, vc, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
