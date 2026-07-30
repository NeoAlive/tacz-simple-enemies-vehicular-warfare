package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.client.gui.CapturePointScreen;
import com.neoalive.tacz_sewv.client.gui.TeamBaseScreen;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Physical-client stub for invasion block config packets.
 * Keeps {@link net.minecraft.client.gui.screens.Screen} off the dedicated-server classpath.
 */
public final class InvasionEditorClient {

    private InvasionEditorClient() {}

    public static void openCapturePoint(BlockPos pos, int pointId, boolean showBillboard, double billboardYOffset,
                                        int timeToCaptureSeconds, int radiusInBlocks, String ownedTeam,
                                        List<String> teams) {
        Minecraft.getInstance().setScreen(new CapturePointScreen(
                pos, pointId, showBillboard, billboardYOffset, timeToCaptureSeconds, radiusInBlocks,
                ownedTeam, teams));
    }

    public static void openTeamBase(BlockPos pos, String assignedTeam, boolean playerOwned,
                                    boolean spawnPlayerOwnedTanksWithNpc, TankFaction crewFaction,
                                    int aiVehicleCount, int timeToCaptureSeconds, int radiusInBlocks,
                                    String ownedTeam, List<String> vehiclePool, List<String> teams,
                                    List<String> catalog) {
        Minecraft.getInstance().setScreen(new TeamBaseScreen(
                pos, assignedTeam, playerOwned, spawnPlayerOwnedTanksWithNpc, crewFaction,
                aiVehicleCount, timeToCaptureSeconds, radiusInBlocks, ownedTeam, vehiclePool, teams,
                catalog));
    }
}
