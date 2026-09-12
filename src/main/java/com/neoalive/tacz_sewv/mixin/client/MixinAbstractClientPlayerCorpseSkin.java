package com.neoalive.tacz_sewv.mixin.client;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.client.UnitCorpseSkin;

/**
 * While {@link MixinCorpseRenderer} has a SEWV unit corpse bound, return that unit's camo
 * texture instead of the Mojang skin for {@code DummyPlayer}.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayerCorpseSkin {

    @Inject(method = "getSkinTextureLocation", at = @At("HEAD"), cancellable = true)
    private void sewv$unitCorpseSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation skin = UnitCorpseSkin.current();
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }
}
