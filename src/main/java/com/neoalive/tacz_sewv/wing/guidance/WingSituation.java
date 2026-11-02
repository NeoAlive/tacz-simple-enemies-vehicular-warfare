package com.neoalive.tacz_sewv.wing.guidance;

/**
 * The world as guidance sees it for one tick: a plain snapshot the plane drive goal fills at 20 Hz
 * from the world (destinations, the leash, the cruise height the terrain allows, ground heights
 * ahead). Procedures and the selector read only this and the hull state, never the world, which
 * is what lets the headless checks fly them. The counterpart of the helicopter {@code Situation}.
 */
public final class WingSituation {

    /** Mirrors {@code PlaneLeash.State}, without the dependency. */
    public enum Leash { FREE, RECALL, RETURN }

    /** Sim time the snapshot was taken (game time / 20). */
    public double time;
    /** The procedure running when the snapshot was taken (for the selector's hysteresis). */
    public WingProcedureId active;
    /** A player order owns the flight path (PMC movement / hold orders). */
    public boolean underOrders;
    /** RU/US: nobody to answer to, so an aircraft with no destination patrols instead of holding. */
    public boolean autonomous;
    public int pilotId;
    /** Orbit direction (entity-id parity, so two aircraft on one anchor turn opposite ways). */
    public boolean clockwise;

    public boolean hasDestination;
    public double destX, destY, destZ, destDistance;
    public boolean hasAnchor;
    public double anchorX, anchorY, anchorZ, anchorDistance;
    public Leash leash = Leash.FREE;
    /** Orbit centre when there is no destination: the anchor, else where the hold was entered. */
    public double holdX, holdZ;

    /** Altitude to fly at (terrain-relative, already capped by the ceiling); NaN = hold current. */
    public double cruiseY = Double.NaN;
    public double ceilingY = Double.POSITIVE_INFINITY;
    /**
     * Ground heights ahead along the current track: sample {@code i} is {@code (i + 1) * floorStep}
     * metres out. Unloaded columns are {@code -infinity} (no sample).
     */
    public double[] floor = new double[0];
    public double floorStep = 16.0;
    /** Sample count the drive goal takes (and the self-checks mirror): 24 at 0.75 s of flight. */
    public static final int FLOOR_SAMPLES = 24;
    public static final double FLOOR_SECONDS_PER_SAMPLE = 0.75;
    /** Orbit radius the airframe flies comfortably at cruise (set by the runtime from its airframe). */
    public double orbitRadius = 80.0;

    public double healthFrac = 1.0;
    /** Ground height under the hull (unloaded: -infinity). */
    public double groundBelow = Double.NEGATIVE_INFINITY;

    // --- Combat (filled only while there is a target or a radio grid mark) ------------------------

    /** A live target, or a radio grid mark ({@link #isMark}), to work. */
    public boolean targetValid;
    public boolean isMark;
    public double targetX, targetY, targetZ, targetVx, targetVy, targetVz, targetDistance;
    /** The selected store is dropped from level flight (a bomb) rather than aimed by the nose. */
    public boolean levelDelivery;
    /** The bomb stick for this run has all gone. */
    public boolean stickComplete;
    /** Nose already within the run alignment of the bearing to the target. */
    public boolean established;
    /** A run from here clears the terrain all the way in (the goal's dive-safety probe). */
    public boolean runSafe = true;
    /** How far out the run begins (engage bubble, or the bomb release range plus margin). */
    public double engageRange = 192.0;
    /** Altitude the run is flown at (briefed by the goal from the delivery geometry). */
    public double runY = Double.NaN;
    /** Longest run before breaking off, m. */
    public double runLength = 200.0;
    /** Deepest dive the selected store is delivered at, rad. */
    public double maxDive = StrictMath.toRadians(55.0);
    /**
     * World direction the hull NOSE must point so the weapon's own line passes through the aim
     * point (lead included): the goal measures the gun line against the nose and folds the offset
     * in, so the pure controller can track it without knowing anything about the gun.
     */
    public boolean hasAimDir;
    public double aimDirX, aimDirY, aimDirZ;
    /** Ground point the bomb track has to pass over (led): what a level run steers along. */
    public double bombAimX, bombAimZ;
    /** Run-exit thresholds from the goal's tuning: clearance floor, sink lead (s), overfly margin, break height. */
    public double minAttackClearance = 24.0, pullupLead = 0.7, overflyMargin = 16.0, entryAgl = 80.0;
}
