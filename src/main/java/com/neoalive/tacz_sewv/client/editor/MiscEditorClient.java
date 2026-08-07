package com.neoalive.tacz_sewv.client.editor;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.MiscEditorScreen;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldVehicleClasses.CueKind;

/** Client-only open for {@link com.neoalive.tacz_sewv.network.PacketOpenMiscEditor}. */
public final class MiscEditorClient {

    private MiscEditorClient() {}

    public static void openScreen(Map<CueKind, List<String>> cues,
                                  Map<CueKind, List<String>> cueDefaults,
                                  Map<TankFaction, List<String>> armor,
                                  Map<TankFaction, List<String>> armorDefaults,
                                  List<String> armorCatalog) {
        Minecraft.getInstance().setScreen(new MiscEditorScreen(
                cues, cueDefaults, armor, armorDefaults, armorCatalog));
    }
}
