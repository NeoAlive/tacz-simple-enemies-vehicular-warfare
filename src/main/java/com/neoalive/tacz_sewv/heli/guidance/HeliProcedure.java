package com.neoalive.tacz_sewv.heli.guidance;

import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliState;

/**
 * A procedure emits a time-parameterised reference (plan section 4.8). Pure: it reads the hull
 * state and the {@link Situation} snapshot, never the world.
 *
 * <p>Contracts:
 * <ul>
 * <li>{@link #refAt} is defined for every {@code t >= } the begin time, including past
 *     completion (it then continues its last segment at constant velocity). The stack keeps
 *     sampling an outgoing procedure through the blend window.</li>
 * <li>{@link #isComplete} is first evaluated on the tick AFTER {@link #begin}: every procedure
 *     lives at least one tick. A procedure whose completion test already holds at begin must
 *     answer {@code canBegin == false} instead.</li>
 * <li>Procedures never see the avoidance bias or force: those are applied after them.</li>
 * </ul>
 */
public interface HeliProcedure {

    ProcedureId id();

    default boolean canBegin(HeliState s, Situation sit) {
        return true;
    }

    void begin(HeliState s, Situation sit, double t);

    HeliReference refAt(double t, HeliState s, Situation sit);

    boolean isComplete(HeliState s, Situation sit, double t);

    /** Engine command this procedure needs (takeoff starts it, park stops it). */
    default HeliControl.EngineCmd engine() {
        return HeliControl.EngineCmd.HOLD;
    }

    /**
     * False while the procedure is putting the hull on the ground (final descent, parked, and a
     * takeoff that has not yet left it): the barrier then ignores the ground plane, or it would
     * hold the hull off the surface it is landing on.
     */
    default boolean groundBarrier(double t) {
        return true;
    }

    /** False when the controller must let go (parked: flat pitch, sticks centred). */
    default boolean flying() {
        return true;
    }

    /** Attack phase for the overlay and the scan goals; NONE outside FireRun. */
    default FirePhase firePhase() {
        return FirePhase.NONE;
    }

    /**
     * True when the geometry allows firing (plan 4.10: the pitch-free cone, the run's window).
     * Non-attack procedures do not restrict the fire assist.
     */
    default boolean fireWindow(HeliState s, Situation sit) {
        return true;
    }

    /**
     * The target changed mid-procedure (a hand-off). True when the procedure simply follows the new
     * target; false ends it (INVALIDATED) and the same id is begun afresh from the current state.
     */
    default boolean retarget(Situation sit, double t) {
        return false;
    }

    /** A point ahead on the procedure's own route, for terrain-relative altitude; null = none. */
    default double[] lookahead() {
        return null;
    }
}
