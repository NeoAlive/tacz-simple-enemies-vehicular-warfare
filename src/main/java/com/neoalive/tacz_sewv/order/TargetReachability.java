package com.neoalive.tacz_sewv.order;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Designation-time sanity checks on a target, so an impossible fire mission is refused at the radio
 * rather than accepted and then quietly never fired.
 */
public final class TargetReachability {

    /**
     * How far under the surface counts as underground. Generous on purpose: the point is to catch
     * caves and bunkers, not to reject a target standing inside a building. A house with eight
     * blocks of headroom above the floor is rare; a hillside has many more.
     */
    private static final int UNDERGROUND_DEPTH = 8;

    private TargetReachability() {}

    /**
     * No arcing shell can reach this, so a mortar mission on it would fire into the ceiling.
     *
     * <p>Measured against {@code MOTION_BLOCKING_NO_LEAVES} rather than {@code WORLD_SURFACE}: a
     * target under a tree is in the open as far as artillery is concerned, and counting the canopy
     * as ground would refuse most missions in a forest.
     */
    public static boolean underground(Level level, BlockPos target) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        return surface - target.getY() >= UNDERGROUND_DEPTH;
    }
}
