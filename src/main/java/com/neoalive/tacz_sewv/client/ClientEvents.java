package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.init.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// FORGE bus, client dist: opens the Tactical Data Terminal on a left click with it in hand.
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public class ClientEvents {

    // Left click (attack) with the Tactical Data Terminal in hand opens its command menu.
    // The screen captures the crosshair/facing the moment it opens, so the click has to be
    // cancelled — otherwise the same press would mine the block / attack the vehicle the player
    // was aiming at to give a board, land or formation order.
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.TACTICAL_DATA_TERMINAL.get())) return;

        TdtScreen.open();
        event.setCanceled(true);
    }
}
