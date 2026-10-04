package com.neoalive.tacz_sewv.heli.guidance;

/**
 * Snapshot the pilot goal hands guidance every tick. Built fresh each tick and never modified
 * after hand-over.
 *
 * <p>Two halves, kept apart on purpose:
 * <ul>
 * <li><b>Summary</b> ({@code order} .. {@code targetId}): the only fields {@link ModeSelector}
 *     reads.</li>
 * <li><b>Geometry</b> (the rest): the points and altitudes procedures fly to. The goal does every
 *     world read (terrain, orders, pads, the target) so procedures stay pure.</li>
 * </ul>
 * All positions are world-frame metres (blocks), velocities m/s, altitudes absolute Y, angles radians.
 */
public final class Situation {

    public enum TargetCategory { NONE, INFANTRY, VEHICLE, AIR }

    public enum TargetMotion { STATIC, SLOW, FAST }

    /** Sim time (seconds) this snapshot was taken at. References are valid for 1 s after it. */
    public double time;

    // --- Summary -------------------------------------------------------------------------------
    public OrderKind order = OrderKind.NONE;
    /** A player order owns the flight path (move, follow, formation, hold, cease-fire). */
    public boolean underOrders;
    /** The procedure running now, so the selector can apply hysteresis. */
    public ProcedureId active = ProcedureId.HOVER_HOLD;
    public boolean targetValid;
    public TargetCategory targetCategory = TargetCategory.NONE;
    public double targetDistance;
    public boolean targetLos;
    public TargetMotion targetMotion = TargetMotion.STATIC;
    public boolean hasDestination;
    public double destDistance;
    /** Hull health as a fraction of max, and the airframe's evade threshold (0 disables). */
    public double healthFrac = 1.0, evadeHealth;
    /** Held weapon's remaining ammunition as a fraction; 0 means depleted. */
    public double ammoFrac = 1.0;
    public boolean armed, weaponGuided;
    /** A firing run is in progress (sticky until the run's exit completes). */
    public boolean inFiringRun;
    /** RU/US: no player orders, patrols when idle. */
    public boolean autonomous;
    /** Attack procedures completed against this target; resets on target change. */
    public int engageCycle;
    /** Envelope results (plan 4.10): FireStill's wind ceiling and yaw standoff are within reach. */
    public boolean windCeilingOk = true, yawStandoffOk = true;
    /** Airborne with the engine stopped or failed: the only thing left to do is autorotate. */
    public boolean engineOut;
    /** Target entity id (pick8e key, retarget detection); -1 for none. */
    public int targetId = -1;

    // --- Geometry ------------------------------------------------------------------------------
    public double targetX, targetY, targetZ;
    /** Target velocity, world frame (the hull it rides when mounted). */
    public double targetVx, targetVy, targetVz;
    /** Entity id of the hull the target rides, -1 if on foot: excluded from the tactical bias. */
    public int targetHullId = -1;
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
    /** Terrain-relative cruise altitude y_c (plan 4.10), absolute Y. */
    public double cruiseY;
    /** groundRef: highest WORLD_SURFACE ground around the target / along the leg (trees count). */
    public double groundRef;
    /** Surface directly under the hull (pull-up floor). */
    public double groundBelow;
    /** Patrol anchor (NaN = position at begin) and the hull's persistent seed. */
    public double anchorX = Double.NaN, anchorZ = Double.NaN;
    public long seed;
    /**
     * Weapon envelope: maximum range, the run's fire-window open range, the standoff floor, the fire
     * cone. Defaults are the shipped config defaults (heliEngageRadius, heliMinStandoff,
     * aiFireAssistConeDeg); the goal overwrites them every tick.
     */
    public double weaponRange = 100.0, engageRadius = 32.0, minStandoff = 28.0, fireCone = Math.toRadians(35.0);
    /** Hull velocity, for the envelope's relative line-of-sight rate. */
    public double hullVx, hullVz;
}
