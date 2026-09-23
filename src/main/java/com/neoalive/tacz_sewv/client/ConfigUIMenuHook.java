package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.neoalive.tacz_sewv.TaczSewv;

/** Adds Combined Arms Configuration to the pause menu. */
public final class ConfigUIMenuHook {

    private static final int ROW = 24;

    private ConfigUIMenuHook() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ConfigUIMenuHook.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause)) return;

        // Slot in right under "Back to Game" — the one full-width button at the very top of the
        // grid — rather than at the bottom, so it reads as a primary action instead of getting
        // lost below Options/Save & Quit. Matched by label rather than "topmost widget": another
        // mod's pause-menu hook could run first and add something above it.
        String backToGame = Component.translatable("menu.returnToGame").getString();
        int insertY = Integer.MAX_VALUE;
        int buttonWidth = 204;
        int x = pause.width / 2 - buttonWidth / 2;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && backToGame.equals(w.getMessage().getString())) {
                insertY = w.getY() + ROW;
                x = w.getX();
                buttonWidth = w.getWidth();
                break;
            }
        }
        if (insertY == Integer.MAX_VALUE) {
            // Fallback: label didn't match (locale/version drift) — just above the bottom-most
            // button, the old placement, rather than not showing the entry at all.
            insertY = Integer.MIN_VALUE;
            for (GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof AbstractWidget w) insertY = Math.max(insertY, w.getY());
            }
            if (insertY == Integer.MIN_VALUE) return;
        }

        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w.getY() >= insertY) {
                w.setY(w.getY() + ROW);
            }
        }

        event.addListener(Button.builder(
                        Component.translatable("gui.tacz_sewv.config.title"),
                        b -> openSafe())
                .bounds(x, insertY, buttonWidth, 20)
                .build());
    }

    private static void openSafe() {
        try {
            ConfigUIClient.requestOpen();
        } catch (Throwable t) {
            // Hot-recompile while the client is running can briefly make classes unloadable;
            // don't turn that into a full client crash from the pause menu.
            TaczSewv.LOGGER.error("[sewv] Config UI failed to open", t);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Config UI failed to open — restart the client after a rebuild."),
                        false);
            }
        }
    }
}
