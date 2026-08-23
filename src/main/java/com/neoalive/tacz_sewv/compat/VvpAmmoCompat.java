package com.neoalive.tacz_sewv.compat;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;

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
        return AmmoCompat.resolve(present(), AMMO_IDS);
    }
}
