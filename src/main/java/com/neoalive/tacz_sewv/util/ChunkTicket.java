package com.neoalive.tacz_sewv.util;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.debug.PlanePerf;

/**
 * One force-loaded chunk held on an entity's behalf, following it as it moves.
 *
 * <p>Long-range AI only works while its chunk keeps ticking: a helicopter flown beyond
 * simulation distance stops flying, and a mortar crew shooting 770 blocks stops existing
 * well before its own shells land. Every such goal holds one of these per entity it needs
 * kept alive — the crew AND the tube for a mortar, since the AI lives on the crew and it
 * can stand across a chunk boundary from the weapon.
 *
 * <p>Two refinements, both under the {@code chunk_tickets} config section and both bounded by
 * {@link ChunkTicketPolicy}'s constraint that the owner's own chunk must stay entity-ticking:
 * <ul>
 *   <li><b>Suspension</b> — while a player already entity-ticks the owner's chunk the ticket is
 *       dropped, and {@link ChunkTicketCoverage} takes it back before that stops being true;
 *   <li><b>Linger</b> — a chunk the owner has just left stays forced for a short while, so an
 *       aircraft circling across the same boundary re-adopts it instead of reloading it and
 *       re-saving it on every pass.
 * </ul>
 *
 * <p>Entity-owned tickets are self-cleaning: they are not restored across a world reload
 * without a validation callback, which suits a live-operation aid rather than persistent
 * world state.
 */
public final class ChunkTicket {

    /** Who holds the ticket, for the per-kind gauge in {@code /sewv debug perf}. */
    public enum Kind {
        HELI, PLANE, MORTAR_CREW, MORTAR_TUBE, VEHICLE_MORTAR, ARTILLERY, TYPE63_CREW, TYPE63_LAUNCHER
    }

    private final Kind kind;

    @Nullable
    private ChunkPos held;

    /** Dropped because a player covers the owner; registered with {@link ChunkTicketCoverage}. */
    private boolean suspended;

    private final ChunkTicketPolicy.Linger linger = new ChunkTicketPolicy.Linger();

    /**
     * Every ticket holding a chunk, for the orphan sweep. A ticket is only ever released from its
     * goal's {@code stop()}, and a goal whose crewman dies never gets one: the hull's chunk (and
     * its loading ring) then stayed forced for as long as the hull existed. Server thread only.
     */
    private static final java.util.Set<ChunkTicket> HELD =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** No live owner goes this long without a follow; every one calls at least every 60 ticks. */
    private static final long ORPHAN_TICKS = 400;

    private long lastTouch;
    @Nullable
    private Entity holder;
    @Nullable
    private ServerLevel holderLevel;

    public ChunkTicket(Kind kind) {
        this.kind = kind;
    }

    /** True while this instance has a forced chunk recorded locally. */
    public boolean isHeld() {
        return this.held != null;
    }

    /**
     * Keeps the owner's current chunk loaded. Safe to call every tick — the ticket is only
     * re-issued when the owner crosses into a different chunk.
     */
    public void follow(Entity owner) {
        PlanePerf.ticketFollowCalls++;
        if (!(owner.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        this.lastTouch = now;
        this.holder = owner;
        this.holderLevel = level;
        if (this.linger.size() > 0) {
            PlanePerf.ticketLingerExpiries += this.linger.expire(now, c -> force(level, owner, c, false));
        }

        ChunkPos want = new ChunkPos(owner.blockPosition());
        boolean coverage = ChunkTicketCoverage.enabled();
        if (coverage && ChunkTicketCoverage.wantSuspended(this.suspended, level, want)) {
            if (!this.suspended) suspend(level, owner, want);
            PlanePerf.ticketFollowNoops++;
            return;
        }
        if (this.suspended) unsuspend();

        if (want.equals(this.held)) {
            PlanePerf.ticketFollowNoops++;
            return;
        }

        PlanePerf.ticketFollowCrosses++;
        // Adopt before pushing the old chunk: at the cap, a push evicts the oldest lingering
        // chunk, which could be the very one being re-entered.
        boolean adopted = this.linger.adopt(want.toLong());
        if (this.held != null) {
            int ttl = ChunkTicketCoverage.lingerTicks();
            int cap = ttl > 0 ? ChunkTicketCoverage.maxLingering() : 0;
            this.linger.push(this.held.toLong(), now + ttl, cap, c -> force(level, owner, c, false));
        }
        if (adopted) PlanePerf.ticketLingerAdopts++;
        else force(level, owner, want.toLong(), true);
        setHeld(want);
    }

    /** Hands the chunk back. A null owner (already gone) just drops the record. */
    public void release(@Nullable Entity owner) {
        if (this.suspended) unsuspend();
        ServerLevel level = owner != null && owner.level() instanceof ServerLevel l ? l : null;
        this.linger.releaseAll(c -> {
            if (level != null) force(level, owner, c, false);
        });
        if (this.held == null) return;
        if (level != null) force(level, owner, this.held.toLong(), false);
        setHeld(null);
    }

    /**
     * Takes the ticket that {@code owner}'s own goal will adopt on its first tick.
     *
     * <p>A goal can only hold its chunk from a tick, and an entity spawned outside
     * simulation distance never gets one — so something spawned far away would sit frozen
     * until a player walked to it. This bootstraps the loop. Same mod id, owner and chunk
     * as {@link #follow}, so the goal adopts this ticket rather than stacking a second one,
     * and the goal's release hands it back.
     *
     * <p>Does not bump {@link PlanePerf#ticketHeldNow} — the adopting goal's {@link #follow}
     * owns that gauge once it records {@code held}.
     */
    public static void bootstrap(ServerLevel level, Entity owner) {
        force(level, owner, new ChunkPos(owner.blockPosition()).toLong(), true);
    }

    private void suspend(ServerLevel level, Entity owner, ChunkPos current) {
        this.linger.releaseAll(c -> force(level, owner, c, false));
        // With nothing recorded yet, the chunk may still carry the bootstrap ticket.
        long drop = this.held != null ? this.held.toLong() : current.toLong();
        force(level, owner, drop, false);
        setHeld(null);
        this.suspended = true;
        PlanePerf.ticketSuspendedNow++;
        PlanePerf.ticketSuspends++;
        ChunkTicketCoverage.register(this, owner);
    }

    private void unsuspend() {
        ChunkTicketCoverage.unregister(this);
        clearSuspension();
        PlanePerf.ticketResumes++;
    }

    /** Coverage lost while the owner may not be ticking: re-force from the server tick. */
    void resumeFromCoverage(ServerLevel level, Entity owner) {
        clearSuspension();
        PlanePerf.ticketResumes++;
        this.lastTouch = level.getGameTime();
        this.holder = owner;
        this.holderLevel = level;
        ChunkPos want = new ChunkPos(owner.blockPosition());
        force(level, owner, want.toLong(), true);
        setHeld(want);
    }

    /** The owner is gone: nothing to re-force, just forget the suspension. */
    void dropSuspension() {
        clearSuspension();
    }

    private void clearSuspension() {
        if (!this.suspended) return;
        this.suspended = false;
        if (PlanePerf.ticketSuspendedNow > 0) PlanePerf.ticketSuspendedNow--;
    }

    private void setHeld(@Nullable ChunkPos pos) {
        int k = this.kind.ordinal();
        if (this.held == null && pos != null) {
            PlanePerf.ticketHeldNow++;
            PlanePerf.ticketHeldByKind[k]++;
            HELD.add(this);
        } else if (this.held != null && pos == null) {
            if (PlanePerf.ticketHeldNow > 0) PlanePerf.ticketHeldNow--;
            if (PlanePerf.ticketHeldByKind[k] > 0) PlanePerf.ticketHeldByKind[k]--;
            HELD.remove(this);
        }
        this.held = pos;
    }

    /** Releases every held ticket its goal has stopped touching. Called from the server tick. */
    static void sweepOrphans() {
        if (HELD.isEmpty()) return;
        for (ChunkTicket t : HELD.toArray(new ChunkTicket[0])) {
            ServerLevel level = t.holderLevel;
            Entity owner = t.holder;
            if (level == null || owner == null) continue;
            if (level.getGameTime() - t.lastTouch < ORPHAN_TICKS) continue;
            // Owner may already be removed: its UUID still names the ticket, which is all
            // forceChunk needs to drop it.
            t.linger.releaseAll(c -> force(level, owner, c, false));
            force(level, owner, t.held.toLong(), false);
            t.setHeld(null);
            PlanePerf.ticketOrphansReleased++;
        }
    }

    /** Server stop: nothing is held into the next world. */
    static void forgetAll() {
        HELD.clear();
    }

    private static void force(ServerLevel level, Entity owner, long chunk, boolean add) {
        ForgeChunkManager.forceChunk(level, TaczSewv.MODID, owner,
                ChunkPos.getX(chunk), ChunkPos.getZ(chunk), add, true);
        if (add) PlanePerf.ticketForceAdds++;
        else PlanePerf.ticketForceRemoves++;
    }
}
