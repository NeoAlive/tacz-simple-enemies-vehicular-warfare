package com.neoalive.tacz_sewv.client.fob;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.QuartersBenchScreen;
import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;

public final class FobClient {

    private FobClient() {}

    public static void acceptData(FobGuiSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof QuartersBenchScreen screen) {
            screen.applySnapshot(snapshot);
        } else {
            mc.setScreen(new QuartersBenchScreen(snapshot));
        }
    }
}
