package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.entity.ai.core.VehicleTargeting;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketOrderQueueState;

/**
 * Quick Wheel order queue, one per player, server-side.
 *
 * <p><b>An order is queued by capturing its packets, not by describing it.</b> Every wheel order
 * already ends as a C2S packet; the client brackets those packets with BEGIN/END and each handler
 * hands itself to {@link #capture} instead of running. Starting the order later replays exactly
 * those handlers with their original context (whose connection is this player's live one — the
 * queue is dropped on logout), so ownership, FOB and downed checks all re-run at start time and no
 * order logic is duplicated here. Unit ids were resolved by the client when the order was queued.
 *
 * <p>The head of the queue is the running order. It advances when {@link #done} says so; orders it
 * does not know are persistent and only advance on a manual skip.
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class OrderQueue {

    private static final int POLL_INTERVAL = 10;
    private static final double FOOT_ARRIVAL = 6.0;
    // Trust boundary: a client decides how much gets captured, so both are capped.
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_REPLAYS = 64;

    private record Entry(String pipelineId, String label, List<Integer> units, List<Runnable> replays) {}

    private static final class State {
        final Deque<Entry> entries = new ArrayDeque<>();
        Entry capturing;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** True while replaying, so a replayed handler runs instead of being captured again. */
    private static boolean replaying;

    private OrderQueue() {}

    public static void begin(ServerPlayer player, String pipelineId, String label, List<Integer> units) {
        STATES.computeIfAbsent(player.getUUID(), k -> new State()).capturing =
                new Entry(pipelineId, label, List.copyOf(units), new ArrayList<>());
    }

    public static void end(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null || s.capturing == null) return;
        Entry e = s.capturing;
        s.capturing = null;
        // The client rejected it or the pick was cancelled: nothing was sent, nothing to queue.
        if (e.replays.isEmpty() || s.entries.size() >= MAX_ENTRIES) return;
        s.entries.addLast(e);
        if (s.entries.size() == 1) start(e);
        sync(player, s);
    }

    /**
     * Called first thing in each order handler (on the server thread).
     * @return {@code true} if the handler was queued and must not run now
     */
    public static boolean capture(ServerPlayer player, Runnable replay) {
        if (replaying) return false;
        State s = STATES.get(player.getUUID());
        if (s == null || s.capturing == null) return false;
        if (s.capturing.replays.size() < MAX_REPLAYS) s.capturing.replays.add(replay);
        return true;
    }

    public static void skip(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null || s.entries.pollFirst() == null) return;
        advance(player, s);
    }

    public static void clear(ServerPlayer player) {
        State s = STATES.remove(player.getUUID());
        if (s != null && !s.entries.isEmpty()) sync(player, new State());
    }

    private static void advance(ServerPlayer player, State s) {
        Entry next = s.entries.peekFirst();
        if (next != null) start(next);
        sync(player, s);
    }

    private static void start(Entry e) {
        replaying = true;
        try {
            for (Runnable r : e.replays) r.run();
        } finally {
            replaying = false;
        }
    }

    private static void sync(ServerPlayer player, State s) {
        List<String> labels = new ArrayList<>(s.entries.size());
        for (Entry e : s.entries) labels.add(e.label);
        NetworkHandler.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new PacketOrderQueueState(labels));
    }

    /** Self-completing orders; anything not listed here (follow, hold, patrol, formation...) waits for a skip. */
    private static boolean done(ServerPlayer player, Entry e) {
        switch (e.pipelineId) {
            case QuickCommandRegistry.ID_QUICK_DISMOUNT:
                return true;
            case QuickCommandRegistry.ID_QUICK_REFILL:
                return !QuickRefillTracker.isActive(player.getUUID());
            case QuickCommandRegistry.ID_QUICK_EVAC:
                return !QuickEvacTracker.isActive(player.getUUID());
            case QuickCommandRegistry.ID_QUICK_MOVE:
            case QuickCommandRegistry.ID_QUICK_ATTACK:
            case QuickCommandRegistry.ID_QUICK_BOARD:
            case QuickCommandRegistry.ID_QUICK_TAKEOFF:
            case QuickCommandRegistry.ID_QUICK_LANDING:
            case QuickCommandRegistry.ID_QUICK_LAND_HELIPAD:
            case QuickCommandRegistry.ID_QUICK_EMERGENCY_LAND:
                for (int id : e.units) {
                    if (player.level().getEntity(id) instanceof PmcUnitEntity pmc && pmc.isAlive()
                            && !unitDone(e.pipelineId, pmc)) {
                        return false;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private static boolean unitDone(String pipelineId, PmcUnitEntity pmc) {
        switch (pipelineId) {
            case QuickCommandRegistry.ID_QUICK_MOVE: {
                if (pmc.getOrder() != OrderType.MOVE_TO_POSITION) return true;
                Vec3 dest = pmc.getMoveToTarget();
                if (dest == null) return true;
                // ponytail: one radius for the whole move; units carried into a wide formation
                // park on slots further out and need a skip.
                Entity body = pmc.getRootVehicle();
                double reach = body instanceof VehicleEntity hull
                        ? VehicleTargeting.arrivalDistance(pmc, hull) + 2.0
                        : FOOT_ARRIVAL;
                double dx = body.getX() - dest.x;
                double dz = body.getZ() - dest.z;
                return dx * dx + dz * dz <= reach * reach;
            }
            case QuickCommandRegistry.ID_QUICK_ATTACK: {
                if (pmc.getOrder() != OrderType.ATTACK_THAT_TARGET) return true;
                Entity target = pmc.level().getEntity(pmc.getAttackTargetId());
                return target == null || !target.isAlive();
            }
            case QuickCommandRegistry.ID_QUICK_BOARD:
                return !((IVehicleBoarder) pmc).tacz_sewv$isBoarding();
            default: {
                // Air: TAKEOFF clears itself at cruise altitude, LANDING becomes LANDED on touchdown.
                // A unit that is no longer a pilot never carried the command.
                if (!(pmc.getVehicle() instanceof VehicleEntity hull) || hull.getFirstPassenger() != pmc) {
                    return true;
                }
                int cmd = ((IHelicopterPilot) pmc).sewv$getHeliCommand();
                return cmd != IHelicopterPilot.HELI_CMD_TAKEOFF && cmd != IHelicopterPilot.HELI_CMD_LANDING;
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || STATES.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % POLL_INTERVAL != 0) return;
        for (Map.Entry<UUID, State> it : STATES.entrySet()) {
            State s = it.getValue();
            Entry head = s.entries.peekFirst();
            if (head == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(it.getKey());
            if (player == null || !done(player, head)) continue;
            s.entries.pollFirst();
            advance(player, s);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }
}
