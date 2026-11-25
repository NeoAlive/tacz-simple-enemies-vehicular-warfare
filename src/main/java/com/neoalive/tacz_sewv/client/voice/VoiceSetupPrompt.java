package com.neoalive.tacz_sewv.client.voice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.vosk.VoskCompat;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Offers voice setup once per launch on the title screen, while VoskLib has no usable model and the player
 * has not said never. Opened from the tick rather than from the title screen's own init, so the title
 * screen is fully built before it is replaced.
 */
@EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class VoiceSetupPrompt {

    private static boolean offered;

    private VoiceSetupPrompt() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (offered) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen title)) return;
        offered = true;
        if (!VoskCompat.present() || VoskCompat.configured()) return;
        if (!ClientConfig.flag(ClientConfig.VOICE_SETUP_PROMPT) || !ClientConfig.flag(ClientConfig.VOICE_ORDERS_ENABLED)) return;
        mc.setScreen(new VoiceSetupScreen(title));
    }
}
