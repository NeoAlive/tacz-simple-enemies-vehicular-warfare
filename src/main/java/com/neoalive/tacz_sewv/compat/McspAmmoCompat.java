package com.neoalive.tacz_sewv.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Softcompat for <b>MCSP</b> vehicle ammo. SuperbWarfare's creative ammo box does not feed
 * MCSP magazine weapons reliably, so spawned MCSP hulls must stock the pack's own items.
 *
 * <p>No MCSP class is referenced — only registry ids — so this never classloads MCSP on an
 * install without it.
 */
public final class McspAmmoCompat {

    public static final String MODID = "mcsp";

    /** Every ammo item MCSP registers (see SOFTCOMPAT/MCSP ModItems). */
    private static final String[] AMMO_IDS = {
            "mcsp:25mm_ap",
            "mcsp:30mm_ap",
            "mcsp:40mm_explosive",
            "mcsp:40mm_smoke",
            "mcsp:120mm_bulletmortar",
            "mcsp:125mm_ap",
            "mcsp:125mm_he",
            "mcsp:bullet762",
            "mcsp:tow_2",
            "mcsp:mlrs_shells",
    };

    private McspAmmoCompat() {}

    public static boolean present() {
        return ModList.get().isLoaded(MODID);
    }

    /** True when this hull's registry id is under the MCSP namespace. */
    public static boolean isMcspHull(String entityId) {
        return entityId != null && entityId.startsWith(MODID + ":");
    }

    /**
     * Resolves every registered MCSP ammo item that exists in this install. Empty when MCSP
     * is absent or none of the known ids registered.
     */
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
