package com.neoalive.tacz_sewv.client.zone;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Opens the spawn zone map. Registered only when Xaero's World Map is loaded, and unbound by
 * default so it never takes a key from another mod. Operators only; the server checks again.
 */
public final class ZoneMapKeybind {

    private static final String XAERO = "xaeroworldmap";

    /** Built only if touched: a KeyMapping enters vanilla's key table on construction. */
    public static final class Keys {
        public static final KeyMapping OPEN_ZONE_MAP = new KeyMapping(
                "key." + TaczSewv.MODID + ".open_zone_map",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "key.categories." + TaczSewv.MODID);

        private Keys() {}
    }

    private ZoneMapKeybind() {}

    public static boolean available() {
        return ModList.get().isLoaded(XAERO);
    }

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            if (available()) event.register(Keys.OPEN_ZONE_MAP);
        }
    }

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
    public static final class Poll {
        private Poll() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !available()) return;
            boolean pressed = false;
            while (Keys.OPEN_ZONE_MAP.consumeClick()) pressed = true;
            if (!pressed) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            if (!mc.player.hasPermissions(2)) {
                mc.player.displayClientMessage(Component.translatable("gui.tacz_sewv.zones.op_only"), true);
                return;
            }
            com.neoalive.tacz_sewv.client.xaero.XaeroZoneView.open(null);
        }
    }
}
