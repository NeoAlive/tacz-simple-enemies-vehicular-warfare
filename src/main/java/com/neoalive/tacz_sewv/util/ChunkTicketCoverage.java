package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * "Does a player already keep this chunk ticking?" for {@link ChunkTicket}, plus the registry
 * that keeps a suspended ticket alive.
 *
 * <p>A ticket dropped because a player covers its owner can only be taken back by something
 * that still runs when the player leaves — and the owner's own goal does not, the moment its
 * chunk stops entity-ticking. So every suspended ticket is registered here and re-checked each
 * server tick, independent of the owner: a teleport, a respawn or a player outrunning the
 * margin re-forces the chunk at the end of that same tick, before the chunk system gets round
 * to unloading anything. Without this a suspended aircraft could freeze until someone came
 * back for it. Server thread only.
 */
public final class ChunkTicketCoverage {

    private record Suspended(ChunkTicket ticket, Entity owner) {}

    private static final List<Suspended> SUSPENDED = new ArrayList<>();

    /** Orphaned-ticket sweep cadence, in server ticks; the orphan threshold itself is 400. */
    private static final int ORPHAN_SWEEP_INTERVAL = 100;

    private ChunkTicketCoverage() {}

    static boolean enabled() {
        try {
            return SewvConfig.TICKET_SKIP_WHEN_PLAYER_TICKING.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int lingerTicks() {
        try {
            return SewvConfig.TICKET_LINGER_TICKS.get();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static int maxLingering() {
        try {
            return SewvConfig.TICKET_MAX_LINGERING.get();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Whether the ticket for {@code pos} should be (or stay) suspended. See {@link ChunkTicketPolicy#nextSuspended}. */
    static boolean wantSuspended(boolean suspended, ServerLevel level, ChunkPos pos) {
        int release;
        int acquire;
        try {
            release = SewvConfig.TICKET_RELEASE_MARGIN_CHUNKS.get();
            acquire = SewvConfig.TICKET_ACQUIRE_MARGIN_CHUNKS.get();
        } catch (Throwable ignored) {
            return false;
        }
        int sim = level.getServer().getPlayerList().getSimulationDistance();
        return ChunkTicketPolicy.nextSuspended(suspended, nearestPlayerChunks(level, pos), sim, release, acquire);
    }

    /** Chebyshev chunk distance to the nearest non-spectator player in {@code level}, or -1 if none. */
    private static int nearestPlayerChunks(ServerLevel level, ChunkPos pos) {
        int best = -1;
        for (ServerPlayer p : level.players()) {
            // Conservative: a spectator's tickets are not relied on to keep anything running.
            if (p.isSpectator()) continue;
            ChunkPos pc = p.chunkPosition();
            int d = ChunkTicketPolicy.chunkDistance(pc.x, pc.z, pos.x, pos.z);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    static void register(ChunkTicket ticket, Entity owner) {
        SUSPENDED.add(new Suspended(ticket, owner));
    }

    static void unregister(ChunkTicket ticket) {
        SUSPENDED.removeIf(s -> s.ticket() == ticket);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % ORPHAN_SWEEP_INTERVAL == 0) ChunkTicket.sweepOrphans();
        if (SUSPENDED.isEmpty()) return;
        boolean on = enabled();
        Suspended[] snapshot = SUSPENDED.toArray(new Suspended[0]);
        for (Suspended s : snapshot) {
            Entity owner = s.owner();
            if (owner.isRemoved() || !(owner.level() instanceof ServerLevel level)) {
                SUSPENDED.remove(s);
                s.ticket().dropSuspension();
                continue;
            }
            if (on && wantSuspended(true, level, new ChunkPos(owner.blockPosition()))) continue;
            SUSPENDED.remove(s);
            s.ticket().resumeFromCoverage(level, owner);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SUSPENDED.clear();
        ChunkTicket.forgetAll();
        com.neoalive.tacz_sewv.debug.PlanePerf.ticketSuspendedNow = 0;
    }
}
