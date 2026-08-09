package com.neoalive.tacz_sewv.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Facade for trench SavedData mutations from player topology edit sites. */
public final class TrenchTracker {

    private TrenchTracker() {}

    public static void onTopologyChanged(Level level, BlockPos origin) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        TrenchNetworks.get(serverLevel).refreshAround(serverLevel, origin);
    }

    public static void onEmplacementChanged(Level level, BlockPos pos, boolean present) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        TrenchNetworks data = TrenchNetworks.get(serverLevel);
        data.setEmplacement(pos, present);
        // Neighbour trenches may gain/lose an adjacent emplacement link for map flags.
        data.refreshAround(serverLevel, pos);
    }
}
