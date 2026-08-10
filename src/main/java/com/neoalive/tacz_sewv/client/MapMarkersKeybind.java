package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Client toggle for SEWV's own Xaero World Map markers — clutter off without opening config.
 * Polled from the client tick like {@link TdtKeybind} so SuperbWarfare's input guards cannot eat it.
 * Registered once via {@link TdtKeybind.Registration} alongside the TDT key (avoids a second MOD-bus
 * subscriber that was easy to mis-read as a duplicate TDT entry).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class MapMarkersKeybind {

    private static final String CATEGORY = "key.categories." + TaczSewv.MODID;

    /**
     * Default <b>]</b>. Avoids SuperbWarfare's vehicle-interact <b>K</b> conflict.
     */
    public static final KeyMapping TOGGLE_MAP_MARKERS = new KeyMapping(
            "key." + TaczSewv.MODID + ".toggle_map_markers",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            CATEGORY);

    private MapMarkersKeybind() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean pressed = false;
        while (TOGGLE_MAP_MARKERS.consumeClick()) {
            pressed = true;
        }
        if (!pressed) return;

        boolean visible = ClientConfig.toggleMapMarkersSession();
        mc.player.displayClientMessage(
                Component.translatable(visible
                                ? "message.tacz_sewv.map_markers.on"
                                : "message.tacz_sewv.map_markers.off")
                        .withStyle(ChatFormatting.GRAY),
                true);
    }
}
