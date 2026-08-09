package com.neoalive.tacz_sewv.map;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.block.TrenchNetworks;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketTrenchNetworks;

/**
 * Syncs trench-network centroids to clients for Xaero map markers. Same cadence / tick-counter
 * discipline as {@link OwnedVehicleTracker}; stage 1 sends all networks in all dimensions.
 */
public final class TrenchNetworksTracker {

    private static int nextSend = Integer.MIN_VALUE;

    private TrenchNetworksTracker() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        nextSend = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        nextSend = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            nextSend = Integer.MIN_VALUE;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        int now = event.getServer().getTickCount();
        if (now < nextSend) return;
        nextSend = now + SewvConfig.MAP_SYNC_INTERVAL_TICKS.get();

        List<TrenchMarker> markers = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (TrenchNetworks.Network network : TrenchNetworks.get(level).networks()) {
                markers.add(new TrenchMarker(
                        network.id(),
                        network.x(),
                        network.y(),
                        network.z(),
                        level.dimension(),
                        network.cellCount(),
                        network.hasEmplacement()));
            }
        }

        PacketTrenchNetworks packet = new PacketTrenchNetworks(markers);
        for (ServerPlayer player : players) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
