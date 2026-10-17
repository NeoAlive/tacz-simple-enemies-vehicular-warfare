package com.neoalive.tacz_sewv.heli.avoid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Obstacles near one hull for one tick: boxes (blocks, airframes) with a stable key and a world
 * velocity, plus the ground plane. Built at 20 Hz by the world probe. The list is sorted by key on
 * construction, so the order the probe found things in can never change a force (determinism).
 *
 * @param groundY ground height under the hull, or NaN when there is no ground obstacle
 */
public record ObstacleSet(List<Box> boxes, double groundY) {

    /** Key reserved for the ground plane's latch. */
    public static final long GROUND = Long.MIN_VALUE;

    /** Axis-aligned obstacle. {@code key}: {@code BlockPos.asLong()} for blocks, entity id for airframes. */
    public record Box(long key, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                      double vx, double vy, double vz) {}

    public ObstacleSet {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingLong(Box::key));
        boxes = List.copyOf(sorted);
    }

    public static ObstacleSet empty() {
        return new ObstacleSet(List.of(), Double.NaN);
    }
}
