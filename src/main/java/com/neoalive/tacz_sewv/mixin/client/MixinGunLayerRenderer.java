package com.neoalive.tacz_sewv.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.GunLayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.entity.ai.support.EmplacementHands;

@Mixin(value = GunLayerRenderer.class, remap = false)
public abstract class MixinGunLayerRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void tacz_sewv$hideOnEmplacement(PoseStack poseStack, MultiBufferSource buffer,
                                             int packedLight, LivingEntity entity,
                                             float limbSwing, float limbSwingAmount,
                                             float partialTicks, float ageInTicks,
                                             float netHeadYaw, float headPitch,
                                             CallbackInfo ci) {
        if (EmplacementHands.hideHeldItems(entity)) {
            ci.cancel();
        }
    }
}
