package com.neoalive.tacz_sewv.grace;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketGraceStart;
import com.neoalive.tacz_sewv.notify.HudNotify;

/**
 * Drives the grace period: starts it on a fresh world, counts the days down on the HUD, ends it,
 * and shows each player the "Gathering Storm" popup once. What grace actually suppresses lives at
 * the spawn sites ({@code AmbientSpawnGate}, {@code ProbeConsumer}, {@code BerezkaStructureCompat}),
 * all reading {@link GracePeriodData#active}.
 */
public final class GraceTickHandler {

    /** A world younger than this on its first start counts as new; older ones never get grace. */
    private static final long NEW_WORLD_TICKS = 1200L;

    private GraceTickHandler() {}

    public static void start(MinecraftServer server, long ticks) {
        GracePeriodData data = GracePeriodData.get(server);
        if (data == null) return;
        data.start(server.overworld().getGameTime(), ticks);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) showPopup(data, sp);
    }

    public static void stop(MinecraftServer server) {
        GracePeriodData data = GracePeriodData.get(server);
        if (data != null) data.stop();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        GracePeriodData data = GracePeriodData.get(server);
        GracePeriodData.refreshCache(data);
        if (data == null || data.initialized) return;
        data.initialized = true;
        data.setDirty();
        int days = SewvConfig.GRACE_PERIOD_DAYS.get();
        if (days > 0 && server.overworld().getGameTime() < NEW_WORLD_TICKS) {
            start(server, days * GracePeriodData.DAY_TICKS);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GracePeriodData.refreshCache(null);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || !GracePeriodData.active(sp.level())) return;
        GracePeriodData data = GracePeriodData.get(sp.server);
        if (data != null && !data.acknowledged.contains(sp.getUUID())) showPopup(data, sp);
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD || level.getGameTime() % 20 != 0) return;
        GracePeriodData data = GracePeriodData.get(level.getServer());
        if (data == null || data.endTick < 0) return;

        long now = level.getGameTime();
        if (now >= data.endTick) {
            data.stop();
            broadcast(level.getServer(), Component.translatable("tacz_sewv.grace.over"));
            return;
        }
        int days = data.daysLeft(now);
        if (days != data.lastAnnouncedDays) {
            data.lastAnnouncedDays = days;
            data.setDirty();
            broadcast(level.getServer(), Component.translatable("tacz_sewv.grace.days_left", days));
        }
    }

    private static void showPopup(GracePeriodData data, ServerPlayer sp) {
        data.acknowledged.add(sp.getUUID());
        data.setDirty();
        int days = data.daysLeft(sp.level().getGameTime());
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new PacketGraceStart(days));
    }

    private static void broadcast(MinecraftServer server, Component body) {
        Component title = Component.translatable("tacz_sewv.grace.title");
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) HudNotify.grace(sp, title, body);
    }
}
