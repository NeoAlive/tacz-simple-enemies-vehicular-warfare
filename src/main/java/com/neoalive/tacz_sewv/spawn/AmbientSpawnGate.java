package com.neoalive.tacz_sewv.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.neoalive.tacz_sewv.compat.MineColoniesCompat;
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

    /**
     * {@link #allows(ServerLevel)} plus "and not on someone else's doorstep".
     *
     * <p>Today the only protected ground is a MineColonies claim. A colony is a build the player
     * spent hours on, defended by guards that cannot even see a vehicle
     * ({@link MineColoniesCompat}), so dropping a convoy or a mortar battery inside one is a
     * one-sided massacre rather than a fight. The facade answers false when MineColonies is
     * absent, so this costs a boolean read on a normal install.
     */
    public static boolean allowsAt(ServerLevel level, BlockPos pos) {
        return allows(level) && !MineColoniesCompat.inAnyColony(level, pos);
    }
}
