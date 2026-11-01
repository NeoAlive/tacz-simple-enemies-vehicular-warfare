package com.neoalive.tacz_sewv.wing.physics;

/**
 * Pilot inputs for one sub-step. The stick, throttle and applied {@code brake} are written only by
 * the controller; the configuration inputs (engine, gear, flaps, {@code brakeCmd}) belong to guidance.
 *
 * <p>Signs are the pilot's, not the aerodynamicist's: positive elevator is a pull (nose up),
 * positive aileron rolls right (starboard wing down), positive rudder yaws the nose right.
 */
public final class WingControl {

    /** Commanded throttle, [0, 1]. */
    public double throttle;
    /** Elevator [-1, 1], positive pitches the nose up. */
    public double elevator;
    /** Aileron [-1, 1], positive rolls right. */
    public double aileron;
    /** Rudder [-1, 1], positive yaws the nose right. */
    public double rudder;
    /** Commanded flap deflection, [0, 1]. */
    public double flap;
    /** Airbrake actually applied, [0, 1]: {@code max(brakeCmd, the controller's overspeed brake)}. */
    public double brake;
    /** Airbrake guidance asks for, [0, 1]. The controller never writes it. */
    public double brakeCmd;
    public boolean gearDown;
    public boolean engineOn = true;

    public void neutral() {
        elevator = aileron = rudder = 0.0;
    }
}
