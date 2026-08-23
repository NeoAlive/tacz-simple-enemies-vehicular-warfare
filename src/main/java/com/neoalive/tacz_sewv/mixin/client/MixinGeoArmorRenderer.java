package com.neoalive.tacz_sewv.mixin.client;

import com.github.mcmodderanchor.simplebedrockmodel.v2.client.renderer.GeoArmorRendererV2;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.client.skin.CrewSkinRegistry;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Swap in a filesystem wearer-faction armor skin on SBM v2.
 *
 * <p>{@code renderArmorToBuffer} reads the private {@code texture} field and never calls
 * {@link #getTexture()}, so a RETURN inject on {@code getTexture} alone was a no-op for the
 * unit layer. The live path is the {@code getRenderType} argument; {@code getTexture} is kept
 * for the first-person arm path which does call it.
 */
@Mixin(value = GeoArmorRendererV2.class, remap = false)
public abstract class MixinGeoArmorRenderer {

    @Shadow
    protected LivingEntity livingEntity;

    @Shadow
    protected ItemStack itemStack;

    @ModifyArg(
            method = "renderArmorToBuffer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/mcmodderanchor/simplebedrockmodel/v2/client/renderer/GeoArmorRendererV2;getRenderType(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"
            ),
            index = 0
    )
    private ResourceLocation tacz_sewv$armorSkinBuffer(ResourceLocation texture) {
        return resolve(texture);
    }

    @Inject(method = "getTexture", at = @At("RETURN"), cancellable = true)
    private void tacz_sewv$armorSkinGetter(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation skin = resolve(null);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }

    @Unique
    private ResourceLocation resolve(ResourceLocation fallback) {
        LivingEntity wearer = this.livingEntity;
        ItemStack stack = this.itemStack;
        if (wearer == null || stack == null || stack.isEmpty()) {
            return fallback;
        }
        CrewFacts.Faction faction = CrewFacts.factionOfCrew(wearer);
        if (faction == null) {
            return fallback;
        }
        ResourceLocation skin = CrewSkinRegistry.textureFor(wearer, stack, faction);
        return skin != null ? skin : fallback;
    }
}
