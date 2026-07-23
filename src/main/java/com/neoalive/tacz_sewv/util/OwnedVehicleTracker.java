package com.neoalive.tacz_sewv.util;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOwnedVehicles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends each player the positions of the vehicles their own PMC crews are riding, for the map
 * markers in {@code client.xaero}.
 *
 * <p>This exists because the client only knows about entities the server is tracking it — roughly
 * view distance — while the map draws the whole explored world. Without the sync a hull two chunks
 * past the tracking edge simply has no client entity to read, so the marker would blink out exactly
 * when the map becomes the useful way to look at it.
 *
 * <p>Sends are keyed on the crew's owner, so a player only ever receives their own units; see
 * {@link PacketOwnedVehicles}. A player with nothing to show gets one empty list and then nothing,
 * which is what lets the client's staleness timeout clear the map by itself.
 */
public final class OwnedVehicleTracker {

    /**
     * Hard cap per player. Well past any plausible number of crewed hulls one commander owns; it is
     * here so a creative-mode spawn spree cannot turn a per-second packet into a bandwidth problem.
     */
    private static final int MAX_MARKERS = 128;

    /**
     * Deadline on the server's own tick counter, not on world game time: game time rewinds when a
     * different world is loaded in the same session, and a deadline set from the previous world's
     * clock would then sit in the future for hours.
     */
    private static int nextSend = Integer.MIN_VALUE;
    /** Players who were sent a non-empty list last time — the ones that still need an empty one. */
    private static final Set<UUID> HAD_MARKERS = new HashSet<>();

    private OwnedVehicleTracker() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SewvConfig.MAP_MARKERS_ENABLED.get()) return;

        int now = event.getServer().getTickCount();
        if (now < nextSend) return;
        nextSend = now + SewvConfig.MAP_SYNC_INTERVAL_TICKS.get();

        Map<UUID, List<VehicleMarker>> byOwner = new HashMap<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            collect(level, byOwner);
        }

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            List<VehicleMarker> markers = byOwner.get(player.getUUID());
            if (markers == null) {
                // Only worth telling them there is nothing once — after that silence says the same
                // thing, and the client times the list out on its own.
                if (!HAD_MARKERS.remove(player.getUUID())) continue;
                markers = List.of();
            } else {
                HAD_MARKERS.add(player.getUUID());
            }
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketOwnedVehicles(markers));
        }
    }

    // ponytail: one whole-level entity scan per interval (once a second by default). Fine at the
    // entity counts this mod produces; if it ever shows on a profile, index hulls by owner as they
    // are crewed instead of rediscovering them.
    private static void collect(ServerLevel level, Map<UUID, List<VehicleMarker>> byOwner) {
        for (VehicleEntity hull : level.getEntities(EntityTypeTest.forClass(VehicleEntity.class), h -> true)) {
            UUID owner = CrewFacts.pmcOwner(hull);
            if (owner == null) continue;

            List<VehicleMarker> markers = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
            if (markers.size() >= MAX_MARKERS) continue;

            Entity driver = hull.getFirstPassenger();
            if (driver == null) continue; // crewed by definition, but the list can change under us

            markers.add(new VehicleMarker(
                    driver.getId(), hull.getId(), hull.getX(), hull.getY(), hull.getZ(),
                    hull.getYRot(), kindOf(hull), level.dimension()));
        }
    }

    /**
     * SuperbWarfare's engine type, narrowed to the classes a map symbol would distinguish.
     *
     * <p>Read from {@code computed()} (the static datapack data), never {@code getEngineInfo()} —
     * that field is populated lazily on the hull's first {@code travel()}, so it is null for a hull
     * that has not moved yet, which on a parked emplacement is forever.
     */
    private static VehicleMarker.Kind kindOf(VehicleEntity hull) {
        EngineType engine = hull.computed().getEngineType();
        if (engine == null) return VehicleMarker.Kind.WHEEL;
        return switch (engine) {
            case TRACK -> VehicleMarker.Kind.TRACK;
            case SHIP -> VehicleMarker.Kind.SHIP;
            case HELICOPTER, AIRCRAFT -> VehicleMarker.Kind.HELICOPTER;
            case FIXED -> VehicleMarker.Kind.FIXED;
            default -> VehicleMarker.Kind.WHEEL;
        };
    }
}
