package com.neoalive.tacz_sewv.spawn;

import net.minecraft.server.level.ServerLevel;

import com.neoalive.tacz_sewv.init.ModGameRules;

/**
 * Master gate for automatic world spawns: SEM events, village garrisons, berezka structures,
 * and SEWV procedural events. Player-initiated spawns ({@code /sewv spawn}, spawn eggs, capture
 * conversion) do not consult this.
 */
public final class AmbientSpawnGate {

    private AmbientSpawnGate() {}

    public static boolean allows(ServerLevel level) {
        return level.getGameRules().getBoolean(ModGameRules.AMBIENT_SPAWNS);
    }
}
