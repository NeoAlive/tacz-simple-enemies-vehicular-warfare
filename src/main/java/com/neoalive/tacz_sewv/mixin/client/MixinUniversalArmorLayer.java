package com.neoalive.tacz_sewv.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.UniversalArmorLayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(UniversalArmorLayer.class)
public abstract class MixinUniversalArmorLayer {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipCustomModelArmor(
            PoseStack poseStack, MultiBufferSource buffer, AbstractUnit entity, EquipmentSlot slot,
            int packedLight, HumanoidModel<LivingEntity> defaultModel,
            float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {

        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) return;

        if (ForgeHooksClient.getArmorModel(entity, stack, slot, defaultModel) != defaultModel) {
            ci.cancel();
        }
    }
}
