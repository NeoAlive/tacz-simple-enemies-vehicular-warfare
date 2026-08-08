package com.neoalive.tacz_sewv.mixin.client;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.GeoArmorRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.neoalive.tacz_sewv.client.skin.ArmorSkinRegistry;
import com.neoalive.tacz_sewv.crew.CrewFacts;

/**
 * Swap in a filesystem wearer-faction armor skin after simplebedrockmodel's getTexture.
 * Paint follows the unit, not the item — an RU unit wearing a US IOTV resolves
 * {@code ru_chest_iotv_*} if present.
 */
@Mixin(value = GeoArmorRenderer.class, remap = false)
public abstract class MixinGeoArmorRenderer {

    @Shadow
    protected LivingEntity livingEntity;

    @Shadow
    protected ItemStack itemStack;

    @Inject(method = "getTexture", at = @At("RETURN"), cancellable = true)
    private void tacz_sewv$armorSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        LivingEntity wearer = this.livingEntity;
        ItemStack stack = this.itemStack;
        if (wearer == null || stack == null || stack.isEmpty()) return;

        CrewFacts.Faction faction = CrewFacts.factionOfCrew(wearer);
        if (faction == null) return;

        ResourceLocation skin = ArmorSkinRegistry.textureFor(wearer, stack, faction);
        if (skin != null) {
            cir.setReturnValue(skin);
        }
    }
}
