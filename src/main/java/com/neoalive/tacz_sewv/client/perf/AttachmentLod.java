package com.neoalive.tacz_sewv.client.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemDisplayContext;

import com.neoalive.tacz_sewv.config.PerfFlags;

/** Far third-person TaCZ attachments are not drawn. The gun body and its pose are untouched. */
public final class AttachmentLod {

    private AttachmentLod() {}

    public static boolean skips(PoseStack poseStack, ItemDisplayContext context) {
        if (!ThirdPersonGunFlush.isWorldContext(context)) return false;
        return CameraDistance.viewSpaceBeyond(poseStack.last().pose(), PerfFlags.semAttachmentLodDistance());
    }
}
