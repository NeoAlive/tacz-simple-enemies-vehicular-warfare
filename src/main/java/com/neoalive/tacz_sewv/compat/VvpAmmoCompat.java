package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Softcompat for <b>vvp</b> (Vintage Vehicle Pack) ammo. Same shape as
 * {@link McspAmmoCompat} / {@link AshAmmoCompat} — registry ids only, no VVP classes.
 */
public final class VvpAmmoCompat {

    public static final String MODID = "vvp";

    private static final String[] AMMO_IDS = {
            "vvp:item_30mm",
            "vvp:ap_shell",
            "vvp:he_shell",
            "vvp:item_7_62mm",
            "vvp:item_12_7mm",
            "vvp:agm",
            "vvp:aam",
            "vvp:shell_122mm",
    };

    private VvpAmmoCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean isVvpHull(String entityId) {
        return entityId != null && entityId.startsWith(MODID + ":");
    }

    public static List<Item> fallbackAmmo() {
        if (!present()) return List.of();
        List<Item> ammo = new ArrayList<>(AMMO_IDS.length);
        for (String id : AMMO_IDS) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null && item != net.minecraft.world.item.Items.AIR && !ammo.contains(item)) {
                ammo.add(item);
            }
        }
        return ammo;
    }
}
