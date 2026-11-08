package com.neoalive.tacz_sewv.mixin.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.functional.AttachmentRender;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.perf.AttachmentLod;

/** Every TaCZ attachment draw, scopes included, goes through this one static method. */
@Mixin(value = AttachmentRender.class, remap = false)
public abstract class MixinTaczAttachmentLod {

    @Inject(
            method = "renderAttachment(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;II)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void tacz_sewv$skipFarAttachment(ItemStack attachment, ItemStack gun, PoseStack poseStack,
                                                    ItemDisplayContext context, int light, int overlay,
                                                    CallbackInfo ci) {
        if (AttachmentLod.skips(poseStack, context)) {
            ci.cancel();
        }
    }
}
