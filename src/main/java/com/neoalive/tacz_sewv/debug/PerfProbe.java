package com.neoalive.tacz_sewv.debug;

import java.util.Map;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import com.neoalive.tacz_sewv.entity.ai.command.BattleGroup;
import com.neoalive.tacz_sewv.entity.ai.command.CommandCoordinator;
import com.neoalive.tacz_sewv.entity.ai.sensor.HullLocalScan;

/**
 * In-game read of the AI-scale counters that already exist ({@link HullLocalScan#stats()},
 * {@link CommandCoordinator#groupsView()}) but had nothing surfacing them — reachable as
 * {@code /sewv debug perf}. Meant for judging a real battle's scale before tuning anything, not
 * for guessing.
 */
public final class PerfProbe {

    private PerfProbe() {}

    public static String report(ServerLevel here) {
        int pmc = 0;
        int ru = 0;
        int us = 0;
        int vehicles = 0;
        for (Entity e : here.getAllEntities()) {
            if (e instanceof PmcUnitEntity) pmc++;
            else if (e instanceof RUunitEntity) ru++;
            else if (e instanceof USunitEntity) us++;
            else if (e instanceof VehicleEntity) vehicles++;
        }

        int groups = 0;
        int members = 0;
        int influenceCells = 0;
        for (Map<Integer, BattleGroup> byId : CommandCoordinator.groupsView().values()) {
            for (BattleGroup g : byId.values()) {
                groups++;
                members += g.size();
                influenceCells += g.influenceMap().cellCount();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dimensionName(here)).append(": ")
                .append("pmc=").append(pmc).append(' ')
                .append("ru=").append(ru).append(' ')
                .append("us=").append(us).append(' ')
                .append("vehicles=").append(vehicles).append('\n');
        sb.append("battle groups (all dimensions): ").append(groups)
                .append(" (members=").append(members)
                .append(", influenceCells=").append(influenceCells).append(")\n");
        sb.append("HullLocalScan: ").append(HullLocalScan.stats()).append('\n');
        sb.append("IdleGroupSupport: ").append(
                com.neoalive.tacz_sewv.entity.ai.support.IdleGroupSupport.stats()).append('\n');
        sb.append(PathingPerf.snapshotAndReset());
        return sb.toString();
    }

    private static String dimensionName(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        return key.location().toString();
    }
}
