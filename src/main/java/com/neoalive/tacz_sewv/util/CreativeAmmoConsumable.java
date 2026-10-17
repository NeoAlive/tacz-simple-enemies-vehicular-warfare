package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Marks {@code superbwarfare:creative_ammo_box} stacks that entered a
 * {@link com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity} inventory as
 * system-issued ({@link #TAG}), so they are wiped on hull death and cannot be kept if a
 * player somehow receives one. A creative ammo box the player put in their own inventory
 * (never stamped) is left alone.
 */
public final class CreativeAmmoConsumable {

    /** NBT key written onto creative ammo that entered a vehicle inventory. */
    public static final String TAG = "sewv_consumable";

    private CreativeAmmoConsumable() {}

    public static boolean isCreativeAmmoBox(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(ModItems.CREATIVE_AMMO_BOX.get());
    }

    public static boolean isConsumable(ItemStack stack) {
        return isCreativeAmmoBox(stack)
                && stack.hasTag()
                && stack.getTag().contains(TAG);
    }

    /**
     * Stamp a creative ammo box in-place. No-op for any other item and for stacks already
     * stamped. Returns the same stack for chaining.
     */
    public static ItemStack stamp(ItemStack stack) {
        if (!isCreativeAmmoBox(stack) || isConsumable(stack)) return stack;
        stack.getOrCreateTag().putBoolean(TAG, true);
        return stack;
    }

    /** Remove every stamped creative ammo box from a player's inventory and cursor. */
    public static void scrubPlayer(Player player) {
        if (player == null || player.level().isClientSide) return;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isConsumable(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (player.containerMenu != null && isConsumable(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!isConsumable(event.getItem().getItem())) return;
        event.setCanceled(true);
        event.getItem().discard();
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        scrubPlayer(event.player);
    }
}
