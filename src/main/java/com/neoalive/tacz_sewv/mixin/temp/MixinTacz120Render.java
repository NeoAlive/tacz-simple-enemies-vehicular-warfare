package com.neoalive.tacz_sewv.mixin.temp;

import java.util.function.Consumer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.functional.AttachmentRender;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.perf.AttachmentLod;
import com.neoalive.tacz_sewv.client.perf.ThirdPersonGunFlush;

/**
 * TEMPORARY. TaCZ 1.2.0 changed {@code OculusCompat.endBatch} to {@code MultiBufferSource} and added
 * an {@code AttachmentType} argument on {@code AttachmentRender.renderAttachment}. Delete this class
 * and {@code tacz_sewv.temp.mixins.json} once {@code MixinTaczModelFlush} and
 * {@code MixinTaczAttachmentLod} target these descriptors.
 *
 * <p>The 6-arg {@code BedrockGunModel.render} only delegates. {@code GunItemRendererWrapper} calls
 * the buffer overload, which is the one that reaches {@code endBatch} and {@code RenderSystem.clear}.
 */
@Mixin(
        value = {BedrockModel.class, BedrockAttachmentModel.class, BedrockGunModel.class, AttachmentRender.class},
        remap = false)
public abstract class MixinTacz120Render {

    private static final String END_BATCH =
            "Lcom/tacz/guns/compat/oculus/OculusCompat;endBatch(Lnet/minecraft/client/renderer/MultiBufferSource;)Z";

    /** Real draw. The shorter overloads forward here. */
    private static final String GUN_RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;"
                    + "IIFFFFLnet/minecraft/client/renderer/MultiBufferSource;)V";

    private static final String ATTACH =
            "renderAttachment(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/tacz/guns/api/item/attachment/AttachmentType;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;II)V";

    private static final String ATTACH_BUFFER =
            "renderAttachment(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/tacz/guns/api/item/attachment/AttachmentType;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;II"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;)V";

    private static final String ATTACH_CONSUMER =
            "renderAttachment(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;"
                    + "Lcom/tacz/guns/api/item/attachment/AttachmentType;Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;II"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;Ljava/util/function/Consumer;)V";

    @Redirect(
            method = {"render*", "renderTempPart*"},
            at = @At(value = "INVOKE", target = END_BATCH, remap = false),
            remap = false,
            require = 0)
    private boolean tacz_sewv$keepBatched(MultiBufferSource bufferSource) {
        if (ThirdPersonGunFlush.skip()) return true;
        return OculusCompat.endBatch(bufferSource);
    }

    @Inject(method = GUN_RENDER, at = @At("HEAD"), remap = false, require = 0)
    private void tacz_sewv$enterGun(PoseStack poseStack, ItemStack gun, ItemDisplayContext context,
                                    RenderType renderType, int light, int overlay, float red, float green,
                                    float blue, float alpha, MultiBufferSource buffers, CallbackInfo ci) {
        ThirdPersonGunFlush.enter(poseStack, gun, context);
    }

    @Inject(method = GUN_RENDER, at = @At("RETURN"), remap = false, require = 0)
    private void tacz_sewv$exitGun(PoseStack poseStack, ItemStack gun, ItemDisplayContext context,
                                   RenderType renderType, int light, int overlay, float red, float green,
                                   float blue, float alpha, MultiBufferSource buffers, CallbackInfo ci) {
        ThirdPersonGunFlush.exit();
    }

    @Redirect(
            method = GUN_RENDER,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    remap = false),
            remap = false,
            require = 0)
    private void tacz_sewv$skipStencilClear(int mask, boolean checkError) {
        if (ThirdPersonGunFlush.skip()) return;
        RenderSystem.clear(mask, checkError);
    }

    /** Accelerated path clears from this lambda, not from the render method. */
    @Redirect(
            method = "lambda$renderAccelerated$31",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    remap = false),
            remap = false,
            require = 0)
    private static void tacz_sewv$skipAcceleratedStencilClear(int mask, boolean checkError) {
        if (ThirdPersonGunFlush.skip()) return;
        RenderSystem.clear(mask, checkError);
    }

    @Inject(method = ATTACH, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$skipFar(ItemStack attachment, ItemStack gun, AttachmentType type,
                                          PoseStack poseStack, ItemDisplayContext context, int light, int overlay,
                                          CallbackInfo ci) {
        if (AttachmentLod.skips(poseStack, context)) ci.cancel();
    }

    @Inject(method = ATTACH_BUFFER, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$skipFarBuffered(ItemStack attachment, ItemStack gun, AttachmentType type,
                                                  PoseStack poseStack, ItemDisplayContext context, int light,
                                                  int overlay, MultiBufferSource buffers, CallbackInfo ci) {
        if (AttachmentLod.skips(poseStack, context)) ci.cancel();
    }

    @Inject(method = ATTACH_CONSUMER, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void tacz_sewv$skipFarConsumer(ItemStack attachment, ItemStack gun, AttachmentType type,
                                                  PoseStack poseStack, ItemDisplayContext context, int light,
                                                  int overlay, MultiBufferSource buffers,
                                                  Consumer<IFunctionalRenderer> extra, CallbackInfo ci) {
        if (AttachmentLod.skips(poseStack, context)) ci.cancel();
    }
}
