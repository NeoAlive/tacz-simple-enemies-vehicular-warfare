package com.neoalive.tacz_sewv.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.config.ConfigUIScreen;
import com.neoalive.tacz_sewv.config.ConfigApplier;
import com.neoalive.tacz_sewv.config.ConfigWireCodec;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketRequestConfigUI;

public final class ConfigUIClient {

    private ConfigUIClient() {}

    /** Open from pause menu or when not yet synced with server. */
    public static void requestOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.getSingleplayerServer() != null) {
            boolean canEdit = mc.player.hasPermissions(2);
            Map<Integer, Object> server = canEdit
                    ? ConfigApplier.captureServerSnapshot(mc.getSingleplayerServer())
                    : Map.of();
            open(canEdit, server);
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new PacketRequestConfigUI());
    }

    public static void open(boolean canEditServer, Map<Integer, Object> serverSnapshot) {
        Map<Integer, String> serverDraft = new HashMap<>();
        for (Map.Entry<Integer, Object> e : serverSnapshot.entrySet()) {
            serverDraft.put(e.getKey(), ConfigWireCodec.snapshotToDraft(e.getValue()));
        }
        Map<Integer, String> clientDraft = new HashMap<>();
        for (var entry : com.neoalive.tacz_sewv.config.ConfigRegistry.forScope(
                com.neoalive.tacz_sewv.config.ConfigScope.CLIENT)) {
            clientDraft.put(entry.index, entry.draftString());
        }
        Minecraft.getInstance().setScreen(new ConfigUIScreen(canEditServer, clientDraft, serverDraft));
    }
}
