package com.neoalive.tacz_sewv.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Tactical Data Terminal: a left click with it in hand opens the
 * {@link com.neoalive.tacz_sewv.client.TdtScreen} command menu (see
 * {@link com.neoalive.tacz_sewv.client.ClientEvents}). It has no server-side behaviour of
 * its own — the screen's buttons send the order packets.
 */
public class TacticalDataTerminalItem extends Item {

    public TacticalDataTerminalItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.tacz_sewv.tactical_data_terminal.use").withStyle(ChatFormatting.GRAY));
    }
}
