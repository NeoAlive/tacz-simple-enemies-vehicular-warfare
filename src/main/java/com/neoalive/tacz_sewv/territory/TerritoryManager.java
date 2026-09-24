package com.neoalive.tacz_sewv.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Assignment;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;
import com.neoalive.tacz_sewv.entity.ai.support.MortarSupport;
import com.neoalive.tacz_sewv.entity.ai.support.PmcDownedSupport;
import com.neoalive.tacz_sewv.entity.ai.support.TerritorySupport;
import com.neoalive.tacz_sewv.fob.FobSupport;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.invasion.SweepAdvancement;
import com.neoalive.tacz_sewv.network.PacketHudNotification;
import com.neoalive.tacz_sewv.network.PacketTerritoryCommand;
import com.neoalive.tacz_sewv.network.PacketTerritoryState;
import com.neoalive.tacz_sewv.network.PacketTerritoryState.Row;

/**
 * Server side of Territory Mode: who may be posted, the Frontline Tool, and the once-a-second pass that keeps
 * the picture true and tells the client about it.
 *
 * <p>Everything world-shaped lives here (claims, entity scans); the maths is {@link FrontlineMath} and what a
 * posted unit does is {@link TerritorySupport} plus its goal. The mode flag is {@link TerritoryData}; the post
 * itself is on the unit ({@link ITerritoryPost}), so coverage is never stored — it is derived from live posted
 * units every pass, and a dead or released unit needs no bookkeeping.
 *
 * <p>The pass runs only for a player whose panel is open or whose mode is on, at most once a second each, and
 * builds the claim set ONCE per pass (walking a party's claim streams is the expensive part). Order matters:
 * prune → claims → front → lost flags → advance plan → coverage → push. Reordering it puts last second's
 * ownership into this second's amber chunks.
 */
public final class TerritoryManager {

    /** Why a unit cannot be posted; ordinals are not wire values (see {@code PacketTerritoryState.ST_*}). */
    public enum Status { OK, FOB, AIR, CREW, DOWNED, SWEEP }

    private static final int PASS_TICKS = 20;
    /**
     * After a server start OpenPAC's claim data may not be readable yet, so an empty claim set is not believed for
     * this long (server ticks). Past it, an empty set from a player who HAS had claims means they lost them all.
     */
    private static final int CLAIMS_STARTUP_GRACE = 1200;
    /** Front chunks sent to a client; a claim past this is truncated rather than allowed to bloat the packet. */
    private static final int MAX_FRONT = 4096;
    /** Notification rate limits, in server ticks: per message kind, and per unit / chunk within a kind. */
    private static final int KIND_COOLDOWN = 40;
    private static final int KEY_COOLDOWN = 60;

    /** Players with the RTS panel open (server thread only). */
    private static final Set<UUID> PANEL = new HashSet<>();
    /** Chunks that had a unit on them at the last pass, to tell a unit dying from a chunk simply never covered. */
    static final Map<UUID, Set<Long>> LAST_COVERED = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    private TerritoryManager() {}

    // ---- eligibility ------------------------------------------------------------------------------------

    /**
     * Whether {@code pmc} can take a post now. On foot, or the seat-0 driver of a non-air hull, and not FOB
     * garrisoned, downed, or a member of a running Sweep &amp; Advance operation (posting would silently pull
     * it out of the sweep, so it is refused and reported instead).
     */
    public static Status status(PmcUnitEntity pmc) {
        if (FobSupport.isStamped(pmc)) return Status.FOB;
        // A driver of a hull assigned to a FOB is under FOB park / resupply orders that outrank a post.
        if (pmc.getVehicle() instanceof VehicleEntity ridden && FobSupport.isStamped(ridden)) return Status.FOB;
        if (PmcDownedSupport.isDowned(pmc)) return Status.DOWNED;
        if (pmc.isPassenger()) {
            if (!(pmc.getVehicle() instanceof VehicleEntity hull) || hull.getFirstPassenger() != pmc) return Status.CREW;
            if (HullFacts.isHelicopterHull(hull) || HullFacts.isPlaneHull(hull)) return Status.AIR;
        } else if (MortarSupport.hasMortarClaim(pmc)) {
            return Status.CREW;
        }
        if (SweepAdvancement.isAssignee(pmc)) return Status.SWEEP;
        return Status.OK;
    }

    public static boolean isModeOn(MinecraftServer server, UUID player) {
        return TerritoryData.get(server).isOn(player);
    }

    // ---- commands ---------------------------------------------------------------------------------------

    public static void handle(ServerPlayer player, PacketTerritoryCommand cmd) {
        if (!OpenPacCompat.isLoaded()) return; // the feature does not exist without OpenPAC
        switch (cmd.action()) {
            case PANEL_OPEN -> {
                if (cmd.flag()) {
                    PANEL.add(player.getUUID());
                    pass(player); // open on a current picture, not one second late
                } else {
                    PANEL.remove(player.getUUID());
                }
            }
            case SET_MODE -> setMode(player, cmd.flag());
            case FRONTLINE -> frontline(player, cmd.chunkX(), cmd.chunkZ(), cmd.ids());
            case RELEASE -> release(player, cmd.ids().isEmpty() ? -1 : cmd.ids().get(0));
            case MANUAL_FRONTLINE -> manualFrontline(player, cmd.chunks(), cmd.ids());
            case PLAN_BAKE -> AdvancePlanManager.bake(player, cmd.chunks());
            case PLAN_START -> AdvancePlanManager.start(player, cmd.ids());
            case PLAN_STOP -> AdvancePlanManager.stop(player);
            case PLAN_SKIP -> AdvancePlanManager.skip(player);
            case PLAN_CLEAR -> AdvancePlanManager.clear(player);
            case CLEAR_LINE -> {
                TerritoryData.get(player.server).clearLine(player.getUUID(), dimKey(player.serverLevel()));
                pass(player);
            }
        }
    }

    static void setMode(ServerPlayer player, boolean on) {
        MinecraftServer server = player.server;
        TerritoryData data = TerritoryData.get(server);
        if (on) {
            if (!data.isOn(player.getUUID())) {
                int eligible = 0;
                for (PmcUnitEntity u : ownedLoaded(player.serverLevel(), player)) {
                    if (status(u) == Status.OK) eligible++;
                }
                if (eligible == 0) {
                    notify(player, "need_units", "", Component.translatable("notification.tacz_sewv.territory.need_units"));
                    pass(player); // keep the panel honest about the toggle staying off
                    return;
                }
                data.set(player.getUUID(), true);
            }
        } else {
            data.set(player.getUUID(), false);
            data.clearLines(player.getUUID()); // the drawn line goes with the mode
            data.clearPlans(player.getUUID()); // ...and so does a baked Advance Plan
            AdvancePlanManager.forget(player.getUUID());
            // Every loaded posted unit of theirs, in every dimension, holds where it stands. Units in
            // unloaded chunks are cleaned as they load (onJoin), because the mode is now off.
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof PmcUnitEntity u && postedBy(u, player.getUUID())) TerritorySupport.release(u);
                }
            }
        }
        LAST_COVERED.remove(player.getUUID());
        pass(player);
    }

    private static void release(ServerPlayer player, int unitId) {
        Entity e = player.serverLevel().getEntity(unitId);
        if (e instanceof PmcUnitEntity u && PmcOwnerSupport.isOwner(player, u)
                && ((ITerritoryPost) u).sewv$hasTerritoryPost()) {
            TerritorySupport.release(u);
            LAST_COVERED.remove(player.getUUID()); // the player did this; not a chunk "going uncovered"
        }
        pass(player);
    }

    /** A Frontline run's selected, eligible, owned units. */
    record Selection(List<PmcUnitEntity> units, Set<Integer> ids) {}

    /**
     * The selected units that can be posted. Returns null after telling the player why (rate-limited) when there are
     * none, so the auto and manual paths fail identically.
     */
    private static Selection selectEligible(ServerPlayer player, ServerLevel level, List<Integer> ids) {
        List<PmcUnitEntity> selected = new ArrayList<>();
        int inSweep = 0;
        Set<Integer> selectedIds = new HashSet<>();
        for (int id : ids) {
            if (!(level.getEntity(id) instanceof PmcUnitEntity u) || !u.isAlive()
                    || !PmcOwnerSupport.isOwner(player, u) || !selectedIds.add(id)) continue;
            Status s = status(u);
            if (s == Status.OK) selected.add(u);
            else if (s == Status.SWEEP) inSweep++;
        }
        if (selected.isEmpty()) {
            if (inSweep > 0) {
                notify(player, "in_sweep", "", Component.translatable("notification.tacz_sewv.territory.in_sweep", inSweep));
            } else {
                notify(player, "select_units", "", Component.translatable("notification.tacz_sewv.territory.select_units"));
            }
            return null;
        }
        return new Selection(selected, selectedIds);
    }

    /**
     * The assignment both Frontline paths share, so auto and manual can never disagree about spread and density.
     * {@code orderedFront} is already in fill order (nearest the origin for auto, drag order for manual) and
     * {@code origin} anchors the unit ordering. Additive: units posted earlier and not selected keep their chunks
     * and count toward each chunk's prior coverage, so leftovers stack on real coverage. Returns units posted.
     */
    static int assignAndPost(ServerPlayer player, ServerLevel level, Selection sel, Set<Long> claims,
                                     List<Chunk> orderedFront, Chunk origin) {
        Map<Chunk, Integer> prior = new HashMap<>();
        for (PmcUnitEntity u : ownedLoaded(level, player)) {
            ITerritoryPost p = (ITerritoryPost) u;
            if (p.sewv$hasTerritoryPost() && !sel.ids().contains(u.getId())) {
                prior.merge(new Chunk(p.sewv$getTerritoryChunkX(), p.sewv$getTerritoryChunkZ()), 1, Integer::sum);
            }
        }

        List<FrontlineMath.Unit> units = new ArrayList<>();
        Map<Integer, PmcUnitEntity> byId = new HashMap<>();
        for (PmcUnitEntity u : sel.units()) {
            byId.put(u.getId(), u);
            boolean inside = claims.contains(FrontlineMath.pack(u.getBlockX() >> 4, u.getBlockZ() >> 4));
            units.add(new FrontlineMath.Unit(u.getId(), u.getX(), u.getZ(), inside));
        }
        int posted = 0;
        for (Assignment a : FrontlineMath.assign(orderedFront, units, origin, prior)) {
            PmcUnitEntity u = byId.get(a.unitId());
            if (u != null && TerritorySupport.post(u, a.chunk().x(), a.chunk().z())) {
                u.getPersistentData().putUUID(ITerritoryPost.TAG_BY, player.getUUID());
                posted++;
            }
        }
        return posted;
    }

    static String dimKey(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /**
     * Auto Frontline: the clicked chunk is the origin and the front is filled nearest-first. Any successful run
     * replaces the display state, so it clears a drawn manual line (which would otherwise claim to describe
     * assignments it no longer does).
     */
    private static void frontline(ServerPlayer player, int originX, int originZ, List<Integer> ids) {
        ServerLevel level = player.serverLevel();
        TerritoryData data = TerritoryData.get(player.server);
        if (!data.isOn(player.getUUID())) return;

        Selection sel = selectEligible(player, level, ids);
        if (sel == null) return;

        Set<Long> claims = OpenPacCompat.selfClaimedChunks(level, player.getUUID());
        if (!claims.contains(FrontlineMath.pack(originX, originZ))) {
            notify(player, "origin", "", Component.translatable("notification.tacz_sewv.territory.origin_not_self"));
            return;
        }
        Chunk origin = new Chunk(originX, originZ);
        List<Chunk> front = FrontlineMath.front(claims, origin);
        if (front.isEmpty()) {
            notify(player, "no_front", "", Component.translatable("notification.tacz_sewv.territory.no_front"));
            return;
        }

        if (assignAndPost(player, level, sel, claims, front, origin) > 0) {
            data.clearLine(player.getUUID(), dimKey(level));
        }
        LAST_COVERED.remove(player.getUUID()); // the player reshuffled the front; not a chunk going uncovered
        pass(player);
    }

    /**
     * Manual Frontline: the drag path IS the ordering. Only front chunks count, the origin is ignored (the first
     * drawn chunk anchors unit ordering), and an empty result changes nothing at all — the existing line and every
     * post stay as they were. A successful run stores the drawn order, replacing any previous line.
     */
    private static void manualFrontline(ServerPlayer player, List<Long> visited, List<Integer> ids) {
        ServerLevel level = player.serverLevel();
        TerritoryData data = TerritoryData.get(player.server);
        if (!data.isOn(player.getUUID())) return;

        Selection sel = selectEligible(player, level, ids);
        if (sel == null) return;

        Set<Long> claims = OpenPacCompat.selfClaimedChunks(level, player.getUUID());
        Set<Long> frontKeys = new HashSet<>();
        for (Chunk c : FrontlineMath.frontChunks(claims)) frontKeys.add(FrontlineMath.pack(c.x(), c.z()));
        List<Chunk> drawn = FrontlineMath.drawnOrder(frontKeys, visited);
        if (drawn.isEmpty()) {
            notify(player, "manual_empty", "", Component.translatable("notification.tacz_sewv.territory.manual_empty"));
            return;
        }

        if (assignAndPost(player, level, sel, claims, drawn, drawn.get(0)) > 0) {
            long[] line = new long[drawn.size()];
            for (int i = 0; i < line.length; i++) line[i] = FrontlineMath.pack(drawn.get(i).x(), drawn.get(i).z());
            data.setLine(player.getUUID(), dimKey(level), line);
        }
        LAST_COVERED.remove(player.getUUID());
        pass(player);
    }

    // ---- the pass ---------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PASS_TICKS != 0 || !OpenPacCompat.isLoaded()) return;
        TerritoryData data = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (data == null) data = TerritoryData.get(server);
            if (data.isOn(player.getUUID()) || PANEL.contains(player.getUUID())) pass(player);
        }
    }

    static void pass(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        boolean modeOn = TerritoryData.get(player.server).isOn(id);

        // 1. prune: the roster (loaded owned units) and, with the mode off, any leftover post.
        List<PmcUnitEntity> owned = ownedLoaded(level, player);
        if (!modeOn) {
            for (PmcUnitEntity u : owned) {
                if (((ITerritoryPost) u).sewv$hasTerritoryPost()) TerritorySupport.release(u);
            }
            LAST_COVERED.remove(id);
            PacketTerritoryState.sendTo(player, new PacketTerritoryState(false, rows(owned), new long[0], new int[0], new long[0], null,
                com.neoalive.tacz_sewv.config.SewvConfig.ADVANCE_PLAN_MAX_CHUNKS.get()));
            return;
        }

        // A post can be overtaken by a FOB assignment that reached the unit or its hull by a route that skips the
        // assignment-time refusals; the FOB park destination would then silently win. Say so and drop the post.
        int fobConflicts = 0;
        for (PmcUnitEntity u : owned) {
            if (((ITerritoryPost) u).sewv$hasTerritoryPost() && status(u) == Status.FOB) {
                TerritorySupport.release(u);
                fobConflicts++;
            }
        }
        if (fobConflicts > 0) {
            notify(player, "fob_conflict", "", Component.translatable("notification.tacz_sewv.territory.fob_conflict", fobConflicts));
            LAST_COVERED.remove(id);
        }

        // 2. claim set, once; 3. front from it.
        Set<Long> claims = OpenPacCompat.selfClaimedChunks(level, id);
        List<Chunk> front = FrontlineMath.frontChunks(claims);
        TerritoryData data = TerritoryData.get(player.server);
        if (!claims.isEmpty()) data.markSeenClaims(id);

        // 4. lost-chunk flags. An empty set is ambiguous: "OpenPAC not readable yet" (never had claims, or a fresh
        // server start) must not flag anything, but "had claims, now none" means every posted chunk was lost —
        // believing neither would leave units holding enemy ground with no word to the player.
        boolean claimsReadable = !claims.isEmpty()
                || (data.hasSeenClaims(id) && player.server.getTickCount() > CLAIMS_STARTUP_GRACE);
        List<Chunk> lostNow = new ArrayList<>();
        if (claimsReadable) {
            for (PmcUnitEntity u : owned) {
                ITerritoryPost p = (ITerritoryPost) u;
                if (!p.sewv$hasTerritoryPost()) continue;
                boolean held = claims.contains(FrontlineMath.pack(p.sewv$getTerritoryChunkX(), p.sewv$getTerritoryChunkZ()));
                if (!held && !p.sewv$isTerritoryLost()) {
                    p.sewv$setTerritoryLost(true);
                    Chunk c = new Chunk(p.sewv$getTerritoryChunkX(), p.sewv$getTerritoryChunkZ());
                    if (!lostNow.contains(c)) lostNow.add(c);
                } else if (held && p.sewv$isTerritoryLost()) {
                    p.sewv$setTerritoryLost(false); // reclaimed: nothing else changes, the unit never left
                }
            }
        }

        // 4b. Advance Plan: parent check, gate, claim a layer, re-run Frontline. Between the lost flags and the leash
        // step so the state pushed below describes the new posts. Returns the claim set after any layer it claimed.
        Set<Long> advanced = AdvancePlanManager.advance(player, level, owned, claims, claimsReadable, data);
        if (advanced != claims) {
            claims = advanced;
            front = FrontlineMath.frontChunks(claims);
        }

        // 6. coverage, derived from live posted units.
        Map<Long, Integer> onChunk = new HashMap<>();
        for (PmcUnitEntity u : owned) {
            ITerritoryPost p = (ITerritoryPost) u;
            if (p.sewv$hasTerritoryPost()) {
                onChunk.merge(FrontlineMath.pack(p.sewv$getTerritoryChunkX(), p.sewv$getTerritoryChunkZ()), 1, Integer::sum);
            }
        }
        int n = Math.min(front.size(), MAX_FRONT);
        long[] frontKeys = new long[n];
        int[] coverage = new int[n];
        Set<Long> nowCovered = new HashSet<>();
        Set<Long> frontSet = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Chunk c = front.get(i);
            long key = FrontlineMath.pack(c.x(), c.z());
            frontKeys[i] = key;
            coverage[i] = onChunk.getOrDefault(key, 0);
            frontSet.add(key);
            if (coverage[i] > 0) nowCovered.add(key);
        }

        notifyChanges(player, lostNow, nowCovered, frontSet);

        // 7. push.
        PacketTerritoryState.sendTo(player, new PacketTerritoryState(true, rows(owned), frontKeys, coverage,
                data.getLine(id, dimKey(level)), AdvancePlanManager.view(player, level, claims, data),
                com.neoalive.tacz_sewv.config.SewvConfig.ADVANCE_PLAN_MAX_CHUNKS.get()));
    }

    /** One toast per kind per pass: a chunk lost / a chunk that lost its last unit, aggregated if several. */
    private static void notifyChanges(ServerPlayer player, List<Chunk> lostNow, Set<Long> nowCovered, Set<Long> front) {
        UUID id = player.getUUID();
        if (!lostNow.isEmpty()) {
            Component body = lostNow.size() == 1
                    ? Component.translatable("notification.tacz_sewv.territory.chunk_lost", lostNow.get(0).x(), lostNow.get(0).z())
                    : Component.translatable("notification.tacz_sewv.territory.chunks_lost", lostNow.size());
            notify(player, "chunk_lost", lostNow.get(0).x() + "," + lostNow.get(0).z(), body);
        }
        Set<Long> last = LAST_COVERED.get(id);
        if (last != null) {
            List<Chunk> uncovered = new ArrayList<>();
            for (long key : last) {
                Chunk c = FrontlineMath.unpack(key);
                // Still a front chunk we still own, but nobody is on it any more: a unit died.
                if (front.contains(key) && !nowCovered.contains(key) && !lostNow.contains(c)) uncovered.add(c);
            }
            if (!uncovered.isEmpty()) {
                Component body = uncovered.size() == 1
                        ? Component.translatable("notification.tacz_sewv.territory.uncovered", uncovered.get(0).x(), uncovered.get(0).z())
                        : Component.translatable("notification.tacz_sewv.territory.uncovered_many", uncovered.size());
                notify(player, "uncovered", uncovered.get(0).x() + "," + uncovered.get(0).z(), body);
            }
        }
        LAST_COVERED.put(id, nowCovered);
    }

    // ---- roster -----------------------------------------------------------------------------------------

    static List<PmcUnitEntity> ownedLoaded(ServerLevel level, ServerPlayer player) {
        List<PmcUnitEntity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof PmcUnitEntity pmc && pmc.isAlive() && PmcOwnerSupport.isOwner(player, pmc)) out.add(pmc);
        }
        return out;
    }

    private static List<Row> rows(List<PmcUnitEntity> owned) {
        List<Row> rows = new ArrayList<>(owned.size());
        for (PmcUnitEntity u : owned) {
            ITerritoryPost p = (ITerritoryPost) u;
            boolean posted = p.sewv$hasTerritoryPost();
            float max = Math.max(1.0F, u.getMaxHealth());
            rows.add(new Row(u.getId(), u.getDisplayName().getString(),
                    (byte) (u.isPassenger() ? 1 : 0), u.getHealth() / max, statusByte(status(u)),
                    posted, posted && p.sewv$isTerritoryLost(),
                    posted ? p.sewv$getTerritoryChunkX() : 0, posted ? p.sewv$getTerritoryChunkZ() : 0));
        }
        return rows;
    }

    private static byte statusByte(Status s) {
        return switch (s) {
            case OK -> PacketTerritoryState.ST_OK;
            case FOB -> PacketTerritoryState.ST_FOB;
            case AIR -> PacketTerritoryState.ST_AIR;
            case CREW -> PacketTerritoryState.ST_CREW;
            case DOWNED -> PacketTerritoryState.ST_DOWNED;
            case SWEEP -> PacketTerritoryState.ST_SWEEP;
        };
    }

    private static boolean postedBy(PmcUnitEntity u, UUID player) {
        if (!((ITerritoryPost) u).sewv$hasTerritoryPost()) return false;
        CompoundTag tag = u.getPersistentData();
        return tag.hasUUID(ITerritoryPost.TAG_BY) && player.equals(tag.getUUID(ITerritoryPost.TAG_BY));
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    /**
     * A posted unit loading in while its commander's mode is off (it was off when they were away, or the unit sat
     * in an unloaded chunk when the mode was disabled) is released, so a stale post never walks a unit home.
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PmcUnitEntity pmc)) return;
        ITerritoryPost post = (ITerritoryPost) pmc;
        if (!post.sewv$hasTerritoryPost()) return;
        MinecraftServer server = event.getLevel().getServer();
        CompoundTag tag = pmc.getPersistentData();
        UUID by = tag.hasUUID(ITerritoryPost.TAG_BY) ? tag.getUUID(ITerritoryPost.TAG_BY) : null;
        if (server == null || by == null || !OpenPacCompat.isLoaded() || !TerritoryData.get(server).isOn(by)) {
            post.sewv$clearTerritoryPost();
            pmc.setOrder(OrderType.HOLD_POSITION);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PANEL.remove(id);
        LAST_COVERED.remove(id);
        COOLDOWNS.remove(id);
        AdvancePlanManager.forget(id);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PANEL.clear();
        LAST_COVERED.clear();
        COOLDOWNS.clear();
        AdvancePlanManager.forgetAll();
    }

    // ---- notifications ----------------------------------------------------------------------------------

    /** An order was refused because the unit is on a post: tell the player where to release it. */
    public static void notifyRefused(ServerPlayer issuer, PmcUnitEntity pmc, String channel) {
        notify(issuer, "refused_" + channel, String.valueOf(pmc.getId()),
                Component.translatable("notification.tacz_sewv.territory.refused." + channel, pmc.getDisplayName()));
    }

    /**
     * One toast at most per message kind every 2 s, and per unit / chunk (the {@code key}) every 3 s — holding a
     * key with no eligible selection must not queue the same message dozens of times.
     */
    static void notify(ServerPlayer player, String kind, String key, Component body) {
        long now = player.server.getTickCount();
        Map<String, Long> cooldowns = COOLDOWNS.computeIfAbsent(player.getUUID(), u -> new HashMap<>());
        if (cooldowns.size() > 256) cooldowns.values().removeIf(until -> until <= now);
        String keyed = kind + ":" + key;
        if (now < cooldowns.getOrDefault(kind, 0L) || now < cooldowns.getOrDefault(keyed, 0L)) return;
        cooldowns.put(kind, now + KIND_COOLDOWN);
        cooldowns.put(keyed, now + KEY_COOLDOWN);
        PacketHudNotification.sendTo(player, Component.translatable("notification.tacz_sewv.territory.title"), body);
    }
}
