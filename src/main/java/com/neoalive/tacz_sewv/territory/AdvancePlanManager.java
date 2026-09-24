package com.neoalive.tacz_sewv.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.ITerritoryPost;
import com.neoalive.tacz_sewv.compat.OpenPacCompat;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.diplomacy.DiplomacyData;
import com.neoalive.tacz_sewv.entity.ai.support.AdvanceMath;
import com.neoalive.tacz_sewv.entity.ai.support.AdvanceMath.Owner;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath;
import com.neoalive.tacz_sewv.entity.ai.support.FrontlineMath.Chunk;
import com.neoalive.tacz_sewv.entity.ai.support.TerritorySupport;
import com.neoalive.tacz_sewv.invasion.PmcOwnerSupport;
import com.neoalive.tacz_sewv.network.PacketTerritoryState.PlanView;

/**
 * The Advance Plan lifecycle: bake a painted region into layers, Start/Stop/Clear it, and once a pass advance a
 * running plan layer by layer. It sits beside {@link TerritoryManager} and uses its posting ({@code assignAndPost}),
 * its once-a-second pass and its rate-limited notifications; there is no second posting system.
 *
 * <p><b>The advance loop</b> (one evaluation per 20-tick pass). For the current layer: not every chunk of it (and of the
 * front chunks that touch it) loaded → hold; a hostile inside → hold; otherwise claim it (all-or-nothing, through
 * OpenPAC's limits), re-run Frontline on the front component that now leads the plan, and soak before looking at
 * the next layer. It ends when every layer is ours. Arrival is not the gate: a driven hull parks short of its post's
 * centre and would never satisfy an arrival test, and the claim itself is what advances state.
 *
 * <p>A hold is never a failure of the plan: {@code NOT_CLAIMABLE} (claims disabled, unclaimable dimension) holds the
 * layer exactly like a limit does, and the plan resumes on its own the moment the problem goes away, with no re-bake.
 * Each hold is announced once when it starts (or its layer changes), with a message that names the actual problem.
 */
public final class AdvancePlanManager {

    /** Why a RUNNING plan is waiting. Ordinals are wire values ({@code PlanView.hold}); append, never reorder. */
    public enum Hold { NONE, HOSTILES, NOT_LOADED, LIMIT, NOT_CLAIMABLE, RETRY, ARRIVING }

    /** {@code PlanView.startReason}: 0 = can start, 1 = no front touches what is left, 2 = the front has split. */
    public static final byte START_OK = 0;
    public static final byte START_NO_FRONT = 1;
    public static final byte START_FRAGMENTED = 2;

    /**
     * After a layer is claimed the plan holds this long before it evaluates the next, so a clean region does not finish
     * in five back-to-back passes. Passes run every 20 ticks, so the effective hold is the first pass at or after the
     * deadline: two passes, about two seconds per layer. A constant, not config.
     */
    static final long LAYER_SOAK_TICKS = 30L;

    /** Transient per-player state that must NOT survive a reload: a reload just grants a fresh grace. */
    private static final class Runtime {
        long soakUntil;
        /** One-shot: the player asked to skip the current layer (see {@link #skip}). */
        boolean force;
        /** The layer the units were last sent to; a sentinel until the first dispatch, so the first pass after Start fires it. */
        int dispatchedLayer = AdvanceMath.NOT_DISPATCHED;
        int badStreak;
        Hold hold = Hold.NONE;
        int holdKey = -1;
    }

    private static final Map<UUID, Runtime> RUNTIME = new HashMap<>();

    private AdvancePlanManager() {}

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    static void forget(UUID player) {
        RUNTIME.remove(player);
    }

    static void forgetAll() {
        RUNTIME.clear();
    }

    /**
     * A PMC died: drop it from every plan snapshot now. The per-pass check below only sees a corpse for the second or
     * so before the entity is removed, after which "dead" looks exactly like "unloaded", so death is caught here.
     */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof PmcUnitEntity pmc)) return;
        MinecraftServer server = pmc.getServer();
        if (server != null) TerritoryData.get(server).forgetUnit(pmc.getUUID());
    }

    private static void say(ServerPlayer player, String kind, String key, String lang, Object... args) {
        TerritoryManager.notify(player, kind, key, Component.translatable("notification.tacz_sewv.territory.plan." + lang, args));
    }

    // ---- bake --------------------------------------------------------------------------------------------------

    /**
     * Validates the painted region and stores it as the player's plan for this dimension (replacing any previous one).
     * Every refusal is a message and changes nothing. The ownership rule is decided HERE, so the player sees conflicts
     * before pressing Start: self-claimed or allied chunks refuse, foreign claims are allowed only when the owner is in
     * a diplomatic ENEMY relation, unclaimed is fine.
     *
     * <p><b>The size cap ({@code advancePlanMaxChunks}) is validated here and nowhere else: existing plans are not
     * re-validated against a changed cap</b>, so editing the config mid-plan never stops or clears a running plan.
     */
    static void bake(ServerPlayer player, List<Long> painted) {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        TerritoryData data = TerritoryData.get(server);
        if (!data.isOn(id)) {
            say(player, "plan_need_mode", "", "need_mode");
            return;
        }

        Set<Long> region = new HashSet<>(painted);
        int cap = SewvConfig.ADVANCE_PLAN_MAX_CHUNKS.get();
        if (region.isEmpty()) {
            say(player, "plan_empty", "", "empty");
            return;
        }
        if (region.size() > cap) {
            say(player, "plan_too_big", "", "too_big", region.size(), cap);
            return;
        }
        if (!AdvanceMath.isSingleBlob(region)) {
            say(player, "plan_disconnected", "", "disconnected");
            return;
        }

        Set<Long> claims = OpenPacCompat.selfClaimedChunks(level, id);
        Set<Long> front = AdvanceMath.frontKeys(claims);
        if (front.isEmpty()) {
            say(player, "plan_no_front", "", "no_front");
            return;
        }

        UUID partyOwner = OpenPacCompat.partyOwnerId(server, id);
        String myFaction = OpenPacCompat.factionName(server, id);
        DiplomacyData diplomacy = DiplomacyData.get(level);
        Map<Long, Owner> owners = new HashMap<>();
        for (long key : region) owners.put(key, ownerOf(level, id, partyOwner, myFaction, diplomacy, claims, key));
        AdvanceMath.Verdict verdict = AdvanceMath.classify(owners);
        if (!verdict.ok()) {
            List<Long> samples = new ArrayList<>(verdict.selfSamples());
            samples.addAll(verdict.allySamples());
            samples.addAll(verdict.otherSamples());
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < Math.min(3, samples.size()); i++) {
                Chunk c = FrontlineMath.unpack(samples.get(i));
                where.append(i == 0 ? "" : ", ").append('(').append(c.x()).append(", ").append(c.z()).append(')');
            }
            say(player, "plan_conflict", "", "conflict", verdict.self(), verdict.ally(), verdict.other(), where.toString());
            return;
        }

        if (AdvanceMath.contactChunks(front, region).isEmpty()) {
            say(player, "plan_not_adjacent", "", "not_adjacent");
            return;
        }
        AdvanceMath.Layers layers = AdvanceMath.layers(claims, region, AdvanceMath.MAX_DILATIONS);
        // layers.dropped() is empty here: self-claimed chunks were refused above. It is not asserted, so the call
        // never depends on that ordering.
        if (!layers.unreachable().isEmpty()) {
            say(player, "plan_unreachable", "", "unreachable", layers.unreachable().size(), AdvanceMath.MAX_DILATIONS);
            return;
        }
        if (layers.layers().isEmpty()) {
            say(player, "plan_not_adjacent", "", "not_adjacent");
            return;
        }
        if (AdvanceMath.parentState(front, region) != AdvanceMath.Parent.HEALTHY) {
            say(player, "plan_fragmented", "", "fragmented");
            return;
        }

        long[] keys = new long[region.size()];
        int[] layerOf = new int[keys.length];
        int i = 0;
        for (long key : region) {
            keys[i] = key;
            layerOf[i] = layers.layerOf().get(key);
            i++;
        }
        List<AdvanceMath.Arrow> arrows = AdvanceMath.arrows(front, region);
        int[] flat = new int[arrows.size() * 4];
        for (int a = 0; a < arrows.size(); a++) {
            flat[a * 4] = arrows.get(a).blockX();
            flat[a * 4 + 1] = arrows.get(a).blockZ();
            flat[a * 4 + 2] = arrows.get(a).dx();
            flat[a * 4 + 3] = arrows.get(a).dz();
        }

        data.setPlan(id, TerritoryManager.dimKey(level), new AdvancePlan(keys, layerOf, layers.layers().size(), flat));
        RUNTIME.remove(id);
        say(player, "plan_baked", "", "baked", region.size(), layers.layers().size());
        TerritoryManager.pass(player);
    }

    private static Owner ownerOf(ServerLevel level, UUID me, UUID partyOwner, String myFaction,
                                 DiplomacyData diplomacy, Set<Long> claims, long key) {
        if (claims.contains(key)) return Owner.SELF;
        Chunk c = FrontlineMath.unpack(key);
        UUID holder = OpenPacCompat.claimOwnerId(level, c.x(), c.z());
        if (holder == null) return Owner.UNCLAIMED;
        if (holder.equals(me) || holder.equals(partyOwner)) return Owner.SELF;
        MinecraftServer server = level.getServer();
        if (OpenPacCompat.allied(server, me, holder)) return Owner.ALLY; // same party, or an OpenPAC party ally
        String theirFaction = OpenPacCompat.factionName(server, holder);
        if (myFaction != null && theirFaction != null) {
            DiplomacyData.Relation relation = diplomacy.relation(myFaction, theirFaction);
            if (relation == DiplomacyData.Relation.ENEMY) return Owner.ENEMY;
            if (relation == DiplomacyData.Relation.ALLY) return Owner.ALLY;
        }
        return Owner.OTHER; // a foreign claim that is not a diplomatic enemy's
    }

    // ---- start / stop / clear ---------------------------------------------------------------------------------

    /** Start with the selected posted units. The snapshot is fixed for the run; no unit is pulled in later. */
    static void start(ServerPlayer player, List<Integer> ids) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        TerritoryData data = TerritoryData.get(player.server);
        AdvancePlan plan = data.getPlan(id, TerritoryManager.dimKey(level));
        if (plan == null || plan.state() == AdvancePlan.RUNNING || !data.isOn(id)) return;

        List<PmcUnitEntity> units = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int unitId : ids) {
            if (!(level.getEntity(unitId) instanceof PmcUnitEntity u) || !u.isAlive() || !seen.add(unitId)) continue;
            if (!PmcOwnerSupport.isOwner(player, u) || !((ITerritoryPost) u).sewv$hasTerritoryPost()) continue;
            if (TerritoryManager.status(u) == TerritoryManager.Status.OK) units.add(u);
        }
        if (units.isEmpty()) {
            say(player, "plan_need_units", "", "need_units");
            return;
        }

        Set<Long> claims = OpenPacCompat.selfClaimedChunks(level, id);
        Set<Long> remainder = new HashSet<>(plan.regionSet());
        remainder.removeAll(claims);
        if (remainder.isEmpty()) {
            data.clearPlan(id, TerritoryManager.dimKey(level));
            say(player, "plan_complete", "", "complete", plan.layerCount());
            TerritoryManager.pass(player);
            return;
        }
        AdvanceMath.Parent parent = AdvanceMath.parentState(AdvanceMath.frontKeys(claims), remainder);
        if (parent != AdvanceMath.Parent.HEALTHY) {
            say(player, "plan_start_blocked", "", parent == AdvanceMath.Parent.GONE ? "not_adjacent" : "fragmented");
            return;
        }

        plan.units().clear();
        for (PmcUnitEntity u : units) plan.units().add(u.getUUID());
        plan.setState(AdvancePlan.RUNNING);
        data.setDirty();
        RUNTIME.put(id, new Runtime());
        say(player, "plan_started", "", "started", units.size(), plan.layerCount());
        TerritoryManager.pass(player);
    }

    /** Halts advances only: claims and posts persist, and the plan stays baked. */
    static void stop(ServerPlayer player) {
        TerritoryData data = TerritoryData.get(player.server);
        AdvancePlan plan = data.getPlan(player.getUUID(), TerritoryManager.dimKey(player.serverLevel()));
        if (plan == null || plan.state() != AdvancePlan.RUNNING) return;
        plan.setState(AdvancePlan.STOPPED);
        data.setDirty();
        RUNTIME.remove(player.getUUID());
        say(player, "plan_stopped", "", "stopped");
        TerritoryManager.pass(player);
    }

    /**
     * The stall workaround: the next evaluation claims the current layer WITHOUT waiting for the soak, for units to
     * arrive, or for hostiles to clear. Only the checks that are about the world rather than the units still apply:
     * the layer must be loaded and the claim limit / claimability must allow it. One-shot; a hold from one of those
     * ends it, and the player can skip again.
     */
    static void skip(ServerPlayer player) {
        TerritoryData data = TerritoryData.get(player.server);
        AdvancePlan plan = data.getPlan(player.getUUID(), TerritoryManager.dimKey(player.serverLevel()));
        if (plan == null || plan.state() != AdvancePlan.RUNNING) return;
        RUNTIME.computeIfAbsent(player.getUUID(), k -> new Runtime()).force = true;
        TerritoryManager.pass(player);
    }

    static void clear(ServerPlayer player) {
        TerritoryData data = TerritoryData.get(player.server);
        String dim = TerritoryManager.dimKey(player.serverLevel());
        if (data.getPlan(player.getUUID(), dim) == null) return;
        data.clearPlan(player.getUUID(), dim);
        RUNTIME.remove(player.getUUID());
        say(player, "plan_cleared", "", "cleared");
        TerritoryManager.pass(player);
    }

    // ---- the advance loop -------------------------------------------------------------------------------------

    /**
     * One pass of the plan. Returns {@code claims} untouched when nothing changed, or the fresh claim set after a layer
     * was claimed (so the caller recomputes the front it pushes).
     */
    static Set<Long> advance(ServerPlayer player, ServerLevel level, List<PmcUnitEntity> owned, Set<Long> claims,
                             boolean claimsReadable, TerritoryData data) {
        UUID id = player.getUUID();
        String dim = TerritoryManager.dimKey(level);
        AdvancePlan plan = data.getPlan(id, dim);
        if (plan == null) {
            RUNTIME.remove(id);
            return claims;
        }
        if (!claimsReadable) return claims; // judge nothing off a claim set that cannot be read yet

        Runtime rt = RUNTIME.computeIfAbsent(id, k -> new Runtime());
        Set<Long> front = AdvanceMath.frontKeys(claims);
        Set<Long> remainder = new HashSet<>(plan.regionSet());
        remainder.removeAll(claims);
        if (remainder.isEmpty()) {
            complete(player, data, id, dim, plan);
            return claims;
        }

        // Parenting: the front chunks touching what is left must all sit in ONE front component. A split that lasts
        // three passes clears the plan; anything shorter is normal churn around a claim and only pauses the advance.
        AdvanceMath.Parent parent = AdvanceMath.parentState(front, remainder);
        rt.badStreak = AdvanceMath.nextBadStreak(rt.badStreak, parent != AdvanceMath.Parent.HEALTHY);
        if (AdvanceMath.shouldClear(rt.badStreak)) {
            data.clearPlan(id, dim);
            RUNTIME.remove(id);
            say(player, "plan_lost_parent", "", "lost_parent");
            return claims;
        }
        if (parent != AdvanceMath.Parent.HEALTHY || plan.state() != AdvancePlan.RUNNING) return claims;

        long now = level.getGameTime();

        List<PmcUnitEntity> live = liveUnits(level, plan, owned, data);
        if (live.isEmpty()) {
            // Stopped, not cleared: the plan stays baked so the player can post units and Start again.
            plan.setState(AdvancePlan.STOPPED);
            data.setDirty();
            RUNTIME.remove(id);
            say(player, "plan_no_units", "", "no_units");
            return claims;
        }

        // The current layer, skipping any layer that is already entirely ours.
        Set<Long> layer;
        while (true) {
            if (plan.currentLayer() >= plan.layerCount()) {
                complete(player, data, id, dim, plan);
                return claims;
            }
            layer = new HashSet<>(plan.layer(plan.currentLayer()));
            layer.removeAll(claims);
            if (!layer.isEmpty()) break;
            plan.advanceLayer();
            data.setDirty();
        }

        boolean forced = rt.force;
        rt.force = false;

        // Path before claim. `contact` = the front chunks touching what is left: where the units are sent and whose
        // arrival is waited on (the same set the parent check computes). The units are the plan's snapshot only.
        Set<Long> contact = AdvanceMath.contactChunks(front, remainder);
        int onEdge = 0;
        int arrived = 0;
        for (PmcUnitEntity u : live) {
            ITerritoryPost post = (ITerritoryPost) u;
            if (!contact.contains(FrontlineMath.pack(post.sewv$getTerritoryChunkX(), post.sewv$getTerritoryChunkZ()))) continue;
            onEdge++;
            if (TerritorySupport.hasArrived(u)) arrived++;
        }
        if (!forced) switch (AdvanceMath.nextStep(rt.dispatchedLayer, plan.currentLayer(), now >= rt.soakUntil, onEdge, arrived)) {
            case DISPATCH -> {
                // Our own front chunks: an unloaded one cannot be pathed to, so say so rather than wait on it silently.
                if (!AdvanceHostiles.allLoaded(level, contact)) return hold(player, rt, plan, Hold.NOT_LOADED, claims);
                dispatch(player, level, live, front, contact, plan, rt, now);
                return claims;
            }
            case WAIT_SOAK -> {
                rt.hold = Hold.NONE;
                return claims;
            }
            case REDISPATCH -> {
                rt.dispatchedLayer = AdvanceMath.NOT_DISPATCHED;
                return hold(player, rt, plan, Hold.ARRIVING, claims);
            }
            case ARRIVING -> {
                return hold(player, rt, plan, Hold.ARRIVING, claims);
            }
            case PROCEED -> { }
        }

        // Gate: the layer AND the front chunks touching it must be loaded, then no hostile inside.
        Set<Long> layerContact = AdvanceMath.contactChunks(front, layer);
        if (!AdvanceHostiles.allLoaded(level, layer) || !AdvanceHostiles.allLoaded(level, layerContact)) {
            return hold(player, rt, plan, Hold.NOT_LOADED, claims);
        }
        Entity blocker = forced ? null : AdvanceHostiles.firstIn(level, layer, live.get(0), player);
        if (blocker != null) {
            // Once per hold (the same dedupe the toast uses): which entity is holding the layer, so "hostiles inside"
            // can be told apart from a downed unit, a parked hull or anything else that cannot really fight back.
            if (rt.holdKey != Hold.HOSTILES.ordinal() * 1000 + plan.currentLayer()) {
                TaczSewv.LOGGER.info("Advance plan of {} held on layer {}/{} by {}", player.getGameProfile().getName(),
                        plan.currentLayer() + 1, plan.layerCount(), AdvanceHostiles.describe(blocker));
            }
            return hold(player, rt, plan, Hold.HOSTILES, claims);
        }

        UUID owner = OpenPacCompat.partyOwnerId(player.server, id);
        if (owner == null) owner = id;
        AdvanceClaims.Result result = AdvanceClaims.claimLayer(level, owner, layer, claims);
        switch (result.status()) {
            case HELD_LIMIT -> {
                return hold(player, rt, plan, Hold.LIMIT, refreshed(level, id, claims, result));
            }
            case NOT_CLAIMABLE -> {
                return hold(player, rt, plan, Hold.NOT_CLAIMABLE, refreshed(level, id, claims, result));
            }
            case RETRY -> {
                return hold(player, rt, plan, Hold.RETRY, refreshed(level, id, claims, result));
            }
            case DONE -> { }
        }

        Set<Long> after = new HashSet<>(claims);
        after.addAll(layer);
        plan.advanceLayer();
        data.setDirty();
        rt.hold = Hold.NONE;
        rt.holdKey = -1;
        say(player, "plan_layer", String.valueOf(plan.currentLayer()), "layer_claimed", plan.currentLayer(), plan.layerCount());

        Set<Long> left = new HashSet<>(plan.regionSet());
        left.removeAll(after);
        if (left.isEmpty()) {
            complete(player, data, id, dim, plan);
            return after;
        }
        // Send the units to the next contact edge straight away (the just-claimed layer, already loaded), so they walk
        // while the minimum soak runs.
        Set<Long> afterFront = AdvanceMath.frontKeys(after);
        dispatch(player, level, live, afterFront, AdvanceMath.contactChunks(afterFront, left), plan, rt, now);
        return after;
    }

    /**
     * Sends the plan's units to {@code contact} and starts the minimum soak. Records which layer was dispatched, so
     * this fires once per layer (and once more after a reload, which loses the in-memory record).
     */
    private static void dispatch(ServerPlayer player, ServerLevel level, List<PmcUnitEntity> live, Set<Long> front,
                                 Set<Long> contact, AdvancePlan plan, Runtime rt, long now) {
        rerunFrontline(player, level, live, front, contact, plan);
        rt.dispatchedLayer = plan.currentLayer();
        rt.soakUntil = now + LAYER_SOAK_TICKS;
        rt.hold = Hold.NONE;
    }

    /** After a partial claim the set has changed even though the layer is held; re-read it so the front is honest. */
    private static Set<Long> refreshed(ServerLevel level, UUID id, Set<Long> claims, AdvanceClaims.Result result) {
        return result.claimed() > 0 ? OpenPacCompat.selfClaimedChunks(level, id) : claims;
    }

    /** Holds, and says so ONCE per (reason, layer): the same hold is not re-announced every pass. */
    private static Set<Long> hold(ServerPlayer player, Runtime rt, AdvancePlan plan, Hold reason, Set<Long> claims) {
        int key = reason.ordinal() * 1000 + plan.currentLayer();
        rt.hold = reason;
        if (reason != Hold.ARRIVING && rt.holdKey != key) { // moving up happens every layer: shown on the panel, never toasted
            rt.holdKey = key;
            say(player, "plan_hold_" + reason.name().toLowerCase(), String.valueOf(plan.currentLayer()),
                    "held." + reason.name().toLowerCase(), plan.currentLayer() + 1, plan.layerCount());
        }
        return claims;
    }

    private static void complete(ServerPlayer player, TerritoryData data, UUID id, String dim, AdvancePlan plan) {
        data.clearPlan(id, dim);
        RUNTIME.remove(id);
        say(player, "plan_complete", "", "complete", plan.layerCount());
    }

    /**
     * The snapshot units still able to take a post: found, alive, still posted and eligible. Snapshot membership:
     * <ul>
     *   <li>dead (entity present, not alive): pruned permanently, as is a unit killed while the plan runs
     *       ({@link #onDeath});</li>
     *   <li>released via roster ✕ (its post is gone): pruned permanently;</li>
     *   <li>downed: kept, skipped this pass (recoverable);</li>
     *   <li>unloaded (entity absent): kept, skipped this pass.</li>
     * </ul>
     * Nobody is ever posted from a stale list. (The snapshot lives only in the saved plan; it is never sent to the client.)
     */
    private static List<PmcUnitEntity> liveUnits(ServerLevel level, AdvancePlan plan, List<PmcUnitEntity> owned,
                                                 TerritoryData data) {
        Map<UUID, PmcUnitEntity> byUuid = new HashMap<>();
        for (PmcUnitEntity u : owned) byUuid.put(u.getUUID(), u);
        List<PmcUnitEntity> live = new ArrayList<>();
        boolean dropped = false;
        for (Iterator<UUID> it = plan.units().iterator(); it.hasNext(); ) {
            UUID uuid = it.next();
            PmcUnitEntity u = byUuid.get(uuid);
            if (u == null) {
                Entity present = level.getEntity(uuid);
                if (present != null && !present.isAlive()) { // a corpse, not an unloaded unit
                    it.remove();
                    dropped = true;
                }
                continue;
            }
            if (!((ITerritoryPost) u).sewv$hasTerritoryPost()) {
                it.remove();
                dropped = true;
                continue;
            }
            if (u.isAlive() && TerritoryManager.status(u) == TerritoryManager.Status.OK) live.add(u);
        }
        if (dropped) data.setDirty();
        return live;
    }

    /**
     * The Frontline re-run on a dispatch: the SAME assignment the Frontline Tool uses ({@code assignAndPost}), scoped to
     * the contact edge only, with the plan's live snapshot units only, ordered nearest-first from the region centre.
     * Only those units are re-posted; every other posted unit keeps its post (and merely counts as prior coverage).
     * The parent check keeps using the whole front component; the two sets serve different purposes.
     */
    private static void rerunFrontline(ServerPlayer player, ServerLevel level, List<PmcUnitEntity> live,
                                       Set<Long> front, Set<Long> contact, AdvancePlan plan) {
        double[] centre = AdvanceMath.centroid(plan.regionSet());
        Chunk origin = new Chunk(Mth.floor(centre[0]) >> 4, Mth.floor(centre[1]) >> 4);
        List<Chunk> ordered = new ArrayList<>(contact.size());
        for (long key : contact) ordered.add(FrontlineMath.unpack(key));
        FrontlineMath.sortByDistance(ordered, origin);

        Set<Integer> ids = new HashSet<>();
        for (PmcUnitEntity u : live) ids.add(u.getId());
        TerritoryManager.assignAndPost(player, level, new TerritoryManager.Selection(live, ids), front, ordered, origin);
        TerritoryManager.LAST_COVERED.remove(player.getUUID()); // a plan reshuffle, not a chunk going uncovered
    }

    // ---- the wire view ---------------------------------------------------------------------------------------

    /** The plan for this player's current dimension as the client draws it, or null when there is none. */
    static PlanView view(ServerPlayer player, ServerLevel level, Set<Long> claims, TerritoryData data) {
        UUID id = player.getUUID();
        AdvancePlan plan = data.getPlan(id, TerritoryManager.dimKey(level));
        if (plan == null) return null;

        Set<Long> remainder = new HashSet<>(plan.regionSet());
        remainder.removeAll(claims);
        AdvanceMath.Parent parent = AdvanceMath.parentState(AdvanceMath.frontKeys(claims), remainder);
        byte reason = parent == AdvanceMath.Parent.HEALTHY ? START_OK
                : parent == AdvanceMath.Parent.GONE ? START_NO_FRONT : START_FRAGMENTED;
        Runtime rt = RUNTIME.get(id);
        byte hold = plan.state() == AdvancePlan.RUNNING && rt != null ? (byte) rt.hold.ordinal() : 0;
        return new PlanView(plan.state(), hold, reason, plan.currentLayer(), plan.layerCount(),
                plan.region(), plan.layerOf(), plan.arrows());
    }
}
