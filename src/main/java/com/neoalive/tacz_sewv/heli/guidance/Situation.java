package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Snapshot the pilot goal hands guidance every tick. Built fresh each tick and never modified
 * after hand-over.
 *
 * <p>Two halves, kept apart on purpose:
 * <ul>
 * <li><b>Summary</b> ({@code order} .. {@code destDistance}): the only fields
 *     {@link ModeSelector} reads.</li>
 * <li><b>Geometry</b> (the rest): the points and altitudes procedures fly to. The goal does every
 *     world read (terrain, orders, pads) so procedures stay pure.</li>
 * </ul>
 * All positions are world-frame metres (blocks), altitudes absolute Y.
 */
public final class Situation {

    /** Sim time (seconds) this snapshot was taken at. References are valid for 1 s after it. */
    public double time;

    // --- Summary -------------------------------------------------------------------------------
    public OrderKind order = OrderKind.NONE;
    /** A player order owns the flight path (move, follow, formation, hold, cease-fire). */
    public boolean underOrders;
    /** The procedure running now, so the selector can apply hysteresis. */
    public ProcedureId active = ProcedureId.HOVER_HOLD;
    public boolean targetValid;
    public boolean hasDestination;
    public double destDistance;

    // --- Geometry ------------------------------------------------------------------------------
    public double targetX, targetY, targetZ;
    /** Destination; {@code destY} is the altitude to fly the leg at. */
    public double destX, destY, destZ;
    /** Velocity of a moving destination (a followed leader), world frame. */
    public double destVx, destVz;
    /** Where to hover when nothing else applies. */
    public double holdX, holdY, holdZ;
    /** Landing: pad centre, the Y the hull rests at, and the altitude of the run-in. */
    public double padX, padZ, touchdownY, transitY;
    /** Rappel: the locked station and its hover altitude. */
    public double lockX, lockZ, rappelY;
    /** Takeoff: the altitude the climb completes at. */
    public double climbTo;
    public int pilotId;
}
