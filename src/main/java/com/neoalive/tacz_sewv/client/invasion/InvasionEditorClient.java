package com.neoalive.tacz_sewv.client.invasion;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.client.gui.CapturePointScreen;
import com.neoalive.tacz_sewv.client.gui.TeamBaseScreen;
import com.neoalive.tacz_sewv.invasion.PmcOwnerKind;
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

    public static void openTeamBase(BlockPos pos, String assignedTeam, boolean playerOwned,
                                    boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                                    int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                                    String ownedTeam, boolean invisible, boolean endInvasionOnCapture,
                                    PmcOwnerKind pmcOwnerKind, String pmcOwnerValue,
                                    List<String> vehiclePool, List<String> enemyTeams, List<String> teams,
                                    List<String> onlinePlayerNames, List<String> onlinePlayerUuids,
                                    List<String> catalog) {
        Minecraft.getInstance().setScreen(new TeamBaseScreen(
                pos, assignedTeam, playerOwned, spawnPlayerOwnedTanksWithNpc, crewFaction,
                aiVehicleCount, timeToCaptureSeconds, radiusInBlocks, ownedTeam, invisible,
                endInvasionOnCapture, pmcOwnerKind, pmcOwnerValue,
                vehiclePool, enemyTeams, teams, onlinePlayerNames, onlinePlayerUuids, catalog));
    }
}
