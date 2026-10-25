package com.neoalive.tacz_sewv.client.map;

/**
 * How much of a marker to draw. The World Map has room for every badge; the minimap is a few dozen
 * pixels across, where text labels turn into noise, so it draws the symbol, heading, selection and
 * (optionally) the vitals bars at a configured scale.
 *
 * @param scale  multiplier on the painter's own pixel sizes (the World Map is 1)
 * @param labels command-debug glyphs, play-role tags, group ids, hover names
 * @param vitals the PMC health / energy bars
 */
public record MarkerStyle(float scale, boolean labels, boolean vitals) {

    public static final MarkerStyle WORLD_MAP = new MarkerStyle(1.0F, true, true);
}
