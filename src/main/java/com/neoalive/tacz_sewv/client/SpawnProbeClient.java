package com.neoalive.tacz_sewv.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.block.SpawnProbeCategory;
import com.neoalive.tacz_sewv.block.SpawnProbeInfantryEntry;
import com.neoalive.tacz_sewv.client.gui.SpawnProbeScreen;
import com.neoalive.tacz_sewv.config.ClientConfig;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/**
 * Physical-client stub for spawn_probe packets / visibility.
 * Keeps {@link net.minecraft.client.gui.screens.Screen} off the dedicated-server classpath.
 */
public final class SpawnProbeClient {

    private SpawnProbeClient() {}

    public static boolean showProbes() {
        return ClientConfig.flag(ClientConfig.SHOW_SPAWN_PROBES);
    }

    public static void openScreen(BlockPos pos, SpawnProbeCategory category, TankFaction factionType,
                                  List<String> vehicleList, boolean preCrewedSpawn,
                                  List<SpawnProbeInfantryEntry> infantryList,
                                  List<String> vehicleCatalog, List<String> infantryCatalog) {
        Minecraft.getInstance().setScreen(new SpawnProbeScreen(
                pos, category, factionType, vehicleList, preCrewedSpawn, infantryList,
                vehicleCatalog, infantryCatalog));
    }
}
