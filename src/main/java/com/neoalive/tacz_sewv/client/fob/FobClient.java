package com.neoalive.tacz_sewv.client.fob;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.ParkingFieldScreen;
import com.neoalive.tacz_sewv.client.gui.QuartersBenchScreen;
import com.neoalive.tacz_sewv.fob.FobGuiSnapshot;

public final class FobClient {

    private FobClient() {}

    public static void acceptData(FobGuiSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (snapshot.kind() == FobGuiSnapshot.GuiKind.PARKING) {
            if (mc.screen instanceof ParkingFieldScreen screen) {
                screen.applySnapshot(snapshot);
            } else {
                mc.setScreen(new ParkingFieldScreen(snapshot));
            }
            return;
        }
        if (mc.screen instanceof QuartersBenchScreen screen) {
            screen.applySnapshot(snapshot);
        } else {
            mc.setScreen(new QuartersBenchScreen(snapshot));
        }
    }
}
