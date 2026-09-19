package com.neoalive.tacz_sewv.client;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory / hand / ground rendering of the FOB blocks. Vanilla has already applied the item
 * model's {@code display} transform and shifted to the model-cube corner by the time this runs,
 * so it only has to do what {@link FobBlockRenderer} does: centre on X/Z and draw the geo.
 */
public class FobItemRenderer extends BlockEntityWithoutLevelRenderer {

    public FobItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet models) {
        super(dispatcher, models);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof BlockItem item)) return;
        FobBlockClient.Kind kind = FobBlockClient.kindFor(item.getBlock().defaultBlockState());
        if (kind == null) return;
        BakedModelInstance instance = FobBlockClient.itemInstance(kind);
        if (instance == null) return;

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.0D, 0.5D);
        instance.resetPose();
        instance.renderToBuffer(poseStack,
                buffer.getBuffer(RenderType.entityCutout(FobBlockClient.texture(kind))),
                packedLight, packedOverlay);
        poseStack.popPose();
    }
}
