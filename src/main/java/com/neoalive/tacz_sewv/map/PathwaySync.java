package com.neoalive.tacz_sewv.map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.neoalive.tacz_sewv.TaczSewv;

@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class PathwaySync {

    private PathwaySync() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PreferredPathwayData.syncTo(player);
        }
    }
}
