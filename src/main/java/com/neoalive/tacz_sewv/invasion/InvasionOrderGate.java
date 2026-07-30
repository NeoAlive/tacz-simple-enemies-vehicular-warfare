package com.neoalive.tacz_sewv.invasion;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Player order surfaces are locked while an invasion match is live — AI capture + command-tier
 * tactics own the match. Server gates packets so forged sends cannot bypass the UI.
 */
public final class InvasionOrderGate {

    private InvasionOrderGate() {}

    public static boolean blocked(ServerLevel level) {
        return InvasionSession.isActive(level);
    }

    public static void deny(Player player) {
        player.displayClientMessage(
                Component.translatable("message.tacz_sewv.invasion.orders_locked"), true);
    }

    /** True when the sender's dimension has a live invasion (caller should abort). */
    public static boolean denyIfActive(ServerPlayer player) {
        if (player == null) return true;
        if (!blocked(player.serverLevel())) return false;
        deny(player);
        return true;
    }
}
