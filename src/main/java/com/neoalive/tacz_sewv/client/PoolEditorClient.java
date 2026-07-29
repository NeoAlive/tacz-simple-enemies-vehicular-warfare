package com.neoalive.tacz_sewv.client;

import com.neoalive.tacz_sewv.client.gui.PoolEditorScreen;
import com.neoalive.tacz_sewv.util.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehiclePools.Category;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/**
 * Physical-client stub for the pool-editor packet.
 * Isolates {@link PoolEditorScreen} (and its {@code Screen} supertype) from the common
 * class {@code PacketOpenPoolEditor}, which is loaded on the dedicated server.
 */
public final class PoolEditorClient {

    private PoolEditorClient() {}

    public static void openScreen(Map<TankFaction, Map<Category, List<String>>> pools,
                                  Map<TankFaction, Map<Category, List<String>>> defaults,
                                  List<String> catalog) {
        Minecraft.getInstance().setScreen(new PoolEditorScreen(pools, defaults, catalog));
    }
}
