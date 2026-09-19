package com.neoalive.tacz_sewv.map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A cleared helipad for the world map: the master block position and its dimension. */
public record HelipadMarker(BlockPos pos, ResourceKey<Level> dimension) {

    public double x() {
        return this.pos.getX() + 0.5;
    }

    public double y() {
        return this.pos.getY() + 0.5;
    }

    public double z() {
        return this.pos.getZ() + 0.5;
    }
}
