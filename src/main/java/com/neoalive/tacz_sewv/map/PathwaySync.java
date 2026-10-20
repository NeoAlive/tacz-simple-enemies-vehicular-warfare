package com.neoalive.tacz_sewv.map;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.neoalive.tacz_sewv.TaczSewv;

@EventBusSubscriber(modid = TaczSewv.MODID)
public final class PathwaySync {

    private PathwaySync() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PreferredPathwayData.syncTo(player);
        }
    }
}
