package com.neoalive.tacz_sewv.heli.physics;

/**
 * Pilot inputs for one sub-step. Written only by {@code HeliController} (engine command aside,
 * which guidance owns).
 */
public final class HeliControl {

    public enum EngineCmd { HOLD, START, STOP }

    /** Commanded collective, normalised [0, 1]. */
    public double collective;
    /** Longitudinal cyclic [-1, 1], positive tilts the disk forward. */
    public double cLon;
    /** Lateral cyclic [-1, 1], positive tilts the disk to starboard. */
    public double cLat;
    /** Yaw channel [-1, 1], positive yaws the nose left. */
    public double pedal;
    public EngineCmd engine = EngineCmd.HOLD;
}
