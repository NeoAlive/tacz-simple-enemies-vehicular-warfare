package com.neoalive.tacz_sewv.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.internal.GeckoArmorLayerImpl;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SEM's Gecko layer also walks Curios ({@code back→CHEST}, {@code head→HEAD}) and feeds every
 * stack into the vanilla humanoid armor path. That is fine for real GeckoLib armor in a Curios
 * slot; for SBW parachutes / thermal goggles it lights up {@code body}+arms / {@code head} with a
 * missing {@code textures/models/armor/...} — the black/purple hip box on PMC only (RU/US never
 * get this layer). Equipment slots already gate on {@code isGeckoArmor}; Curios did not.
 */
@Mixin(value = GeckoArmorLayerImpl.class, remap = false)
public abstract class MixinGeckoArmorLayerImpl {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$skipNonGeckoCurios(
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractUnit entity,
            ItemStack stack, EquipmentSlot slot, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {

        if (!GeckoCompat.LOADED) return;
        if (stack.isEmpty() || GeckoCompatClient.isGeckoArmor(stack)) return;
        ci.cancel();
    }
}
