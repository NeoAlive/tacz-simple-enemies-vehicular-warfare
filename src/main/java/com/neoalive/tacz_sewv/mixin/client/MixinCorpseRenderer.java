package com.neoalive.tacz_sewv.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.neoalive.tacz_sewv.client.UnitCorpseSkin;

/**
 * Binds SEWV unit skin while CorpseMod draws a DummyPlayer.
 *
 * <p>String {@code targets} + {@link Coerce} so this class has <b>zero</b> CorpseMod type refs in
 * its constant pool — loadable when Corpse is absent; {@link com.neoalive.tacz_sewv.mixin.CorpseMixinPlugin}
 * still skips apply entirely in that case.
 */
@Mixin(targets = "de.maxhenkel.corpse.entities.CorpseRenderer", remap = false)
public abstract class MixinCorpseRenderer {

    @Inject(method = "render", at = @At("HEAD"))
    private void sewv$bindUnitSkin(
            @Coerce Entity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci) {
        UnitCorpseSkin.bind(entity);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void sewv$clearUnitSkin(
            @Coerce Entity entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci) {
        UnitCorpseSkin.clear();
    }
}
