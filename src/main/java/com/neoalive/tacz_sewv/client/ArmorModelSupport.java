package com.neoalive.tacz_sewv.client;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;

/** Cheap per-item answer for whether Forge replaces an armor carrier model. */
public final class ArmorModelSupport {

    private static final Map<Item, Boolean> CUSTOM_MODELS = new IdentityHashMap<>();

    private ArmorModelSupport() {}

    public static boolean hasCustomModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
                                         HumanoidModel<LivingEntity> carrier) {
        Boolean cached = CUSTOM_MODELS.get(stack.getItem());
        if (cached != null) return cached;
        boolean custom = ForgeHooksClient.getArmorModel(entity, stack, slot, carrier) != carrier;
        CUSTOM_MODELS.put(stack.getItem(), custom);
        return custom;
    }
}
