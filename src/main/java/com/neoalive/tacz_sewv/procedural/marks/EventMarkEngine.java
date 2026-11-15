package com.neoalive.tacz_sewv.procedural.marks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.procedural.events.DynamicEventManager;
import net.nekoyuni.SimpleEnemyMod.procedural.events.system.DynamicEvent;
import net.nekoyuni.SimpleEnemyMod.world.data.EventProbabilityData;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.notify.HudNotify;
import com.neoalive.tacz_sewv.procedural.events.EventBand;
import com.neoalive.tacz_sewv.spawn.AmbientSpawnGate;
import com.neoalive.tacz_sewv.zone.SpawnZones;

/**
 * The event mark engine: events start out in the world rather than being rolled on top of the player.
 *
 * <p>Each online player is dealt marks (a spot and an event) one to two simulated radii past the edge
 * of what the server simulates, judged by {@link MarkSites} without loading anything. A mark fires the
 * first time it is crossed, when a player's simulated area reaches it, so the event appears at the
 * farthest distance where it can actually run. A mark that is never reached expires and is replaced,
 * so expiry sets the pace for players who travel; an AFK player crosses nothing. A player who stays in
 * one area but keeps playing has their marks creep in toward them ({@link SewvConfig#EVENT_MARK_CREEP}).
 *
 * <p>SEM's own roller keeps the anchored events (cave, base shelling) and everything else while the
 * engine is off: legacy config, or a server simulating under {@link MarkRules#MIN_SIMULATED_CHUNKS}
 * chunks, where there is no room past the edge.
 */
public final class EventMarkEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int STEP_TICKS = 20;
    /** Marks this close together would read as one event. */
    private static final double MIN_MARK_SPACING = 96.0;
    /** A player whose last input is older than this is idle and never crept on. */
    private static final long ACTIVE_MS = 60_000L;
    /** Moving this far resets a player's stationary clock. */
    private static final double ANCHOR_RADIUS = 32.0;

    private static final MarkDeck DECK = new MarkDeck();
    private static final Random RANDOM = new Random();
    /** Game time each player's marks may fire for them again. Transient: a restart forgives. */
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

    private record Anchor(double x, double z, long since) {}

    private EventMarkEngine() {}

    /** Whether marks own the non-anchored events right now. */
    public static boolean running(ServerLevel level) {
        if (SewvConfig.LEGACY_EVENT_ENGINE.get()) return false;
        PlayerList list = level.getServer().getPlayerList();
        if (Math.min(list.getViewDistance(), list.getSimulationDistance()) < MarkRules.MIN_SIMULATED_CHUNKS) {
            return false;
        }
        return !MarkEvents.unavailable();
    }

    private static int radius(ServerLevel level) {
        PlayerList list = level.getServer().getPlayerList();
        return EventBand.simulatedRadius(list.getViewDistance(), list.getSimulationDistance());
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % STEP_TICKS != 0) return;
        if (!running(level)) return;
        step(level);
    }

    private static void step(ServerLevel level) {
        EventMarkData data = EventMarkData.get(level);
        long now = level.getGameTime();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) players.add(p);
        }
        int radius = radius(level);

        if (data.marks().removeIf(m -> now >= m.expiresAt || MarkEvents.get(m.eventId) == null)) data.setDirty();
        updateAnchors(players, now);

        Iterator<EventMarkData.Mark> it = data.marks().iterator();
        while (it.hasNext()) {
            EventMarkData.Mark mark = it.next();
            BlockPos at = new BlockPos((int) Math.floor(mark.x), level.getSeaLevel(), (int) Math.floor(mark.z));
            if (!level.isPositionEntityTicking(at)) {
                if (creep(level, mark, players, radius, now)) data.setDirty();
                continue;
            }
            if (!mark.crossed) {
                mark.crossed = true;
                data.setDirty();
                if (RANDOM.nextDouble() >= SewvConfig.EVENT_MARK_TRIGGER_CHANCE.get()) {
                    it.remove();
                    continue;
                }
            }
            ServerPlayer player = nearest(players, mark);
            if (player == null) continue;
            if (Math.sqrt(distSq(player, mark)) < radius * MarkRules.MIN_FIRE_SHARE) {
                // Reached it from too close (a teleport, a fast flight): firing now would be on top of them.
                it.remove();
                data.setDirty();
                continue;
            }
            if (now < COOLDOWN.getOrDefault(player.getUUID(), Long.MIN_VALUE)) continue;
            if (!AmbientSpawnGate.allows(level)) continue;
            fire(level, mark, player, now);
            it.remove();
            data.setDirty();
        }

        deal(level, data, players, radius, now);
    }

    private static void fire(ServerLevel level, EventMarkData.Mark mark, ServerPlayer player, long now) {
        DynamicEvent event = MarkEvents.get(mark.eventId);
        if (event == null || !EventProbabilityData.get(level).isEventActive(mark.eventId)) return;
        if (!event.canExecute(level, player)) return;
        BlockPos pos = MarkSites.settle(level, mark.x, mark.z, MarkEvents.profile(mark.eventId), level.random);
        if (pos == null || !AmbientSpawnGate.allowsAt(level, pos)) {
            LOGGER.debug("[sewv] event mark {} at {},{} had no footing", mark.eventId, (int) mark.x, (int) mark.z);
            return;
        }
        boolean ok;
        try {
            ok = event.execute(level, player, pos);
        } catch (Throwable t) {
            LOGGER.warn("[sewv] event mark {} failed to run", mark.eventId, t);
            ok = false;
        }
        LOGGER.info("[sewv] event mark {} at {} for {}: {}", mark.eventId, pos.toShortString(),
                player.getGameProfile().getName(), ok ? "fired" : "declined");
        if (!ok) return;
        HudNotify.eventNearby(player, mark.eventId, pos);
        COOLDOWN.put(player.getUUID(), now + SewvConfig.EVENT_MARK_COOLDOWN_SECONDS.get() * 20L);
        try {
            DynamicEventManager.getCooldownManager().triggerCooldown(player);
        } catch (Throwable ignored) {
            // An SEM build without the cooldown manager only loses the shared cooldown.
        }
    }

    /**
     * Candidates proposed per election; the background thread keeps the flattest that suits. Vehicle
     * events get twice as many: in forest and hills most spots fail them, and a rejected spot costs
     * one height read, not a footprint.
     */
    private static final int CANDIDATES = 8;
    private static final int VEHICLE_CANDIDATES = 16;
    /** An election still running after this long is dropped (a stalled worker must not stop dealing). */
    private static final long ELECTION_TIMEOUT_TICKS = 600L;

    /** The one election in flight: who it is for, which event, and the spots being judged. */
    private record Election(UUID owner, String eventId, List<MarkSites.Candidate> candidates,
                            CompletableFuture<Integer> result, long started) {}

    @Nullable
    private static Election pending;

    /**
     * One election at a time. The server thread deals the owner (the deck), picks the event and
     * proposes spots that pass the cheap tests; the footprint noise is read on a background thread;
     * a later step adds the winner if it is still clear.
     */
    private static void deal(ServerLevel level, EventMarkData data, List<ServerPlayer> players, int radius, long now) {
        if (pending != null) {
            Election e = pending;
            if (!e.result().isDone()) {
                if (now - e.started() > ELECTION_TIMEOUT_TICKS) pending = null;
                return;
            }
            pending = null;
            int pick = e.result().getNow(-1);
            if (pick < 0) return;
            MarkSites.Candidate c = e.candidates().get(pick);
            double clear = radius + MarkEvents.reach(e.eventId()) + 16.0;
            if (nearAnyPlayer(players, c.x(), c.z(), clear) || nearAnyMark(data, c.x(), c.z())) return;
            data.add(e.eventId(), c.x() + 0.5, c.z() + 0.5, now + SewvConfig.EVENT_MARK_EXPIRY_SECONDS.get() * 20L,
                    e.owner());
            LOGGER.debug("[sewv] event mark {} elected at {},{}", e.eventId(), c.x(), c.z());
            return;
        }
        if (players.isEmpty()) return;
        int perPlayer = SewvConfig.EVENT_MARKS_PER_PLAYER.get();
        Map<UUID, Integer> held = new HashMap<>();
        for (EventMarkData.Mark m : data.marks()) held.merge(m.owner, 1, Integer::sum);
        Map<UUID, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer p : players) online.put(p.getUUID(), p);
        UUID owner = DECK.next(online.keySet(),
                u -> held.getOrDefault(u, 0) < perPlayer && now >= COOLDOWN.getOrDefault(u, Long.MIN_VALUE), RANDOM);
        if (owner == null) return;
        ServerPlayer player = online.get(owner);

        DynamicEvent event = pickEvent(level, player);
        if (event == null) return;
        String id = event.getId();
        int reach = MarkEvents.reach(id);
        double[] band = MarkRules.electionBand(radius);
        double clear = radius + reach + 16.0;
        List<MarkSites.Candidate> candidates = new ArrayList<>();
        int wanted = MarkEvents.profile(id) == MarkRules.Profile.VEHICLE ? VEHICLE_CANDIDATES : CANDIDATES;
        for (int i = 0; i < wanted; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double d = band[0] + RANDOM.nextDouble() * (band[1] - band[0]);
            int x = (int) Math.floor(player.getX() + Math.cos(a) * d);
            int z = (int) Math.floor(player.getZ() + Math.sin(a) * d);
            if (nearAnyPlayer(players, x, z, clear) || nearAnyMark(data, x, z)) continue;
            if (!SpawnZones.allowsChunk(level, x >> 4, z >> 4)) continue;
            // SEM's own biome weight; reads the biome without loading the chunk.
            double biome = event.getBiomeModifier(level, new BlockPos(x, level.getSeaLevel(), z));
            if (RANDOM.nextDouble() >= biome) continue;
            candidates.add(new MarkSites.Candidate(x, z));
        }
        if (candidates.isEmpty()) return;
        pending = new Election(owner, id, candidates,
                MarkSites.elect(level, candidates, MarkEvents.profile(id), reach), now);
    }

    /** Weighted by each event's own base chance, among events SEM has enabled and that will run for this player. */
    @Nullable
    private static DynamicEvent pickEvent(ServerLevel level, ServerPlayer player) {
        EventProbabilityData flags = EventProbabilityData.get(level);
        List<DynamicEvent> pool = new ArrayList<>();
        double total = 0.0;
        for (DynamicEvent e : MarkEvents.markedEvents()) {
            if (!flags.isEventActive(e.getId()) || !e.canExecute(level, player)) continue;
            double w = Math.max(0.0, e.getBaseChance());
            if (w <= 0.0) continue;
            pool.add(e);
            total += w;
        }
        if (pool.isEmpty()) return null;
        double roll = RANDOM.nextDouble() * total;
        for (DynamicEvent e : pool) {
            roll -= Math.max(0.0, e.getBaseChance());
            if (roll <= 0.0) return e;
        }
        return pool.get(pool.size() - 1);
    }

    private static void updateAnchors(List<ServerPlayer> players, long now) {
        Map<UUID, Anchor> kept = new HashMap<>();
        for (ServerPlayer p : players) {
            Anchor a = ANCHORS.get(p.getUUID());
            double dx = a == null ? 0 : p.getX() - a.x();
            double dz = a == null ? 0 : p.getZ() - a.z();
            if (a == null || dx * dx + dz * dz > ANCHOR_RADIUS * ANCHOR_RADIUS) a = new Anchor(p.getX(), p.getZ(), now);
            kept.put(p.getUUID(), a);
        }
        ANCHORS.clear();
        ANCHORS.putAll(kept);
    }

    /** Moves a mark toward its owner when the owner has stayed put but is still playing. */
    private static boolean creep(ServerLevel level, EventMarkData.Mark mark, List<ServerPlayer> players, int radius,
                                 long now) {
        if (!SewvConfig.EVENT_MARK_CREEP.get()) return false;
        ServerPlayer owner = null;
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(mark.owner)) owner = p;
        }
        if (owner == null) return false;
        Anchor a = ANCHORS.get(owner.getUUID());
        long idle = SewvConfig.EVENT_MARK_CREEP_IDLE_SECONDS.get() * 20L;
        if (a == null || now - a.since() < idle) return false;
        if (Util.getMillis() - owner.getLastActionTime() > ACTIVE_MS) return false;
        double[] next = MarkRules.creep(mark.x, mark.z, owner.getX(), owner.getZ(), radius);
        if (!SpawnZones.allowsChunk(level, (int) Math.floor(next[0]) >> 4, (int) Math.floor(next[1]) >> 4)) return false;
        mark.x = next[0];
        mark.z = next[1];
        return true;
    }

    @Nullable
    private static ServerPlayer nearest(List<ServerPlayer> players, EventMarkData.Mark mark) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            double d = distSq(p, mark);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static double distSq(ServerPlayer p, EventMarkData.Mark mark) {
        double dx = p.getX() - mark.x;
        double dz = p.getZ() - mark.z;
        return dx * dx + dz * dz;
    }

    private static boolean nearAnyPlayer(List<ServerPlayer> players, int x, int z, double r) {
        for (ServerPlayer p : players) {
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    private static boolean nearAnyMark(EventMarkData data, int x, int z) {
        for (EventMarkData.Mark m : data.marks()) {
            double dx = m.x - x;
            double dz = m.z - z;
            if (dx * dx + dz * dz < MIN_MARK_SPACING * MIN_MARK_SPACING) return true;
        }
        return false;
    }

    /** One line per mark for debugging: event, position, distance to the nearest player, expiry. */
    public static String describe(ServerLevel level) {
        StringBuilder sb = new StringBuilder("running=").append(running(level)).append(" radius=").append(radius(level));
        long now = level.getGameTime();
        for (EventMarkData.Mark m : EventMarkData.get(level).marks()) {
            ServerPlayer p = nearest(new ArrayList<>(level.players()), m);
            sb.append('\n').append(m.eventId).append(" @ ").append((int) m.x).append(',').append((int) m.z)
                    .append(" d=").append(p == null ? -1 : (int) Math.sqrt(distSq(p, m)))
                    .append(" crossed=").append(m.crossed)
                    .append(" ttl=").append((m.expiresAt - now) / 20).append('s');
        }
        return sb.toString();
    }
}
