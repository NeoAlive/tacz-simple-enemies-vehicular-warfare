package com.neoalive.tacz_sewv.map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Player-owned FOB marker for the world map (command block position). */
public record FobMarker(
        BlockPos commandPos,
        ResourceKey<Level> dimension,
        boolean valid,
        /** Assigned vehicles the owner may still command (excludes stolen RU/US hulls). */
        int assignedVehicles,
        /** Valid layout, parking linked, and at least one assigned vehicle. */
        boolean routeReady) {

    public double x() {
        return commandPos.getX() + 0.5;
    }

    public double y() {
        return commandPos.getY() + 0.5;
    }

    public double z() {
        return commandPos.getZ() + 0.5;
    }
}
