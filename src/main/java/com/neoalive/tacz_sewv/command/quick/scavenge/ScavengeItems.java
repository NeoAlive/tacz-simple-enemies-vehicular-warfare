package com.neoalive.tacz_sewv.command.quick.scavenge;

import javax.annotation.Nullable;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/** Item reads Scavenge needs: rounds left in a dropped gun, and how two armor pieces compare. */
final class ScavengeItems {

    private ScavengeItems() {}

    /** One round of what a TACZ gun's magazine holds, or empty when it holds none. */
    static ItemStack magazineRound(ItemStack gunStack) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null || gun.getCurrentAmmoCount(gunStack) <= 0) return ItemStack.EMPTY;
        ResourceLocation gunId = gun.getGunId(gunStack);
        if (gunId == null) return ItemStack.EMPTY;
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId()).orElse(null);
        if (ammoId == null) return ItemStack.EMPTY;
        return AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
    }

    static int magazineCount(ItemStack gunStack) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        return gun == null ? 0 : Math.max(0, gun.getCurrentAmmoCount(gunStack));
    }

    /** A copy of the gun with {@code n} fewer rounds in the magazine. */
    static ItemStack unload(ItemStack gunStack, int n) {
        ItemStack copy = gunStack.copy();
        IGun gun = IGun.getIGunOrNull(copy);
        if (gun != null) gun.setCurrentAmmoCount(copy, Math.max(0, gun.getCurrentAmmoCount(copy) - n));
        return copy;
    }

    @Nullable
    static EquipmentSlot armorSlot(ItemStack stack) {
        return stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot() : null;
    }

    /**
     * True when {@code found} beats what is worn: no worse in protection or durability left, and
     * better in at least one. An empty slot is beaten by any piece.
     */
    static boolean beats(ItemStack found, ItemStack worn) {
        if (!(found.getItem() instanceof ArmorItem a)) return false;
        if (worn.isEmpty()) return true;
        if (!(worn.getItem() instanceof ArmorItem b)) return false;
        return ScavengeRules.dominates(a.getDefense(), durability(found), b.getDefense(), durability(worn));
    }

    private static int durability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }
}
