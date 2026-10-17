package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.client.gui.AirportScreen;

/** Physical-client stub so common code never touches {@link net.minecraft.client.gui.screens.Screen}. */
public final class AirportClient {

    private AirportClient() {}

    /** Drop the map plot for a runway that was actually broken, not chunk-unloaded. */
    public static void forgetPlot(BlockPos pos) {
        AirportPlots.forget(pos);
    }

    public static void open(BlockPos pos, int x1, int z1, int x2, int z2, boolean cleared,
                            AirportClearance.Status status, @Nullable BlockPos blocker,
                            int stripLength, int stripWidth, int capacity,
                            float slotFactor, float bufferFactor, float extraFactor,
                            boolean fromHighEnd) {
        Minecraft mc = Minecraft.getInstance();
        // The map shading rides this reply rather than a sync of its own — see AirportPlots. The
        // dimension is the player's own: this packet only ever arrives while they are stood at the
        // runway block they are editing.
        if (cleared && mc.level != null) {
            AirportPlots.note(pos, mc.level.dimension(), x1, z1, x2, z2);
        } else {
            forgetPlot(pos);
        }
        mc.setScreen(new AirportScreen(
                pos, x1, z1, x2, z2, cleared, status, blocker, stripLength, stripWidth, capacity,
                slotFactor, bufferFactor, extraFactor, fromHighEnd));
    }
}
