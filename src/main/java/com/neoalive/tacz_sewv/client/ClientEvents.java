package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.TaczSewv;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// MOD bus for registration, FORGE bus for the tick/keypress
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(BoardKeybind.BOARD_KEY);
        event.register(BoardKeybind.DISMOUNT_KEY);
        event.register(HelicopterKeybind.TAKEOFF_KEY);
        event.register(HelicopterKeybind.LAND_KEY);
    }

    // Separate subscriber on the FORGE bus for the actual key polling
    @Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
    public static class ForgeClientEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (Minecraft.getInstance().player == null) return;

            // consumeClick() returns true once per press, drains the queue
            while (BoardKeybind.BOARD_KEY.consumeClick()) {
                BoardKeybind.onBoardPressed();
            }
            while (BoardKeybind.DISMOUNT_KEY.consumeClick()) {
                BoardKeybind.onDismountPressed();
            }
            while (HelicopterKeybind.TAKEOFF_KEY.consumeClick()) {
                HelicopterKeybind.onTakeoffPressed();
            }
            while (HelicopterKeybind.LAND_KEY.consumeClick()) {
                HelicopterKeybind.onLandPressed();
            }
        }
    }
}