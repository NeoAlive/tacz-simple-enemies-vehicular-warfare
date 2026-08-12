package com.neoalive.tacz_sewv.client;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.neoalive.tacz_sewv.airport.AirportClearance;
import com.neoalive.tacz_sewv.client.gui.AirportScreen;

/** Physical-client stub so network packets never touch {@link net.minecraft.client.gui.screens.Screen}. */
public final class AirportClient {

    private AirportClient() {}

    public static void open(BlockPos pos, int x1, int z1, int x2, int z2, boolean cleared,
                            AirportClearance.Status status, @Nullable BlockPos blocker,
                            int stripLength, int stripWidth, int capacity,
                            float slotFactor, float bufferFactor, float extraFactor) {
        Minecraft.getInstance().setScreen(new AirportScreen(
                pos, x1, z1, x2, z2, cleared, status, blocker, stripLength, stripWidth, capacity,
                slotFactor, bufferFactor, extraFactor));
    }
}
