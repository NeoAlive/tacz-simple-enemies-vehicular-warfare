package com.neoalive.tacz_sewv.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

/**
 * Warms {@link VehiclePoolCatalog} after login on the main thread, without a global tick counter
 * that could interact with other mods' startup timing.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PoolCatalogBootstrap {

    private PoolCatalogBootstrap() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // Defer one frame so login / datapack sync from other mods finishes first.
        mc.execute(VehiclePoolCatalog::ensureLoaded);
    }
}
