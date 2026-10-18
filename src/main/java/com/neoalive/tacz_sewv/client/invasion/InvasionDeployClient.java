package com.neoalive.tacz_sewv.client.invasion;

import java.util.List;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.invasion.force.ForceKind;

/** Physical-client stub for {@link com.neoalive.tacz_sewv.network.PacketInvasionDeployPrompt}. */
public final class InvasionDeployClient {

    private InvasionDeployClient() {}

    public static void open(List<ForceKind> options, int seconds) {
        if (options.isEmpty()) return;
        Minecraft.getInstance().setScreen(new DeployScreen(options, seconds));
    }
}
