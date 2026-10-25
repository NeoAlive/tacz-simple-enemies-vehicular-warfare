package com.neoalive.tacz_sewv.client.map;

import net.minecraft.core.BlockPos;

/**
 * World XZ to the screen space of whichever map is drawing: the World Map's fullscreen camera or the
 * minimap's rotated framebuffer. The overlay painters take one of these instead of reading a camera,
 * which is the whole reason the same code can draw on both maps.
 *
 * <p>Culling is the projection's job too, not the painter's: the World Map's screen runs from 0 to
 * its width, while the minimap's framebuffer is centred on the player at (0, 0), so a painter that
 * tested against {@code 0..width} itself would cull half the minimap.
 */
public interface MapProjection {

    /** World XZ (continuous) to screen pixels. */
    int[] toScreen(double worldX, double worldZ);

    /** A block's centre to screen pixels. */
    default int[] toScreen(BlockPos pos) {
        return toScreen(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    /** A world distance (blocks) as screen pixels at the current zoom. */
    double lengthPx(double blocks);

    /** Whether a screen rectangle overlaps the visible map at all. */
    boolean intersects(int x0, int y0, int x1, int y1);

    /** Whether a projected point is worth drawing from — a little slack past the visible edge. */
    default boolean onScreen(int[] point) {
        return intersects(point[0] - 16, point[1] - 16, point[0] + 16, point[1] + 16);
    }
}
