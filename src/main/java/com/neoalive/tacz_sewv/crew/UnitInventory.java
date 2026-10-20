package com.neoalive.tacz_sewv.crew;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A unit's own pockets: SEM's 18-slot PMC inventory (0 main hand, 1 off hand, 2-5 armour, 6-17
 * storage), or null for anything that has none.
 *
 * <p>NeoForge port: on 1.21 every {@code LivingEntity} answers {@code Capabilities.ItemHandler.ENTITY}
 * with a 6-slot equipment wrapper, so RU/US are no longer "without an ITEM_HANDLER". Much of the
 * crew-supply doctrine reads "has an inventory" as "is a PMC" (issued ammo for RU/US, real stacks
 * for PMC — see {@code IIssuedAmmo}); asking the capability directly would hand an RU mortar crew
 * a hands-and-armour wrapper to look for shells in. Every unit-inventory read goes through here.
 */
public final class UnitInventory {

    private UnitInventory() {}

    @Nullable
    public static IItemHandler of(@Nullable Entity entity) {
        if (!(entity instanceof PmcUnitEntity pmc)) return null;
        return pmc.getCapability(Capabilities.ItemHandler.ENTITY, null);
    }
}
