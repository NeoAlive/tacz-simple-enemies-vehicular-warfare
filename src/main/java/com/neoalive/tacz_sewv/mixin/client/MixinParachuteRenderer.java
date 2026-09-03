package com.neoalive.tacz_sewv.mixin.client;

import com.atsuishio.superbwarfare.client.renderer.curio.ParachuteRenderer;
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
 * Same shape as {@link MixinThermalImagingGogglesRenderer}: SBW's Curios parachute renderer is
 * written for HumanoidModel wearers. Open canopy on SEM units is drawn by SBW's own
 * {@code RenderLivingEvent.Post} hook; the packed-pack Curios path must not run on hierarchical
 * unit models.
 */
@Mixin(value = ParachuteRenderer.class, remap = false)
public abstract class MixinParachuteRenderer {

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
