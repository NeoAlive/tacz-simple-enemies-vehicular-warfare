package com.neoalive.tacz_sewv.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.neoalive.tacz_sewv.init.ModItems;

/** Shared TDT inventory presence check (client open + server packet gate). */
public final class TacticalTerminal {

    private TacticalTerminal() {}

    /** True when the player has a Tactical Data Terminal anywhere in inventory. */
    public static boolean hasInInventory(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.TACTICAL_DATA_TERMINAL.get())) return true;
        }
        return false;
    }
}
