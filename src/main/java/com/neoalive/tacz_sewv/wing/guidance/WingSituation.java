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
}
