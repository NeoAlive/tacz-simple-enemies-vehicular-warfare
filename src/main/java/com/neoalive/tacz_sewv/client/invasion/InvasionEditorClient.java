package com.neoalive.tacz_sewv.client.invasion;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.client.gui.CapturePointScreen;
import com.neoalive.tacz_sewv.client.gui.TeamBaseScreen;
import com.neoalive.tacz_sewv.invasion.force.ForceKind;
import com.neoalive.tacz_sewv.invasion.force.TeamBaseConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Physical-client stub for invasion block config packets.
 * Keeps {@link net.minecraft.client.gui.screens.Screen} off the dedicated-server classpath.
 */
public final class InvasionEditorClient {

    private InvasionEditorClient() {}

    public static void openCapturePoint(BlockPos pos, int pointId, int timeToCaptureSeconds, int radiusInBlocks,
                                        String ownedTeam, boolean invisible, List<String> teams) {
        Minecraft.getInstance().setScreen(new CapturePointScreen(
                pos, pointId, timeToCaptureSeconds, radiusInBlocks, ownedTeam, invisible, teams));
    }

    public static void openTeamBase(BlockPos pos, TeamBaseConfig config, List<String> teams,
                                    List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                                    List<String> catalog, Map<TankFaction, Map<ForceKind, List<String>>> worldPools) {
        Minecraft.getInstance().setScreen(new TeamBaseScreen(
                pos, config, teams, onlinePlayerNames, onlinePlayerUuids, catalog, worldPools));
    }
}
