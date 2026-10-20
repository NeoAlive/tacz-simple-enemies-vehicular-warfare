package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.init.ModItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import com.neoalive.tacz_sewv.platform.StackTag;

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
                && StackTag.getOrEmpty(stack).contains(TAG);
    }

    /**
     * Stamp a creative ammo box in-place. No-op for any other item and for stacks already
     * stamped. Returns the same stack for chaining.
     */
    public static ItemStack stamp(ItemStack stack) {
        if (!isCreativeAmmoBox(stack) || isConsumable(stack)) return stack;
        StackTag.update(stack, tag -> tag.putBoolean(TAG, true));
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
    public static void onItemPickup(ItemEntityPickupEvent.Pre event) {
        // NeoForge port: the pickup event is not cancellable on 1.21; refuse the pickup instead.
        if (event.getPlayer().level().isClientSide) return;
        if (!isConsumable(event.getItemEntity().getItem())) return;
        event.setCanPickup(TriState.FALSE);
        event.getItemEntity().discard();
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        scrubPlayer(event.getEntity());
    }
}
