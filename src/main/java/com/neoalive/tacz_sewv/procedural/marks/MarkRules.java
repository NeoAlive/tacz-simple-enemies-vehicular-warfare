package com.neoalive.tacz_sewv.procedural.marks;

/**
 * The pure half of the event mark engine, pinned by {@code selfCheckEventMarks}: where a mark is
 * elected, whether a footprint suits the event, and how a creeping mark advances.
 */
public final class MarkRules {

    /** Below this many simulated chunks there is no room past the edge; the legacy roller runs. */
    public static final int MIN_SIMULATED_CHUNKS = 8;
    /** Marks are elected this many simulated radii past the player (one to two past the edge). */
    public static final double NEAR_RADII = 2.0;
    public static final double FAR_RADII = 3.0;
    /** A mark fires only while the trigger player is at least this share of the radius away. */
    public static final double MIN_FIRE_SHARE = 0.6;
    /** Blocks a creeping mark advances per engine step (one second). */
    public static final double CREEP_STEP = 2.0;
    /** A creeping mark stops this far inside the radius; the trigger takes it from there. */
    public static final double CREEP_STOP_SHARE = 0.8;
    /** Height samples per footprint side. */
    public static final int GRID = 5;

    private MarkRules() {}

    /** What ground an event needs. */
    public enum Profile {
        /** Hulls drive here: dry everywhere, no slope steeper than about 1 in 2.2 between samples. */
        VEHICLE(0.45, 1.0),
        /** Riflemen on foot: mostly dry, rough ground allowed. */
        INFANTRY(1.2, 0.8),
        /** Ships: water deep enough to float a hull over most of it. */
        WATER(-1, 0.6),
        /** Aircraft: anything. */
        ANY(-1, 0.0);

        /** Steepest rise over run allowed between neighbouring samples, or -1 for no limit. */
        public final double maxGrade;
        /** Share of samples that must have the right surface (dry, or wet for WATER). */
        public final double share;

        Profile(double maxGrade, double share) {
            this.maxGrade = maxGrade;
            this.share = share;
        }
    }

    /** Distance band, in blocks, a mark is elected in for a simulated radius. */
    public static double[] electionBand(int radius) {
        return new double[] {radius * NEAR_RADII, radius * FAR_RADII};
    }

    /** Water this deep over the floor counts as navigable for {@link Profile#WATER}. */
    public static final int MIN_DEPTH = 3;

    /** Score for a footprint that does not suit the profile. */
    public static final double REJECT = Double.POSITIVE_INFINITY;

    /**
     * How well a footprint sampled from the generator suits the profile: the steepest grade between
     * neighbouring samples, so lower is flatter, or {@link #REJECT}. Measured against real chunks the
     * noise floor matched the leafless surface exactly at 82% of points; the rest were logs and
     * structures, which the trigger time check catches.
     *
     * @param surface top of the world at each sample, water included, row major {@code grid x grid}
     * @param floor top solid block at each sample, water excluded
     * @param spacing blocks between neighbouring samples
     */
    public static double score(Profile profile, int[] surface, int[] floor, int grid, double spacing) {
        if (profile == Profile.ANY) return 0.0;
        int n = grid * grid;
        if (n == 0 || surface.length != n || floor.length != n || spacing <= 0) return REJECT;
        int good = 0;
        for (int i = 0; i < n; i++) {
            boolean wet = surface[i] - floor[i] >= (profile == Profile.WATER ? MIN_DEPTH : 1);
            // No water over the floor. Not a sea level test: superflat reports sea level 63 over ground at -60.
            boolean dry = surface[i] == floor[i];
            if (profile == Profile.WATER ? wet : dry) good++;
        }
        if (good < Math.ceil(n * profile.share)) return REJECT;
        int step = 0;
        for (int r = 0; r < grid; r++) {
            for (int c = 0; c < grid; c++) {
                int h = surface[r * grid + c];
                if (c + 1 < grid) step = Math.max(step, Math.abs(h - surface[r * grid + c + 1]));
                if (r + 1 < grid) step = Math.max(step, Math.abs(h - surface[(r + 1) * grid + c]));
            }
        }
        double grade = step / spacing;
        if (profile.maxGrade >= 0 && grade > profile.maxGrade) return REJECT;
        return grade;
    }

    /**
     * Next position of a creeping mark: {@link #CREEP_STEP} toward the player, never closer than
     * {@link #CREEP_STOP_SHARE} of the radius.
     */
    public static double[] creep(double markX, double markZ, double playerX, double playerZ, int radius) {
        double dx = playerX - markX;
        double dz = playerZ - markZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        double stop = radius * CREEP_STOP_SHARE;
        if (d <= stop || d < 1.0e-6) return new double[] {markX, markZ};
        double step = Math.min(CREEP_STEP, d - stop);
        return new double[] {markX + dx / d * step, markZ + dz / d * step};
    }
}
