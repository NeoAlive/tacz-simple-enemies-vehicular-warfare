package com.neoalive.tacz_sewv.zone;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSpawnZoneState;

/**
 * Operators with the zone map open. Each gets the whole picture of their dimension on open and
 * again whenever that dimension's zones change, checked once a second.
 */
public final class SpawnZoneViewers {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int PASS_TICKS = 20;

    /** Viewer to the (dimension, revision) it last received. */
    private static final Map<UUID, String> SENT = new HashMap<>();

    private SpawnZoneViewers() {}

    public static void open(ServerPlayer player) {
        SENT.put(player.getUUID(), "");
        push(player);
    }

    public static void close(UUID id) {
        SENT.remove(id);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SENT.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PASS_TICKS != 0) return;
        for (UUID id : SENT.keySet().toArray(new UUID[0])) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.hasPermissions(2)) {
                SENT.remove(id);
                continue;
            }
            push(p);
        }
    }

    private static void push(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ZoneLayers layers = SpawnZoneData.get(level).layers();
        String dim = level.dimension().location().toString();
        String stamp = dim + "#" + layers.revision();
        if (stamp.equals(SENT.get(player.getUUID()))) return;
        SENT.put(player.getUUID(), stamp);
        double px = player.getX(), pz = player.getZ();
        long[] prio = nearest(layers.manualOf(ZoneType.PRIORITY), px, pz);
        long[] manualNo = nearest(layers.manualOf(ZoneType.NOZONE), px, pz);
        long[] beaconNo = nearest(layers.beaconCovered(), px, pz);
        int n = 0;
        for (ZoneLayers.Beacon ignored : layers.beacons()) n++;
        long[] pos = new long[n];
        int[] radius = new int[n];
        boolean[] powered = new boolean[n];
        int i = 0;
        for (ZoneLayers.Beacon b : layers.beacons()) {
            pos[i] = b.pos;
            radius[i] = b.radius;
            powered[i] = b.powered;
            i++;
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketSpawnZoneState(dim, prio, manualNo, beaconNo, pos, radius, powered));
    }

    /** The layer, trimmed to the chunks nearest the viewer when it is over the wire cap. */
    private static long[] nearest(long[] keys, double px, double pz) {
        int cap = PacketSpawnZoneState.MAX_PER_LAYER;
        if (keys.length <= cap) return keys;
        LOG.warn("Spawn zone layer has {} chunks; sending the nearest {} to the map", keys.length, cap);
        Long[] boxed = Arrays.stream(keys).boxed().toArray(Long[]::new);
        Arrays.sort(boxed, (a, b) -> Double.compare(ZoneGeometry.distSqToChunkCentre(px, pz, a),
                ZoneGeometry.distSqToChunkCentre(px, pz, b)));
        long[] out = new long[cap];
        for (int i = 0; i < cap; i++) out[i] = boxed[i];
        return out;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (SENT.containsKey(event.getEntity().getUUID())) SENT.put(event.getEntity().getUUID(), "");
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SENT.clear();
    }
}
