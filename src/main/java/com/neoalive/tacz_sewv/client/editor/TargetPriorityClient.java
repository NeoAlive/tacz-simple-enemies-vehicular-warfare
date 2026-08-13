package com.neoalive.tacz_sewv.client.editor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.TargetPriorityScreen;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;

/** Client-only open for {@link com.neoalive.tacz_sewv.network.PacketOpenTargetPriority}. */
public final class TargetPriorityClient {

    private TargetPriorityClient() {}

    public static void openScreen(Map<TankFaction, Set<String>> excluded,
                                  Map<TankFaction, Set<String>> defaults,
                                  List<String> catalog) {
        Minecraft.getInstance().setScreen(new TargetPriorityScreen(excluded, defaults, catalog));
    }
}
