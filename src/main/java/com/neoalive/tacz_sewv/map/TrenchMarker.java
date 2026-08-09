package com.neoalive.tacz_sewv.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** One trench network (or standalone emplacement) for the Xaero map overlay. */
public record TrenchMarker(
        int networkId,
        double x,
        double y,
        double z,
        ResourceKey<Level> dimension,
        int cellCount,
        boolean hasEmplacement
) {}
