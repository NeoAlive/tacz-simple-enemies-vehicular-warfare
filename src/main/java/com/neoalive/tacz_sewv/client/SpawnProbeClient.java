package com.neoalive.tacz_sewv.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.client.gui.SpawnProbeScreen;
import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Physical-client stub for spawn_probe packets / visibility.
 * Keeps {@link net.minecraft.client.gui.screens.Screen} off the dedicated-server classpath.
 */
public final class SpawnProbeClient {

    private SpawnProbeClient() {}

    public static boolean showProbes() {
        return ClientGameRules.get(ModGameRules.SHOW_SPAWN_PROBES);
    }

    public static void openScreen(BlockPos pos, List<String> vehicleList, boolean preCrewedSpawn,
                                  List<String> catalog) {
        Minecraft.getInstance().setScreen(new SpawnProbeScreen(pos, vehicleList, preCrewedSpawn, catalog));
    }
}
