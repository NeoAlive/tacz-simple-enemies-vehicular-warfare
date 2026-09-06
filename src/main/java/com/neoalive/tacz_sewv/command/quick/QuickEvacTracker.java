package com.neoalive.tacz_sewv.command.quick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.bridge.IHelicopterPilot;
import com.neoalive.tacz_sewv.bridge.IVehicleBoarder;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.entity.ai.support.FlightOrders;
import com.neoalive.tacz_sewv.network.PacketHelicopterCommand;

/**
 * Per-heli Quick Evac runner. Absolute {@link ServerLevel#getGameTime()} deadlines only.
 *
 * <p>Stale-safe: never holds live entity references across ticks — every poll re-resolves by
 * network id. Each leg takes off when its reserved units are aboard (or gone) or the timeout hits.
 *
 * <p>One active run per player (a new dispatch replaces the previous after aborting it).
 */
@Mod.EventBusSubscriber(modid = TaczSewv.MODID)
public final class QuickEvacTracker {

    private static final int POLL_INTERVAL = 10;

    private static final Map<UUID, Run> ACTIVE = new HashMap<>();

    private QuickEvacTracker() {}

    public static void start(UUID playerId, List<LegSpec> legs, long now) {
        // Replace: drop prior land commands / board walks before latching the new run.
        cancelPlayer(playerId, resolveLevel(playerId));
        long deadline = now + SewvConfig.QUICK_EVAC_BOARD_TIMEOUT_TICKS.get();
        List<Leg> live = new ArrayList<>(legs.size());
        for (LegSpec spec : legs) {
            live.add(new Leg(spec.heliId(), spec.pilotId(), List.copyOf(spec.reservedUnitIds())));
        }
        ACTIVE.put(playerId, new Run(playerId, live, deadline));
    }

    /**
     * Abort an in-flight Quick Evac: clear land/takeoff on each leg's pilot and drop reserved
     * board orders. Returns {@code true} if a run was active.
     */
    public static boolean cancelPlayer(UUID playerId, ServerLevel level) {
        Run run = ACTIVE.remove(playerId);
        if (run == null) return false;
        if (level == null) return true;
        for (Leg leg : run.legs) {
            if (leg.done) continue;
            Entity pilotEnt = level.getEntity(leg.pilotId);
            Entity heliEnt = level.getEntity(leg.heliId);
            if (pilotEnt instanceof PmcUnitEntity pilot
                    && heliEnt instanceof VehicleEntity hull) {
                FlightOrders.clearCommand(pilot, hull);
            } else if (pilotEnt instanceof PmcUnitEntity pilot) {
                FlightOrders.clearCommand(pilot, null);
            }
            for (int unitId : leg.reservedUnitIds) {
                Entity e = level.getEntity(unitId);
                if (!(e instanceof PmcUnitEntity pmc)) continue;
                IVehicleBoarder boarder = (IVehicleBoarder) pmc;
                if (boarder.tacz_sewv$getMountTargetId() == leg.heliId) {
                    boarder.tacz_sewv$setBoarding(false);
                    boarder.tacz_sewv$setMountTargetId(-1);
                    if (pmc.getVehicle() == null) {
                        pmc.getNavigation().stop();
                    }
                }
            }
        }
        return true;
    }

    public static boolean isActive(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    private static ServerLevel resolveLevel(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || !(player.level() instanceof ServerLevel level)) return null;
        return level;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || ACTIVE.isEmpty()) return;
        if (server.getTickCount() % POLL_INTERVAL != 0) return;

        Iterator<Map.Entry<UUID, Run>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Run> entry = it.next();
            Run run = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(run.playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (tick(level, player, run)) {
                it.remove();
            }
        }
    }

    /** @return {@code true} when every leg is finished. */
    private static boolean tick(ServerLevel level, ServerPlayer player, Run run) {
        long now = level.getGameTime();
        boolean timedOut = now >= run.deadline;

        for (Leg leg : run.legs) {
            if (leg.done) continue;

            LiveHeli live = resolve(level, player, leg);
            if (live == null) {
                leg.done = true;
                continue;
            }

            // Full mid-evac (extra boarders, etc.) — lift immediately.
            if (QuickEvacPipeline.isFull(live.hull()) || timedOut || reservationsMet(level, leg, live)) {
                takeoff(live);
                leg.done = true;
            }
        }

        for (Leg leg : run.legs) {
            if (!leg.done) return false;
        }
        return true;
    }

    /**
     * True when every reserved unit is either dead/gone or already a passenger of this hull.
     * Empty reservation never auto-completes — those legs only depart on timeout (they still land
     * near nearby PMCs so the formation doesn't leave a free bird circling forever).
     */
    private static boolean reservationsMet(ServerLevel level, Leg leg, LiveHeli live) {
        if (leg.reservedUnitIds.isEmpty()) return false;
        for (int unitId : leg.reservedUnitIds) {
            Entity e = level.getEntity(unitId);
            if (!(e instanceof PmcUnitEntity pmc) || !pmc.isAlive()) continue; // gone = satisfied
            if (pmc.getVehicle() != live.hull()) return false;
        }
        return true;
    }

    private static void takeoff(LiveHeli live) {
        int cruise = (PacketHelicopterCommand.MIN_ALTITUDE
                + PacketHelicopterCommand.MAX_ALTITUDE) / 2;
        FlightOrders.takeoff(live.pilot(), live.hull(), cruise);
    }

    private static LiveHeli resolve(ServerLevel level, ServerPlayer player, Leg leg) {
        Entity heliEnt = level.getEntity(leg.heliId);
        Entity pilotEnt = level.getEntity(leg.pilotId);
        if (!(heliEnt instanceof VehicleEntity hull) || !hull.isAlive()) return null;
        if (!(pilotEnt instanceof PmcUnitEntity pilot) || !pilot.isAlive()) return null;
        if (!com.neoalive.tacz_sewv.invasion.PmcOwnerSupport.isOwner(player, pilot)) return null;
        if (!(pilot instanceof IHelicopterPilot)) return null;
        if (pilot.getVehicle() != hull) return null;
        if (hull.getFirstPassenger() != pilot) return null;
        return new LiveHeli(hull, pilot);
    }

    /** Dispatch-time leg description — ids only. */
    public record LegSpec(int heliId, int pilotId, List<Integer> reservedUnitIds) {}

    private static final class Run {
        final UUID playerId;
        final List<Leg> legs;
        final long deadline;

        Run(UUID playerId, List<Leg> legs, long deadline) {
            this.playerId = playerId;
            this.legs = legs;
            this.deadline = deadline;
        }
    }

    private static final class Leg {
        final int heliId;
        final int pilotId;
        final List<Integer> reservedUnitIds;
        boolean done;

        Leg(int heliId, int pilotId, List<Integer> reservedUnitIds) {
            this.heliId = heliId;
            this.pilotId = pilotId;
            this.reservedUnitIds = reservedUnitIds;
        }
    }

    private record LiveHeli(VehicleEntity hull, PmcUnitEntity pilot) {}
}
