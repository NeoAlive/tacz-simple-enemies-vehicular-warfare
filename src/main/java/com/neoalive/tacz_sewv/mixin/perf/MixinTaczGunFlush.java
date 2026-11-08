package com.neoalive.tacz_sewv.mixin.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.perf.ThirdPersonGunFlush;

/**
 * Scopes the flush skip to one TaCZ gun render (see {@link ThirdPersonGunFlush}). The stencil buffer
 * clear at the end is skipped with it: without a scope no stencil test ran, so nothing was written.
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public abstract class MixinTaczGunFlush {

    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II)V";

    @Inject(method = RENDER, at = @At("HEAD"), remap = false, require = 0)
    private void tacz_sewv$enterGun(PoseStack poseStack, ItemStack gun, ItemDisplayContext context,
                                    RenderType renderType, int light, int overlay, CallbackInfo ci) {
        ThirdPersonGunFlush.enter(poseStack, gun, context);
    }

    @Inject(method = RENDER, at = @At("RETURN"), remap = false, require = 0)
    private void tacz_sewv$exitGun(PoseStack poseStack, ItemStack gun, ItemDisplayContext context,
                                   RenderType renderType, int light, int overlay, CallbackInfo ci) {
        ThirdPersonGunFlush.exit();
    }

    @Redirect(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", remap = false),
            remap = false,
            require = 0)
    private void tacz_sewv$skipStencilClear(int mask, boolean checkError) {
        if (ThirdPersonGunFlush.skip()) return;
        RenderSystem.clear(mask, checkError);
    }
}
