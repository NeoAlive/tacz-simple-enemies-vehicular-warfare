package com.neoalive.tacz_sewv.item.armor;

import java.util.UUID;
import java.util.function.Consumer;

import com.atsuishio.superbwarfare.Mod;
import com.atsuishio.superbwarfare.init.ModAttributes;
import com.atsuishio.superbwarfare.resource.model.ArmorModelReloadListener;
import com.atsuishio.superbwarfare.tiers.ModArmorMaterial;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.renderer.GeoArmorRendererV2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * PMC MICH helmet — same cemented-carbide / bullet-resistance profile as SBW's US PASGT,
 * with this mod's own bedrock model (loaded through SBW's {@link ArmorModelReloadListener}).
 */
public class PmcHelmetMichItem extends ArmorItem {

    public static final ResourceLocation TEXTURE =
            new ResourceLocation(TaczSewv.MODID, "textures/bedrock/armor/pmc_helmet_mich.png");
    public static final ResourceLocation MODEL =
            new ResourceLocation(TaczSewv.MODID, "models/bedrock/armor/pmc_helmet_mich.geo.json");

    public PmcHelmetMichItem() {
        super(ModArmorMaterial.CEMENTED_CARBIDE, Type.HELMET, new Properties());
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GeoArmorRendererV2 renderer;

            @Override
            public HumanoidModel<?> getHumanoidArmorModel(
                    LivingEntity livingEntity,
                    ItemStack itemStack,
                    EquipmentSlot equipmentSlot,
                    HumanoidModel<?> original) {
                if (this.renderer == null) {
                    this.renderer = new GeoArmorRendererV2(
                            ArmorModelReloadListener.INSTANCE.getModel(MODEL),
                            equipmentSlot,
                            TEXTURE);
                }
                this.renderer.preparePose(livingEntity, itemStack, equipmentSlot, original);
                return this.renderer;
            }
        });
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        Multimap<Attribute, AttributeModifier> map = super.getDefaultAttributeModifiers(slot);
        UUID uuid = new UUID(slot.toString().hashCode(), 0L);
        if (slot == EquipmentSlot.HEAD) {
            map = HashMultimap.create(map);
            double resistance = 0.2 * Math.max(0.0, 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
            map.put(
                    ModAttributes.BULLET_RESISTANCE.get(),
                    new AttributeModifier(
                            uuid,
                            Mod.ATTRIBUTE_MODIFIER,
                            resistance,
                            AttributeModifier.Operation.ADDITION));
        }
        return map;
    }
}
