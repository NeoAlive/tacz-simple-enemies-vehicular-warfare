package com.neoalive.tacz_sewv.client.editor;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.util.VehiclePackIndex;

/**
 * Warms {@link VehiclePoolCatalog} and {@link VehiclePackIndex} after login on the main thread,
 * without a global tick counter that could interact with other mods' startup timing.
 */
@EventBusSubscriber(modid = TaczSewv.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PoolCatalogBootstrap {

    private PoolCatalogBootstrap() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        // Defer one frame so login / datapack sync from other mods finishes first.
        mc.execute(() -> {
            VehiclePoolCatalog.ensureLoaded();
            VehiclePackIndex.rebuild();
        });
    }
}
