package com.neoalive.tacz_sewv.wing.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Mutable flight state of one fixed-wing hull. SI units, world frame for {@code p}/{@code v}, body
 * frame (+Z nose, +Y up, +X port, the same frame as {@code HeliState} and {@code McPose}) for
 * {@code w}. {@code q} rotates body to world. Body rates: x nose-down pitch, y nose-left yaw,
 * z starboard-down roll (rad/s).
 *
 * <p>{@code p} is the entity position (bottom centre of the bounding box). The aerodynamic forces
 * act at the CG and thrust passes through it, so translation is the same for both points and no
 * offset is carried.
 */
public final class WingState {

    public enum Engine { OFF, RUN, FAILED }

    public final Vector3d p = new Vector3d();
    public final Vector3d v = new Vector3d();
    public final Quaterniond q = new Quaterniond();
    public final Vector3d w = new Vector3d();
    /** Actual (spooled) throttle, [0, 1]. */
    public double throttle;
    /** Actual actuator positions, [0, 1]; gear 1 = down and locked. */
    public double flap, brake, gear = 1.0;
    /** Fuel in SBW energy units. */
    public double fuel = Double.POSITIVE_INFINITY;
    public Engine engine = Engine.RUN;
    /** Hull below 10 % health: engine lost and control authority degraded. Never clears. */
    public boolean degraded;

    // Observables of the last sub-step (not integrated).
    public double alpha, beta, airspeed, thrust, cl, cd;
    /** Aerodynamic + thrust acceleration along body up, in g. */
    public double nz;

    public WingState copy() {
        WingState c = new WingState();
        c.p.set(p);
        c.v.set(v);
        c.q.set(q);
        c.w.set(w);
        c.throttle = throttle;
        c.flap = flap;
        c.brake = brake;
        c.gear = gear;
        c.fuel = fuel;
        c.engine = engine;
        c.degraded = degraded;
        c.alpha = alpha;
        c.beta = beta;
        c.airspeed = airspeed;
        c.thrust = thrust;
        c.cl = cl;
        c.cd = cd;
        c.nz = nz;
        return c;
    }
}
