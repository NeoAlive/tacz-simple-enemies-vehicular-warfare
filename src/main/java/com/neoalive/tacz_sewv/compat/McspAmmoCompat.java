package com.neoalive.tacz_sewv.compat;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;

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
        return AmmoCompat.resolve(present(), AMMO_IDS);
    }
}
