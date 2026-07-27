package com.neoalive.tacz_sewv.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Debug-only allocentric battle picture for the map overlay. Built read-only from a populated
 * {@code BattleField} — centroids, axis, and flank marker positions are already decided before
 * this record exists. The client draws these fields; it does not recompute them.
 *
 * <p>Flank world positions are packaged beside the enemy centroid along the BattleField axis
 * perpendicular (left = rotate 90° CCW of enemy→us). {@link #flankLeft}/{@link #flankRight} are
 * false when that side is not open — the coordinates are then unused.
 */
public record BattleFieldMarker(
        int groupId,
        ResourceKey<Level> dimension,
        double y,
        double friendlyX, double friendlyZ,
        double enemyX, double enemyZ,
        double axisX, double axisZ,
        boolean flankLeft, double flankLeftX, double flankLeftZ,
        boolean flankRight, double flankRightX, double flankRightZ
) {

    /** World-block offset of an open-flank mark from the enemy centroid along the axis perpendicular. */
    public static final double FLANK_MARK_OFFSET = 28.0;

    /**
     * Axis-relative left unit in XZ: rotate enemy→us 90° CCW. Same convention as
     * {@code InfluenceMap.scoreFlanks}.
     */
    public static double leftX(double axisX, double axisZ) {
        return -axisZ;
    }

    public static double leftZ(double axisX, double axisZ) {
        return axisX;
    }

    /**
     * World position of a flank mark. {@code side} is {@code +1} for left, {@code -1} for right.
     * Pure — shared by the map packet packager and the self-check so the ⟂ contract cannot drift.
     */
    public static double flankMarkX(double enemyX, double axisX, double axisZ, int side) {
        return enemyX + side * leftX(axisX, axisZ) * FLANK_MARK_OFFSET;
    }

    public static double flankMarkZ(double enemyZ, double axisX, double axisZ, int side) {
        return enemyZ + side * leftZ(axisX, axisZ) * FLANK_MARK_OFFSET;
    }
}
