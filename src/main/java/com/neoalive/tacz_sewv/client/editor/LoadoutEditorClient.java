package com.neoalive.tacz_sewv.client.editor;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.LoadoutEditorScreen;
import com.neoalive.tacz_sewv.network.PacketOpenLoadoutEditor;

/**
 * Physical-client stub for the loadout-editor packet. Isolates {@link LoadoutEditorScreen} (and its
 * {@code Screen} supertype) from the common class {@code PacketOpenLoadoutEditor}, which is loaded
 * on the dedicated server — same reason as {@code PoolEditorClient}.
 */
public final class LoadoutEditorClient {

    private LoadoutEditorClient() {}

    public static void openScreen(PacketOpenLoadoutEditor.Data data) {
        WeaponCatalog.absorb(data);
        Minecraft.getInstance().setScreen(new LoadoutEditorScreen(data));
    }
}
