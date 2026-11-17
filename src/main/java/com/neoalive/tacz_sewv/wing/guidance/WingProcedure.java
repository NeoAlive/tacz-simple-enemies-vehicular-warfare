package com.neoalive.tacz_sewv.wing.guidance;

import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * A fixed-wing procedure emits a reference for the controller. Pure: it reads the hull state and
 * the {@link WingSituation} snapshot, never the world. Same contracts as {@code HeliProcedure}:
 * {@link #refAt} is defined for every {@code t >=} the begin time, {@link #isComplete} is first
 * asked on the tick after {@link #begin}, and procedures never see the terrain guard's override.
 *
 * <p>Free-navigation references are deliberately <b>unscheduled</b>: the reference point is the
 * aircraft's own projection onto the path, so there is no along-track error and the controller
 * holds the commanded speed instead of chasing a moving carrot.
 */
public interface WingProcedure {

    WingProcedureId id();

    default boolean canBegin(WingState s, WingSituation sit) {
        return true;
    }

    void begin(WingState s, WingSituation sit, double t);

    WingReference refAt(double t, WingState s, WingSituation sit);

    boolean isComplete(WingState s, WingSituation sit, double t);

    /**
     * True for a procedure that runs to completion once begun unless something of a higher class
     * pre-empts it (an attack run, the break that follows it). The others follow the selector at once.
     */
    default boolean sticky() {
        return false;
    }

    /** True when the geometry allows firing. Non-attack procedures do not restrict it. */
    default boolean fireWindow(WingState s, WingSituation sit) {
        return true;
    }

    /** Configuration this procedure wants: gear, flaps [0, 1], airbrake [0, 1]. */
    default boolean gearDown() {
        return false;
    }

    default double flap() {
        return 0.0;
    }

    default double brake() {
        return 0.0;
    }
}
