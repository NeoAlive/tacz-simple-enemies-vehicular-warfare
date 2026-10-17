package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.curio.ThermalImagingGogglesRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.SlotContext;

/**
 * SBW's stock Curios renderer follows head rotations only on HumanoidModel.
 * SEM units are hierarchical models, so the stock path leaves a second goggles mesh
 * parked near the body while {@code CuriosHeadLayer} draws the corrected one on the head.
 */
@Mixin(value = ThermalImagingGogglesRenderer.class, remap = false)
public abstract class MixinThermalImagingGogglesRenderer {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private <T extends LivingEntity, M extends EntityModel<T>> void tacz_sewv$skipSemUnits(
            ItemStack stack, SlotContext slotContext, PoseStack poseStack, RenderLayerParent<T, M> parent,
            MultiBufferSource buffer, int packedLight, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {

        LivingEntity entity = slotContext.entity();
        if (!(entity instanceof AbstractUnit)) return;
        if (parent.getModel() instanceof HumanoidModel<?>) return;
        ci.cancel();
    }
}
