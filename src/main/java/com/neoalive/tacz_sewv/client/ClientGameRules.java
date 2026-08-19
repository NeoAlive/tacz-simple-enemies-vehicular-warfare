package com.neoalive.tacz_sewv.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameRules;

/**
 * Client-side counterpart to {@link com.neoalive.tacz_sewv.init.ModGameRules#server}, for the
 * handful of debug reads that happen on the render/client thread (a dedicated-server-connected
 * client has no {@code MinecraftServer} in this JVM for the server-side accessor to find).
 * Gamerules are synced to the client, so {@code Minecraft.getInstance().level} carries the same
 * values the server holds once a world is joined; before that (main menu) this reads as off.
 *
 * <p>Client-only — must never be referenced from common/server code (this class touches {@link
 * Minecraft}, which does not exist on a dedicated server).
 */
public final class ClientGameRules {

    private ClientGameRules() {}

    public static boolean get(GameRules.Key<GameRules.BooleanValue> rule) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameRules().getBoolean(rule);
    }
}
