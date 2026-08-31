package com.neoalive.tacz_sewv.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Adds Combined Arms Configuration to the pause menu. */
public final class ConfigUIMenuHook {

    private ConfigUIMenuHook() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ConfigUIMenuHook.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause)) return;
        int buttonWidth = 200;
        int x = pause.width / 2 - buttonWidth / 2;
        int y = pause.height / 4 - 28;
        event.addListener(Button.builder(
                        net.minecraft.network.chat.Component.translatable("gui.tacz_sewv.config.title"),
                        b -> ConfigUIClient.requestOpen())
                .bounds(x, y, buttonWidth, 20)
                .build());
    }
}
