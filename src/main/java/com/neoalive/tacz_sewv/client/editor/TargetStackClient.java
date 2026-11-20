package com.neoalive.tacz_sewv.client.editor;

import java.util.Map;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.client.gui.TargetStackScreen;
import com.neoalive.tacz_sewv.spawn.TankSpawner.TankFaction;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/** Client-only open for {@link com.neoalive.tacz_sewv.network.PacketOpenTargetStack}. */
public final class TargetStackClient {

    private TargetStackClient() {}

    public static void openScreen(Map<TankFaction, WorldTargetStack.Stack> stacks, WorldTargetStack.Stack defaults) {
        Minecraft.getInstance().setScreen(new TargetStackScreen(stacks, defaults));
    }
}
