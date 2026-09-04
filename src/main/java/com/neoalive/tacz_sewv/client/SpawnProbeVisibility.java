package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.ClientConfig;

/**
 * Rebuilds chunk meshes when Client → Debug → Show Spawn Probes flips so
 * {@link com.neoalive.tacz_sewv.block.SpawnProbeBlock#getRenderShape} takes effect without F3+A.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID, value = Dist.CLIENT)
public final class SpawnProbeVisibility {

    private static boolean lastKnown;

    private SpawnProbeVisibility() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean now = ClientConfig.flag(ClientConfig.SHOW_SPAWN_PROBES);
        if (now == lastKnown) return;
        lastKnown = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }
}
