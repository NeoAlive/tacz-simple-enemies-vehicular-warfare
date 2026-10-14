package com.neoalive.tacz_sewv.compat.lv;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import com.neoalive.tacz_sewv.config.EasyMode;

/**
 * Supply doctrine for LV hulls, mirroring the SBW one ({@code MixinVehicleFactionEnergy} /
 * {@code IIssuedAmmo}): RU/US have no inventory and are <em>issued</em> fuel and ammunition, a
 * PMC's hull runs on whatever the player put in it. Decided per hull and per station from who is
 * sitting there now.
 *
 * <p>Done from the crew's goals through LV's public API, not by a mixin into LV (which a dedicated
 * server cannot survive — see {@link LvEvents}). That shapes both halves:
 * <ul>
 *   <li><b>Fuel</b> is kept topped up while an issued crew holds the wheel. The cost is that a hull
 *       captured from such a crew is captured with fuel in it; the SBW mixin avoids that by never
 *       draining the stored tank, which LV gives no hook for.</li>
 *   <li><b>Ammunition</b> is issued one magazine at a time as REAL rounds put into the hull's
 *       storage when a station runs dry, so LV's own reload (time, sound, sync) does the rest. A
 *       captor finds at most the one magazine that was mid-reload.</li>
 * </ul>
 */
final class LvSupply {

    /** Below this fraction of capacity an issued crew's tank is refilled. */
    private static final float REFUEL_BELOW = 0.5F;

    private LvSupply() {}

    /** Seat 0 is an RU/US unit and faction fuel is issued. Never {@code getDriver()} — see {@link LvHulls}. */
    static boolean issuedDriver(AbstractVehicle hull) {
        return EasyMode.factionInfiniteEnergy() && isIssuedFaction(LvHulls.seat0Occupant(hull));
    }

    /** The unit working this weapon's station is RU/US and faction ammunition is issued. */
    static boolean issuedOwner(AbstractVehicleWeapon<?> weapon) {
        return EasyMode.factionInfiniteAmmo() && isIssuedFaction(weapon.getWeaponUnit().getOwner());
    }

    /** Called from the drive goals each tick. */
    static void topUpFuel(AbstractVehicle hull) {
        if (hull.energyInfo == null || !issuedDriver(hull)) return;
        float capacity = hull.energyInfo.energyCapacity;
        if (hull.getEnergy() < capacity * REFUEL_BELOW) hull.setEnergy(capacity);
    }

    /**
     * Called from the gunner goal for each weapon on its station. A dry, non-reloading weapon with
     * nothing in storage gets one magazine's worth of its own ammo item in the hull, which LV's
     * weapon tick then reloads from on its next tick.
     */
    static void issueAmmo(AbstractVehicle hull, AbstractVehicleWeapon<?> weapon) {
        if (weapon.getRemainAmmo() > 0 || weapon.isReloading() || weapon.hasStorageAmmo()) return;
        if (!issuedOwner(weapon)) return;
        ItemStack[] items = weapon.getData().getReload().getAmmo().getItems();
        if (items.length == 0) return;
        ItemStack round = items[0].copy();
        round.setCount(Math.max(1, Math.min(weapon.getMaxCapacity(), round.getMaxStackSize())));
        hull.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(inv -> {
            ItemStack rest = round;
            for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
                rest = inv.insertItem(i, rest, false);
            }
        });
    }

    private static boolean isIssuedFaction(Entity e) {
        return e instanceof RUunitEntity || e instanceof USunitEntity;
    }
}
