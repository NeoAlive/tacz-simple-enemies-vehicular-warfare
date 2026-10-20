package com.neoalive.tacz_sewv.invasion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketInvasionHud;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * Pushes {@link PacketInvasionHud} while a session is active (~1s). Layout lives on
 * {@link InvasionSession}; this only refreshes state.
 */
public final class InvasionHudTracker {

    private static final int INTERVAL_TICKS = 20;

    private InvasionHudTracker() {}

    public static void push(ServerLevel level) {
        InvasionSession session = InvasionSession.of(level);
        InvasionHud.Layout layout = session.hudLayout();
        if (layout == null) return;
        InvasionHud.Snapshot snap = InvasionHud.snapshot(level, layout);
        PacketInvasionHud packet = PacketInvasionHud.of(level, snap, layout);
        for (ServerPlayer player : level.players()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static void clear(ServerLevel level) {
        PacketInvasionHud packet = PacketInvasionHud.clearPacket();
        for (ServerPlayer player : level.players()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL_TICKS != 0) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (!InvasionSession.isActive(level)) continue;
            if (InvasionSession.of(level).hudLayout() == null) continue;
            push(level);
        }
    }
}
