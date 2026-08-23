package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The one loop behind {@link McspAmmoCompat#fallbackAmmo}, {@link AshAmmoCompat#fallbackAmmo}
 * and {@link VvpAmmoCompat#fallbackAmmo}: resolve registry ids to registered items, skipping
 * anything this install doesn't have, deduped in declaration order.
 */
public final class AmmoCompat {

    private AmmoCompat() {}

    public static List<Item> resolve(boolean modPresent, String... ids) {
        if (!modPresent) return List.of();
        List<Item> ammo = new ArrayList<>(ids.length);
        for (String id : ids) {
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null && item != net.minecraft.world.item.Items.AIR && !ammo.contains(item)) {
                ammo.add(item);
            }
        }
        return ammo;
    }
}
