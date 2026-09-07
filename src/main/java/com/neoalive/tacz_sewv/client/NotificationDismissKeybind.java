package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Skips the remaining on-screen delay of the active HUD notification. Default unbound —
 * assign in Controls. Polled from the client tick so SuperbWarfare seat input guards cannot eat it.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class NotificationDismissKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    public static final KeyMapping DISMISS = new KeyMapping(
            "key." + TaczSewv.MODID + ".dismiss_notification",
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            CATEGORY);

    private NotificationDismissKeybind() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean pressed = false;
        while (DISMISS.consumeClick()) {
            pressed = true;
        }
        if (!pressed || mc.screen != null) return;
        if (!NotificationHud.isActive()) return;

        NotificationHud.dismiss();
    }
}
