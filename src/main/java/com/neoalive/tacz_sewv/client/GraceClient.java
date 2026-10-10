package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.GraceScreen;

/** Client-only entry point for {@code PacketGraceStart}, so the packet never links screen classes. */
public final class GraceClient {

    private GraceClient() {}

    public static void open(int days) {
        Minecraft.getInstance().setScreen(new GraceScreen(days));
    }
}
