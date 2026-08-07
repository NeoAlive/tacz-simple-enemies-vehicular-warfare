package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Softcompat for <b>ashvehicle</b> ammo items. Prefer native stacks over a creative box when
 * stocking ASH hulls (same shape as {@link McspAmmoCompat}). Sapsan has no gun ammo and is
 * skipped by the spawner separately.
 */
public final class AshAmmoCompat {

    public static final String MODID = "ashvehicle";

    private static final String[] AMMO_IDS = {
            "ashvehicle:aim9item",
            "ashvehicle:aim120item",
            "ashvehicle:aim54item",
            "ashvehicle:r60item",
            "ashvehicle:agm114item",
            "ashvehicle:agm158item",
            "ashvehicle:gbu57item",
            "ashvehicle:cbu87item",
            "ashvehicle:nuclearbombitem",
            "ashvehicle:40mmitem",
            "ashvehicle:105mmitem",
            "ashvehicle:20mmitem",
    };

    private AshAmmoCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean isAshHull(String entityId) {
        return entityId != null && entityId.startsWith(MODID + ":");
    }

    /** Coordinate ballistic launchers (Sapsan) have empty Weapons — no container ammo to stock. */
    public static boolean isMissileSystemHull(String entityId) {
        if (entityId == null) return false;
        String lower = entityId.toLowerCase();
        return lower.contains("sapsan") || lower.contains("grim2");
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
