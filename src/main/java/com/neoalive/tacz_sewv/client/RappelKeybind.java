package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Player-rappel movement lock while descending. Orders are issued from the Quick Command Air menu
 * ({@link com.neoalive.tacz_sewv.client.radial.QuickAirClient}), not keybinds.
 */
@EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class RappelKeybind {

    private RappelKeybind() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        PlayerRappelClient.clearIfStale(mc.player);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (event.getEntity() instanceof net.minecraft.client.player.LocalPlayer local) {
            PlayerRappelClient.clearIfStale(local);
        }
        if (!PlayerRappelClient.isMovementLocked()) return;
        Input input = event.getInput();
        input.leftImpulse = 0.0F;
        input.forwardImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }
}
