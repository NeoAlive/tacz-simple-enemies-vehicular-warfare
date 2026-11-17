package com.neoalive.tacz_sewv.wing.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Fixed-wing rigid-body physics, one sub-step at a time: the counterpart of {@code HeliPhysics},
 * with the same frame, integrator (semi-implicit Euler, quaternion attitude) and sub-step.
 *
 * <p>Forces are computed in the body frame from the air-relative velocity: drag along the relative
 * wind, lift perpendicular to it in the plane of the body's up axis (so it stays meaningful at
 * any alpha, including inverted and post-stall), side force along the starboard axis, thrust along
 * the nose through the CG. Moments use standard stability-axis coefficients, mapped onto the
 * body frame at the end ({@code x = -M}, {@code y = -N}, {@code z = +L}). Control power scales
 * with dynamic pressure by construction: a slow aircraft runs out of authority on its own.
 *
 * <p>There is no ground model: an aircraft on the ground belongs to SBW (the runtime hands it
 * back on contact), so the physics never has to know about wheels.
 */
public final class WingPhysics {

    /** Sub-step: six per 20 Hz game tick, same as the helicopter. */
    public static final double H = 1.0 / 120.0;
    /** Floor on the airspeed used to normalise body rates (avoids the 1/V at rest). */
    private static final double V_NORM_MIN = 3.0;
    /** SBW power at full sprint throttle; its fuel law is {@code cost/tick = EnergyCostRate x |power|}. */
    public static final double SBW_FULL_POWER = 3.0;

    private final WingAirframe af;
    private final double fuelPerSecondFull;
    private final double aSpool, aGear, aFlap, aBrake;

    private final Quaterniond qInv = new Quaterniond();
    private final Quaterniond dq = new Quaterniond();
    private final Vector3d va = new Vector3d();
    private final Vector3d force = new Vector3d();
    private final Vector3d torque = new Vector3d();
    private final Vector3d tmp = new Vector3d();

    /**
     * @param energyCostRate the hull's SBW {@code EnergyCostRate} (energy per tick per unit power);
     *     full throttle burns what SBW's own engine burns at full sprint power
     */
    public WingPhysics(WingAirframe af, double energyCostRate) {
        this.af = af;
        this.fuelPerSecondFull = 20.0 * energyCostRate * SBW_FULL_POWER;
        this.aSpool = lag(af.spool);
        this.aGear = rate(af.gearTime);
        this.aFlap = rate(af.flapTime);
        this.aBrake = rate(af.brakeTime);
    }

    public WingAirframe airframe() {
        return af;
    }

    /** First-order lag coefficient per sub-step for time constant {@code tau}. */
    private static double lag(double tau) {
        return tau <= 0.0 ? 1.0 : 1.0 - StrictMath.exp(-H / tau);
    }

    /** Full-travel time to a per-sub-step slew. */
    private static double rate(double fullTravel) {
        return fullTravel <= 0.0 ? 1.0 : H / fullTravel;
    }

    private static double slew(double x, double target, double step) {
        return x < target ? Math.min(target, x + step) : Math.max(target, x - step);
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    /** Advance {@code s} by one sub-step. */
    public void step(WingState s, WingControl u, WingEnv env) {
        // --- Engine and actuators -------------------------------------------------------------
        if (s.degraded) s.engine = WingState.Engine.FAILED;
        if (s.engine == WingState.Engine.RUN && (!u.engineOn || s.fuel <= 0.0)) s.engine = WingState.Engine.OFF;
        if (s.engine == WingState.Engine.OFF && u.engineOn && s.fuel > 0.0) s.engine = WingState.Engine.RUN;
        boolean running = s.engine == WingState.Engine.RUN;
        s.throttle += aSpool * ((running ? clamp(u.throttle, 0.0, 1.0) : 0.0) - s.throttle);
        s.gear = slew(s.gear, u.gearDown ? 1.0 : 0.0, aGear);
        s.flap = slew(s.flap, clamp(u.flap, 0.0, 1.0), aFlap);
        s.brake = slew(s.brake, clamp(u.brake, 0.0, 1.0), aBrake);

        // --- Air data -------------------------------------------------------------------------
        qInv.set(s.q).conjugate();
        qInv.transform(tmp.set(s.v).sub(env.wind()), va);
        double vAir = va.length();
        // Stability axes: u forward (+Z), v right (-X), w down (-Y).
        double uS = va.z, vS = -va.x, wS = -va.y;
        double alpha = StrictMath.atan2(wS, uS);
        double beta = vAir > 1.0E-6 ? StrictMath.asin(clamp(vS / vAir, -1.0, 1.0)) : 0.0;
        double p = s.w.z, q = -s.w.x, r = -s.w.y;
        double qbar = 0.5 * env.rho() * vAir * vAir;
        double qs = qbar * af.area;
        double vn = Math.max(vAir, V_NORM_MIN);
        double pHat = p * af.span / (2.0 * vn), qHat = q * af.chord / (2.0 * vn), rHat = r * af.span / (2.0 * vn);

        double auth = s.degraded ? af.degradedAuthority : 1.0;
        double de = clamp(u.elevator, -1.0, 1.0) * auth;
        double da = clamp(u.aileron, -1.0, 1.0) * auth;
        double dr = clamp(u.rudder, -1.0, 1.0) * auth;

        double cl = AeroModel.cl(af, alpha, s.flap);
        double cd = AeroModel.cd(af, alpha, s.flap, s.gear, s.brake, !running);
        double cy = af.cyBeta * beta;
        double thrust = Propulsion.thrust(af, s.throttle, vAir);

        // --- Forces, body frame ---------------------------------------------------------------
        force.set(0.0, 0.0, thrust);
        if (vAir > 1.0E-6) {
            double ix = va.x / vAir, iy = va.y / vAir, iz = va.z / vAir;
            force.add(-qs * cd * ix, -qs * cd * iy, -qs * cd * iz);
            // Lift: the body up axis with its relative-wind component removed.
            double lx = -iy * ix, ly = 1.0 - iy * iy, lz = -iy * iz;
            double ln = StrictMath.sqrt(lx * lx + ly * ly + lz * lz);
            if (ln > 1.0E-6) {
                double k = qs * cl / ln;
                force.add(k * lx, k * ly, k * lz);
            }
            force.x -= qs * cy; // side force acts along starboard (-X)
        }
        double nz = force.y / (af.mass * env.g());
        s.q.transform(force);
        force.y -= af.mass * env.g();

        // --- Moments: stability axes, then onto the body frame ------------------------------------
        double alphaM = clamp(alpha, -0.5 * Math.PI, 0.5 * Math.PI);
        double cRoll = af.clBeta * beta + af.clp * pHat + af.clDa * da;
        double cPitch = af.cm0 + af.cmAlpha * alphaM + af.cmq * qHat + af.cmDe * de;
        double cYaw = af.cnBeta * beta + af.cnr * rHat + af.cnDr * dr;
        double mRoll = qs * af.span * cRoll, mPitch = qs * af.chord * cPitch, mYaw = qs * af.span * cYaw;
        torque.set(-mPitch, -mYaw, mRoll);
        torque.x -= af.restDamping * af.ix * s.w.x;
        torque.y -= af.restDamping * af.iy * s.w.y;
        torque.z -= af.restDamping * af.iz * s.w.z;

        // Euler: I w' = tau - w x (I w)
        double ixx = af.ix, iyy = af.iy, izz = af.iz;
        double wx = s.w.x, wy = s.w.y, wz = s.w.z;
        double gx = wy * (izz * wz) - wz * (iyy * wy);
        double gy = wz * (ixx * wx) - wx * (izz * wz);
        double gz = wx * (iyy * wy) - wy * (ixx * wx);

        // --- Integrate (semi-implicit Euler) --------------------------------------------------
        s.v.fma(H / af.mass, force);
        s.w.add(H * (torque.x - gx) / ixx, H * (torque.y - gy) / iyy, H * (torque.z - gz) / izz);
        s.p.fma(H, s.v);
        dq.set(s.w.x, s.w.y, s.w.z, 0.0);
        s.q.mul(dq, dq); // dq = q (x) (0, w)
        s.q.set(s.q.x + 0.5 * H * dq.x, s.q.y + 0.5 * H * dq.y, s.q.z + 0.5 * H * dq.z, s.q.w + 0.5 * H * dq.w)
                .normalize();
        if (s.q.w < 0.0) s.q.set(-s.q.x, -s.q.y, -s.q.z, -s.q.w);
        if (running) s.fuel = Math.max(0.0, s.fuel - H * fuelPerSecondFull * s.throttle);

        s.alpha = alpha;
        s.beta = beta;
        s.airspeed = vAir;
        s.thrust = thrust;
        s.cl = cl;
        s.cd = cd;
        s.nz = nz;
    }
}
