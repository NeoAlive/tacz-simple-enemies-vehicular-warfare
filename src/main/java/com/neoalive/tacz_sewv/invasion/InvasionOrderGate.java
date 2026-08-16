package com.neoalive.tacz_sewv.invasion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Formerly locked every player order surface while an invasion match was live. That conflicted with
 * team-base PMC Owner (and player-owned invasion fleets): markers could not be commanded from Xaero
 * or the TDT. Ownership is still enforced by {@link com.neoalive.tacz_sewv.crew.OrderAuth}.
 */
public final class InvasionOrderGate {

    private InvasionOrderGate() {}

    public static boolean blocked(ServerLevel level) {
        return false;
    }

    public static void deny(Player player) {
        // no-op — gate retired
    }

    /** Always false: invasion no longer blocks order packets. */
    public static boolean denyIfActive(ServerPlayer player) {
        return false;
    }
}
