package com.neoalive.tacz_sewv.entity.ai.command.platoon;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import com.neoalive.tacz_sewv.entity.ai.core.HullFacts;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;
import com.neoalive.tacz_sewv.entity.ai.support.PatrolSupport;
import com.neoalive.tacz_sewv.entity.unit.PmcCommanderEntity;

/**
 * Writes a {@link CommanderOrderType} onto a platoon's members, scoped to that platoon only —
 * never the owner's whole fleet. Server-side, called directly from {@link CommanderAutoOrderGoal}
 * (autonomous, no packet — there is no player to authorize).
 *
 * <p>Reuses the real order primitives: {@link PatrolSupport}'s area task for every member (a crew
 * drives it, a unit on foot walks it through {@code AreaTaskInfantryGoal}) and
 * {@link DriveHelicopterGoal#setForcedRappel} for rappel.
 *
 * <p>SEARCH_AND_DESTROY/PATROL are auto-dispatched standing orders, not player ones — left alone
 * they would hold forever, so each dispatch stamps a deadline {@link #expireStale} clears
 * ({@link PlatoonRegistry} calls it every scan). Player-issued orders from the Quick Wheel go through a
 * different path entirely and are never in this map.
 */
public final class CommanderOrderDispatch {

    private static final int SEARCH_RADIUS = 24;
    private static final int PATROL_RADIUS = 24;
    /** 50 seconds. */
    private static final int AUTO_ORDER_TIMEOUT_TICKS = 1000;

    private static final Map<Integer, Long> AUTO_ORDER_DEADLINE = new ConcurrentHashMap<>();

    private CommanderOrderDispatch() {}

    public static void dispatch(ServerLevel level, PmcCommanderEntity commander, Platoon platoon, CommanderOrderType order) {
        switch (order) {
            case SEARCH_AND_DESTROY -> searchAndDestroy(level, commander, platoon);
            case PATROL -> patrol(level, commander, platoon);
            case RAPPEL -> rappel(commander, platoon);
        }
    }

    private static void searchAndDestroy(ServerLevel level, PmcCommanderEntity commander, Platoon platoon) {
        BlockPos origin = commander.blockPosition();
        long deadline = level.getGameTime() + AUTO_ORDER_TIMEOUT_TICKS;
        for (int id : platoon.memberIds()) {
            if (!(level.getEntity(id) instanceof PmcUnitEntity pmc)) continue;
            PatrolSupport.beginSearch(pmc, origin, SEARCH_RADIUS, 0, 1);
            AUTO_ORDER_DEADLINE.put(id, deadline);
        }
    }

    private static void patrol(ServerLevel level, PmcCommanderEntity commander, Platoon platoon) {
        BlockPos origin = commander.blockPosition();
        long deadline = level.getGameTime() + AUTO_ORDER_TIMEOUT_TICKS;
        for (int id : platoon.memberIds()) {
            if (level.getEntity(id) instanceof PmcUnitEntity pmc) {
                PatrolSupport.beginPatrol(pmc, origin, PATROL_RADIUS);
                AUTO_ORDER_DEADLINE.put(id, deadline);
            }
        }
    }

    private static void rappel(PmcCommanderEntity commander, Platoon platoon) {
        if (platoon.type() != Platoon.Type.INFANTRY) return;
        if (!(commander.getVehicle() instanceof VehicleEntity hull) || !HullFacts.isHelicopterHull(hull)) return;
        DriveHelicopterGoal.setForcedRappel(hull);
    }

    /** Network ids don't survive a server restart into the next world — drop every tracked deadline. */
    static void clearDeadlines() {
        AUTO_ORDER_DEADLINE.clear();
    }

    /** Clears any auto-dispatched PATROL/SEARCH_AND_DESTROY past its 50s deadline. */
    public static void expireStale(ServerLevel level) {
        if (AUTO_ORDER_DEADLINE.isEmpty()) return;
        long now = level.getGameTime();
        Iterator<Map.Entry<Integer, Long>> it = AUTO_ORDER_DEADLINE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (now < e.getValue()) continue;
            it.remove();
            if (level.getEntity(e.getKey()) instanceof PmcUnitEntity pmc) {
                // Also drops a sweep box an older build left on the unit.
                PatrolSupport.clearSweepMembership(pmc, "CommanderOrderDispatch.expire");
            }
        }
    }
}
