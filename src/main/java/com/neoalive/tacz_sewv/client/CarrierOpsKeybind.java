package com.neoalive.tacz_sewv.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;
import com.neoalive.tacz_sewv.compat.NeoArmsCompat;

/**
 * Opens the carrier plane-ops GUI while seated. Mode toggle is owned by Neo Arms (J). Registered only
 * with Neo Arms installed, so the key does not sit in the controls list doing nothing.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class CarrierOpsKeybind {

    /**
     * Holder, so the mapping is only constructed with Neo Arms present: a {@code KeyMapping} enters
     * vanilla's key table on construction whether or not Forge ever registers it (see {@code RtsKeybind}).
     */
    private static final class Keys {
        /** Default U — separate from Neo Arms mode toggle (J). */
        static final KeyMapping OPEN_OPS = new KeyMapping(
                "key." + TaczSewv.MODID + ".carrier_ops",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories." + TaczSewv.MODID);

        private Keys() {}
    }

    private CarrierOpsKeybind() {}

    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void onRegister(RegisterKeyMappingsEvent event) {
            if (NeoArmsCompat.present()) event.register(Keys.OPEN_OPS);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !NeoArmsCompat.present()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        boolean pressed = false;
        while (Keys.OPEN_OPS.consumeClick()) {
            pressed = true;
        }
        if (!pressed) return;

        Entity vehicle = mc.player.getVehicle();
        if (!NeoArmsCarrierAccess.isCarrier(vehicle)) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.carrier_ops.not_seated")
                            .withStyle(ChatFormatting.GRAY), true);
            return;
        }

        boolean staticMode = NeoArmsCarrierAccess.isStaticMode(vehicle);
        NeoArmsCarrierAccess.Strip strip = NeoArmsCarrierAccess.deckStrip(vehicle, false);
        if (strip == null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.tacz_sewv.carrier_ops.no_strip")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }
        CarrierOpsClient.open(vehicle.getId(), strip, staticMode);
    }
}
