package com.neoalive.tacz_sewv.heli.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Mutable flight state of one hull. SI units, world frame W for {@code p}/{@code v}, body frame B
 * (+Z nose, +Y mast, +X port) for {@code w}. {@code q} rotates body to world.
 *
 * <p>{@code p} is the entity position (bottom centre of the bounding box), not the centre of
 * gravity. Minecraft's box never rotates and SBW renders the model about a fixed height above it,
 * so the two differ by a constant world offset ({@link Airframe#cgHeight}). Translation dynamics
 * are identical for both points.
 */
public final class HeliState {

    public enum Engine { OFF, START, RUN, FAILED }

    public final Vector3d p = new Vector3d();
    public final Vector3d v = new Vector3d();
    public final Quaterniond q = new Quaterniond();
    /** Body rates: x nose-down pitch, y nose-left yaw, z starboard-down roll (rad/s). */
    public final Vector3d w = new Vector3d();
    /** Rotor speed, rad/s. */
    public double omega;
    /** Actual (lagged) collective, normalised [0, 1]. */
    public double theta0;
    /** Actual (lagged) yaw-channel command, [-1, 1]. */
    public double pedal;
    /** Fuel in SBW energy units. */
    public double fuel = Double.POSITIVE_INFINITY;
    public Engine engine = Engine.OFF;
    /** Tail rotor lost (hull below 10 % health): no yaw authority, no anti-torque. Never clears. */
    public boolean tailFailed;
    /** Governor target speed (ramps during START). */
    public double omegaTarget;
    /** Governor error on the previous sub-step, NaN when the governor was not running. */
    public double govErrPrev = Double.NaN;

    // Observables of the last sub-step (not integrated).
    public double thrust;
    public double engineTorque;
    public double tailThrust;

    public HeliState copy() {
        HeliState c = new HeliState();
        c.p.set(p);
        c.v.set(v);
        c.q.set(q);
        c.w.set(w);
        c.omega = omega;
        c.theta0 = theta0;
        c.pedal = pedal;
        c.fuel = fuel;
        c.engine = engine;
        c.tailFailed = tailFailed;
        c.omegaTarget = omegaTarget;
        c.govErrPrev = govErrPrev;
        c.thrust = thrust;
        c.engineTorque = engineTorque;
        c.tailThrust = tailThrust;
        return c;
    }
}
