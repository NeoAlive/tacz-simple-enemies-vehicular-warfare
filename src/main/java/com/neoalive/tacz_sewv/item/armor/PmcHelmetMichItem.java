package com.neoalive.tacz_sewv.item.armor;

import java.util.ArrayList;
import java.util.List;

import com.atsuishio.superbwarfare.Mod;
import com.atsuishio.superbwarfare.init.ModAttributes;
import com.atsuishio.superbwarfare.resource.model.ArmorModelReloadListener;
import com.atsuishio.superbwarfare.tiers.ModArmorMaterial;
import com.github.mcmodderanchor.simplebedrockmodel.v2.client.renderer.GeoArmorRendererV2;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * PMC MICH helmet — same cemented-carbide / bullet-resistance profile as SBW's US PASGT,
 * with this mod's own bedrock model (loaded through SBW's {@link ArmorModelReloadListener}).
 *
 * <p>Inventory icon is the flat {@code textures/item/mich_helmet.png}.
 */
public class PmcHelmetMichItem extends ArmorItem {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "textures/bedrock/armor/pmc_helmet_mich.png");
    public static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(TaczSewv.MODID, "models/bedrock/armor/pmc_helmet_mich.geo.json");

    public PmcHelmetMichItem() {
        // NeoForge port: 1.21 armor materials carry no durability; same value SBW 1.21 gives its PASGT.
        super(ModArmorMaterial.CEMENTED_CARBIDE, Type.HELMET, new Properties().durability(Type.HELMET.getDurability(50)));
    }

    /**
     * NeoForge port: client extensions are registered from {@code RegisterClientExtensionsEvent}
     * (see {@code ClientModEvents}), not by an {@code initializeClient} override.
     */
    public static IClientItemExtensions clientExtensions() {
        return new IClientItemExtensions() {
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
        };
    }

    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        ItemAttributeModifiers base = super.getDefaultAttributeModifiers(stack);
        List<ItemAttributeModifiers.Entry> list = new ArrayList<>(base.modifiers());
        double resistance = 0.2 * Math.max(0.0, 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
        list.add(new ItemAttributeModifiers.Entry(
                ModAttributes.BULLET_RESISTANCE,
                new AttributeModifier(Mod.ATTRIBUTE_MODIFIER, resistance, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.HEAD));
        return new ItemAttributeModifiers(list, true);
    }
}
