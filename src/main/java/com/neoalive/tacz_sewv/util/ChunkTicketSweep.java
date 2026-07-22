package com.neoalive.tacz_sewv.util;

import com.mojang.datafixers.util.Pair;
import com.neoalive.tacz_sewv.TaczSewv;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Reconciles forced-chunk tickets {@link ChunkTicket} left behind when a mortar/heli crew died
 * or was discarded without its owning goal ever calling {@code stop()} — vanilla gives no such
 * guarantee on entity removal, only when a goal ends on its own terms (a dead/discarded entity
 * simply stops ticking, and nothing calls {@code goalSelector.tick()} again to reach it). Forge
 * persists entity-owned forced-chunk tickets in save data across every reload with no built-in
 * liveness check, so a crew killed mid-operation can leave a chunk force-loaded forever.
 *
 * <p>Two-phase by necessity, verified against Forge's own {@code ForgeChunkManager} source
 * (47.2.0): {@link #stage} runs from {@code validateTickets}, which fires BEFORE this level's
 * tickets are reinstated — at that exact instant no entity has loaded yet (loading one is what
 * the ticket itself is about to cause), so "is this owner still alive" cannot be answered there.
 * It only records candidates. {@link #onLevelTick} resolves them a short delay later, once the
 * now-reinstated ticket has had time to actually load its chunk — if the owner is truly gone, it
 * will NOT be found there — then releases genuine orphans through the same
 * {@code ForgeChunkManager.forceChunk(..., false, ...)} call {@link ChunkTicket#release} uses.
 */
public final class ChunkTicketSweep {

    private ChunkTicketSweep() {}

    // ponytail: fixed delay, not "chunk actually loaded" — fine for something this rare (a crew
    // dying mid-mission); poll level.getChunkSource().getChunk(x, z, false) != null per candidate
    // instead if a slow disk or a huge load queue ever produces a false-positive sweep.
    private static final int SWEEP_DELAY_TICKS = 600; // 30s

    private record Candidate(UUID owner, long chunkPos, boolean ticking) {}

    private static final class Pending {
        int ticksLeft = SWEEP_DELAY_TICKS;
        final List<Candidate> candidates;

        Pending(List<Candidate> candidates) {
            this.candidates = candidates;
        }
    }

    // Keyed by level rather than a single global list: a server can run more than one dimension,
    // each with its own save data and its own validateTickets call.
    private static final Map<ServerLevel, Pending> PENDING = new WeakHashMap<>();

    /** Call once from {@code FMLCommonSetupEvent} — the API's own contract requires it. */
    public static void register(FMLCommonSetupEvent event) {
        event.enqueueWork(() ->
                ForgeChunkManager.setForcedChunkLoadingCallback(TaczSewv.MODID, ChunkTicketSweep::stage));
    }

    private static void stage(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, Pair<LongSet, LongSet>> entry : helper.getEntityTickets().entrySet()) {
            UUID owner = entry.getKey();
            for (long chunk : entry.getValue().getFirst()) candidates.add(new Candidate(owner, chunk, false));
            for (long chunk : entry.getValue().getSecond()) candidates.add(new Candidate(owner, chunk, true));
        }
        if (!candidates.isEmpty()) PENDING.put(level, new Pending(candidates));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (PENDING.isEmpty()) return; // the entire cost on every tick this mod has nothing pending
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;

        Pending pending = PENDING.get(level);
        if (pending == null) return;
        if (--pending.ticksLeft > 0) return;

        PENDING.remove(level);
        for (Candidate c : pending.candidates) {
            if (level.getEntity(c.owner()) != null) continue; // still alive — leave its ticket alone
            ChunkPos pos = new ChunkPos(c.chunkPos());
            ForgeChunkManager.forceChunk(level, TaczSewv.MODID, c.owner(), pos.x, pos.z, false, c.ticking());
        }
    }
}
