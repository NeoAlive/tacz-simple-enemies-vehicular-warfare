package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.airport.HelipadClearance;
import com.neoalive.tacz_sewv.client.gui.HelipadScreen;

/** Physical-client stub so common code never touches a Screen class (mirrors {@link AirportClient}). */
public final class HelipadClient {

    private HelipadClient() {}

    /** Open the panel, or refresh it in place when a check reply arrives for the pad already showing. */
    public static void open(BlockPos pos, boolean cleared, HelipadClearance.Status status,
                            @Nullable BlockPos blocker, boolean occupied) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof HelipadScreen open && open.pos().equals(pos)) {
            open.update(cleared, status, blocker, occupied);
            return;
        }
        mc.setScreen(new HelipadScreen(pos, cleared, status, blocker, occupied));
    }
}
