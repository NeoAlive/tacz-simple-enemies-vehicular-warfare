package com.neoalive.tacz_sewv.client.map;

/**
 * The minimap's element transform, reproduced so lines and rings land where Xaero puts icons. Pure
 * arithmetic (no Minecraft or Xaero types), which is what lets {@code selfCheckMinimap} pin it.
 *
 * <p>Xaero places an element at block offset {@code (dx, dz)} from the player at
 * {@code X = (ps·dx − pc·dz)·zoom}, {@code Y = (pc·dx + ps·dz)·zoom}, where {@code ps}/{@code pc} are
 * the sine/cosine of {@code π − angle} and {@code angle} is 90° when north-locked. Reading the
 * rotation back out of {@code ps}/{@code pc} rather than out of Xaero's config means a north-lock
 * rule Xaero adds later (it already has two) can never make our headings disagree with its map.
 */
public final class MinimapMath {

    private MinimapMath() {}

    /**
     * Screen bearing of world north, in degrees clockwise from straight up — the amount to add to a
     * north-up heading. North is offset {@code (0, −1)}, which lands at {@code (pc, −ps)}; that vector
     * is {@code atan2(pc, ps)} clockwise from up. North-locked ({@code ps = 1, pc = 0}) gives 0.
     */
    public static float bearingOffsetDeg(double ps, double pc) {
        return (float) Math.toDegrees(Math.atan2(pc, ps));
    }

    /** Screen X of a block offset, matching Xaero's own placement. */
    public static double projectX(double ps, double pc, double zoom, double dx, double dz) {
        return (ps * dx - pc * dz) * zoom;
    }

    /** Screen Y of a block offset, matching Xaero's own placement. */
    public static double projectY(double ps, double pc, double zoom, double dx, double dz) {
        return (pc * dx + ps * dz) * zoom;
    }
}
