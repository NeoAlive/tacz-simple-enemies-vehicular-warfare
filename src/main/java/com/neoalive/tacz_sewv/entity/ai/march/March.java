package com.neoalive.tacz_sewv.entity.ai.march;

/**
 * One group moving as one: frozen membership, a route, and a virtual anchor that walks the route at a fixed
 * pace.
 *
 * <p><b>Nobody steers off anybody else.</b> Every member — the lead hull included — goes to its own slot,
 * laid off the anchor. The anchor is not a unit, so it cannot outrun the group, and it waits: the registry
 * pauses it whenever a member falls too far behind its slot, which is what bounds the column's stretch and
 * makes re-clustering unnecessary. Chasing a live leader was the old design, and the fastest hull simply drove
 * away from the rest.
 *
 * <p>The anchor is answered analytically ({@link #anchorAt}) from the last time the registry touched it, so
 * readers see smooth motion between command passes without anything being ticked.
 *
 * <p>Network ids name the members, so a march is transient by the bridge rule: a reload drops it and the group
 * forms a new one. The pacing half is pure and headless-testable.
 */
public final class March {

    public enum Kind {
        /** Out of contact: hulls in a column, squads trailing it. Contact ends it. */
        IDLE_TRAVEL,
        /** In a fight: hulls abreast, a file of infantry behind each. Advances through contact. */
        COHESIVE_LINE
    }

    final int id;
    final Kind kind;
    /** Driver unit ids; the index is the hull's slot. A member that leaves closes the gap behind it. */
    int[] hulls;
    /** Infantry unit ids; under IDLE_TRAVEL squad leaders (index = squad slot), under COHESIVE_LINE everyone. */
    int[] infantry;
    final MarchRoute route;
    /** The direction the march set off in, {@code atan2(dx, dz)}; the road swings about it, never away from it. */
    final double bearing;
    /** The road never grows past this arc length. */
    final double maxLength;
    /** Hull slot spacing along the route (column) or across it (line). */
    final double spacing;
    /** Anchor speed, blocks per game tick. */
    final double pace;
    final long started;

    private double progress;
    private long lastAdvance;
    private boolean paused;
    private long pausedSince = Long.MIN_VALUE;
    /** The route can no longer be extended; the march ends when the anchor reaches its end. */
    boolean routeClosed;

    March(int id, Kind kind, int[] hulls, int[] infantry, MarchRoute route, double bearing, double maxLength,
          double spacing, double pace, double progress, long now) {
        this.id = id;
        this.kind = kind;
        this.hulls = hulls;
        this.infantry = infantry;
        this.route = route;
        this.bearing = bearing;
        this.maxLength = maxLength;
        this.spacing = spacing;
        this.pace = pace;
        this.progress = progress;
        this.lastAdvance = now;
        this.started = now;
    }

    /** Anchor arc length at {@code now}: frozen while paused, never past the end of the route. */
    public double anchorAt(long now) {
        if (this.paused) return this.progress;
        double s = this.progress + this.pace * Math.max(0L, now - this.lastAdvance);
        return Math.min(s, this.route.length());
    }

    /** Pause or resume. Banks the distance walked so far, so a pause freezes the anchor where it stands. */
    void setPaused(boolean paused, long now) {
        this.progress = anchorAt(now);
        this.lastAdvance = now;
        if (paused && !this.paused) this.pausedSince = now;
        if (!paused) this.pausedSince = Long.MIN_VALUE;
        this.paused = paused;
    }

    public boolean paused() {
        return this.paused;
    }

    /**
     * Should a march wait? {@code worst} is the furthest-behind member's lag as a fraction of its straggle limit.
     * It waits once anyone is past the limit, and goes again only when everyone is inside {@code resume} of it —
     * the gap between the two is what keeps it from stuttering on the edge.
     */
    static boolean shouldPause(boolean paused, double worst, double resume) {
        if (worst > 1.0) return true;
        return paused && worst > resume;
    }

    /** Ticks the anchor has been waiting, 0 while moving. */
    long pausedFor(long now) {
        return this.paused ? now - this.pausedSince : 0L;
    }

    /** The anchor has reached a route that will not grow. */
    boolean arrived(long now) {
        return this.routeClosed && anchorAt(now) >= this.route.length() - 1.0e-6;
    }

    public int id() {
        return this.id;
    }

    public Kind kind() {
        return this.kind;
    }

    public int[] hullIds() {
        return this.hulls.clone();
    }

    public int[] infantryIds() {
        return this.infantry.clone();
    }

    /** Hull {@code k}'s slot at {@code now}, {@code {x, z, fx, fz}}. */
    public double[] hullSlot(int k, long now) {
        double s = anchorAt(now);
        return this.kind == Kind.IDLE_TRAVEL
                ? MarchGeometry.column(this.route, s, k, this.spacing)
                : MarchGeometry.line(this.route, s, k, this.hulls.length, this.spacing);
    }

    /** Infantry member {@code i}'s slot at {@code now}, {@code {x, z, fx, fz}}. */
    public double[] infantrySlot(int i, long now) {
        double s = anchorAt(now);
        if (this.kind == Kind.COHESIVE_LINE) {
            int n = this.hulls.length;
            return MarchGeometry.file(this.route, s, MarchGeometry.fileHull(i, n), n, this.spacing,
                    MarchGeometry.fileRank(i, n));
        }
        // Squads trail the column's tail: one hull spacing clear of the last hull, then one squad gap each.
        double behind = this.hulls.length * this.spacing + i * SQUAD_GAP;
        return MarchGeometry.trail(this.route, s, behind, MarchGeometry.alternate(i, SQUAD_LATERAL));
    }

    /** Distance between consecutive squads trailing a column. */
    static final double SQUAD_GAP = 10.0;
    /** How far to either side of the road a trailing squad walks. */
    static final double SQUAD_LATERAL = 4.0;
}
