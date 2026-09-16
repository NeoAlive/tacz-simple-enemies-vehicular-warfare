package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.neoalive.tacz_sewv.client.gui.CarrierOpsScreen;
import com.neoalive.tacz_sewv.compat.NeoArmsCarrierAccess;

public final class CarrierOpsClient {

    private CarrierOpsClient() {}

    public static void open(int carrierEntityId, NeoArmsCarrierAccess.Strip strip, boolean staticMode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new CarrierOpsScreen(carrierEntityId, strip, staticMode));
    }

    public static void applyDeployResult(boolean ok, @Nullable String messageKey) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.screen instanceof CarrierOpsScreen screen) {
            screen.applyDeployResult(ok,
                    messageKey == null || messageKey.isEmpty()
                            ? null
                            : Component.translatable(messageKey));
        }
    }
}
