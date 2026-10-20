package com.neoalive.tacz_sewv.grace;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import com.neoalive.tacz_sewv.compat.BerezkaStructureCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketGraceStart;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * Drives the grace period: starts it on a fresh world, counts the days down on the HUD, ends it,
 * and shows each player the "Gathering Storm" popup once. What grace actually suppresses lives at
 * the spawn sites ({@code AmbientSpawnGate}, {@code ProbeConsumer}, {@code BerezkaStructureCompat}),
 * all reading {@link GracePeriodData#active}.
 */
public final class GraceTickHandler {

    /** A world younger than this on its first start counts as new; older ones never get grace. */
    private static final long NEW_WORLD_TICKS = 1200L;
    /** Horizontal distance (squared) a player must walk from where they stood before the popup shows. */
    private static final double MOVE_SQR = 1.5 * 1.5;

    /**
     * Player -> where they stood when the popup was queued. It shows on their first real move: sent at
     * login it landed while the client was still on the terrain-loading screen, whose close wiped it,
     * and it was still recorded as seen. Transient: an unshown popup re-queues on the next login.
     */
    private static final Map<UUID, Vec3> PENDING = new HashMap<>();

    /** Both clocks at the last check; MIN_VALUE until the first one after a (re)load. */
    private static long lastGameTime = Long.MIN_VALUE;
    private static long lastDayTime = Long.MIN_VALUE;

    private GraceTickHandler() {}

    public static void start(MinecraftServer server, long ticks) {
        GracePeriodData data = GracePeriodData.get(server);
        if (data == null) return;
        data.start(ticks);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) PENDING.put(sp.getUUID(), sp.position());
    }

    public static void stop(MinecraftServer server) {
        GracePeriodData data = GracePeriodData.get(server);
        if (data != null) data.stop();
        PENDING.clear();
        if (ModList.get().isLoaded(BerezkaStructureCompat.MODID)) BerezkaStructureCompat.clearGraceBoxes();
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
        PENDING.clear();
        lastGameTime = Long.MIN_VALUE;
        lastDayTime = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || !GracePeriodData.active(sp.level())) return;
        GracePeriodData data = GracePeriodData.get(sp.server);
        if (data != null && !data.acknowledged.contains(sp.getUUID())) {
            PENDING.put(sp.getUUID(), sp.position());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD || level.getGameTime() % 20 != 0) return;
        GracePeriodData data = GracePeriodData.get(level.getServer());
        long game = level.getGameTime(), day = level.getDayTime();
        // Spend whichever clock moved further: a forward /time skip or a night slept counts, a
        // backward /time set cannot refund grace, and a frozen daylight cycle still lets it run out.
        long elapsed = lastGameTime == Long.MIN_VALUE ? 0 : Math.max(game - lastGameTime, day - lastDayTime);
        lastGameTime = game;
        lastDayTime = day;
        if (data == null || data.remaining <= 0) return;

        if (data.spend(elapsed)) {
            PENDING.clear();
            data.stop();
            if (ModList.get().isLoaded(BerezkaStructureCompat.MODID)) BerezkaStructureCompat.clearGraceBoxes();
            broadcast(level.getServer(), Component.translatable("tacz_sewv.grace.over"));
            return;
        }
        int days = data.daysLeft();
        if (days != data.lastAnnouncedDays) {
            data.lastAnnouncedDays = days;
            data.setDirty();
            broadcast(level.getServer(), Component.translatable("tacz_sewv.grace.days_left", days));
        }
        for (Iterator<Map.Entry<UUID, Vec3>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Vec3> due = it.next();
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(due.getKey());
            if (sp == null) {
                it.remove();
                continue;
            }
            Vec3 from = due.getValue();
            double dx = sp.getX() - from.x, dz = sp.getZ() - from.z;
            if (dx * dx + dz * dz < MOVE_SQR) continue;
            it.remove();
            if (!data.acknowledged.contains(sp.getUUID())) showPopup(data, sp);
        }
    }

    private static void showPopup(GracePeriodData data, ServerPlayer sp) {
        data.acknowledged.add(sp.getUUID());
        data.setDirty();
        int days = data.daysLeft();
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new PacketGraceStart(days));
    }

    private static void broadcast(MinecraftServer server, Component body) {
        Component title = Component.translatable("tacz_sewv.grace.title");
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) HudNotify.grace(sp, title, body);
    }
}
