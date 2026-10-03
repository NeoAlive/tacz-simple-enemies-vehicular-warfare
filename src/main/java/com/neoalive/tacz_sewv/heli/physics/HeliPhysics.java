package com.neoalive.tacz_sewv.heli.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 6DOF rigid body plus rotor, engine, tail rotor and fuselage drag, integrated with
 * semi-implicit Euler at a fixed sub-step {@link #H}. Plan section 4.1-4.5.
 *
 * <p>Force accumulation: body-frame forces (rotor, tail rotor, fuselage) are rotated to world
 * once, then gravity, the avoidance force and external forces are added in world. Moments are
 * body-frame throughout. Gyroscopic coupling {@code omega x I omega} is kept. The rotor's own
 * angular momentum is deliberately not: the flapping hub absorbs precession, and the
 * tip-path-plane damping term stands in for it. Do not add {@code -omega x (I_R Omega y_B)} back
 * without retuning the damping and rate-loop gains.
 *
 * <p>One instance per hull; it owns scratch vectors, so it is not thread-safe.
 */
public final class HeliPhysics {

    /** Sub-step: six per 20 Hz game tick. */
    public static final double H = 1.0 / 120.0;
    /** Skid friction on horizontal speed, 1/s: SBW's own ground damping of 0.85 per tick. */
    private static final double SKID_FRICTION = -StrictMath.log(0.85) * 20.0;
    /** Rotor brake after shutdown on the ground: stops a nominal-speed rotor in this many seconds. */
    private static final double ROTOR_BRAKE_SECONDS = 20.0;

    private final Airframe af;
    /** SBW energy units per joule of shaft-plus-idle work (the fuel-burn anchor). */
    private final double fuelPerJoule;
    private final double alphaCollective;
    private final double alphaYaw;

    private final Vector3d force = new Vector3d();
    private final Vector3d torque = new Vector3d();
    private final Vector3d tmp = new Vector3d();
    private final Quaterniond qInv = new Quaterniond();
    private final Quaterniond dq = new Quaterniond();

    public HeliPhysics(Airframe af, double fuelPerJoule) {
        this.af = af;
        this.fuelPerJoule = fuelPerJoule;
        this.alphaCollective = 1.0 - StrictMath.exp(-H / af.collectiveLag);
        this.alphaYaw = 1.0 - StrictMath.exp(-H / af.yawLag);
    }

    public Airframe airframe() {
        return af;
    }

    /** Advance {@code s} by one sub-step. {@code avoid} is a world-frame force at the CG (may be zero). */
    public void step(HeliState s, HeliControl u, HeliEnv env, Vector3dc avoid) {
        double rho = env.rho();
        Vector3d wind = new Vector3d(env.wind());
        RotorModel.Flow f = RotorModel.flow(af, s, env.g(), rho, wind, env.groundY(), u.cLon, u.cLat);
        double omega = s.omega;

        // --- Rotor thrust and torques ---------------------------------------------------------
        double thrust = RotorModel.thrust(af, rho, omega, s.theta0, f);
        double q0 = RotorModel.profileTorque(af, rho, omega, f.mu());
        double qi = 0.0, qin = 0.0;
        if (omega > 1.0E-6) {
            double vi = RotorModel.induced(rho, af.area, thrust, f.vPar());
            qi = thrust * (af.kappa * vi + Math.max(0.0, f.vc())) / omega;
            qin = thrust * Math.max(0.0, -f.vc()) / omega;
        }
        double qf = af.frictionCoeff * omega;
        double spinDrag = q0 + qi + qf;

        // --- Engine and governor --------------------------------------------------------------
        engineLogic(s, u.engine);
        boolean powered = s.engine == HeliState.Engine.START || s.engine == HeliState.Engine.RUN;
        double qe = 0.0;
        if (powered) {
            double e = s.omegaTarget - omega;
            double de = Double.isNaN(s.govErrPrev) ? 0.0 : (e - s.govErrPrev) / H;
            double kp = af.govWn * af.rotorInertia * (1.0 + af.govKd);
            double kd = af.govKd * af.rotorInertia;
            qe = clamp(spinDrag - qin + kp * e + kd * de, 0.0, af.maxTorque);
            s.govErrPrev = e;
        } else {
            s.govErrPrev = Double.NaN;
        }

        // --- Tail rotor (the only yaw-authority input) ----------------------------------------
        double rhoR = rho / Airframe.RHO0;
        double wr = omega / af.omegaN;
        qInv.set(s.q).conjugate();
        Vector3d va = qInv.transform(tmp.set(s.v).sub(wind), new Vector3d());
        double arm = af.coaxial ? 1.0 : af.tailArm;
        // Starboard air velocity at the tail: -(v_a + w x r_tr).x with r_tr = (0, h, -l).
        double wxrX = s.w.y * (-arm) - s.w.z * af.tailHeight;
        double vTail = -(va.x + wxrX);
        double tail = s.tailFailed ? 0.0
                : clamp(af.tailNominal * rhoR * wr * wr * s.pedal - af.tailDamping * rhoR * wr * vTail,
                        -af.tailMax, af.tailMax);

        // --- Forces, body frame ---------------------------------------------------------------
        double fx = thrust * f.nx(), fy = thrust * f.ny(), fz = thrust * f.nz();
        double rotX = fx, rotZ = fz;
        if (!af.coaxial) fx -= tail;
        fx -= 0.5 * rho * af.cdaX * Math.abs(va.x) * va.x;
        fy -= 0.5 * rho * af.cdaY * Math.abs(va.y) * va.y;
        fz -= 0.5 * rho * af.cdaZ * Math.abs(va.z) * va.z;
        s.q.transform(force.set(fx, fy, fz));
        force.y -= af.mass * env.g();
        force.add(avoid);

        // --- Moments, body frame --------------------------------------------------------------
        double bLon = u.cLon * af.cyclicMax, bLat = u.cLat * af.cyclicMax;
        double kBeta = af.hubStiffness * wr * wr;
        torque.set(af.hubHeight * rotZ + kBeta * bLon, 0.0, -af.hubHeight * rotX + kBeta * bLat);
        torque.x -= af.angularDamping * wr * s.w.x;
        torque.z -= af.angularDamping * wr * s.w.z;
        if (af.coaxial) {
            torque.y += tail;
        } else {
            torque.y += af.tailArm * tail;
            torque.z += af.tailHeight * tail;
            double shaft = powered ? qe : -qf; // freewheel: only friction reaches the airframe
            torque.y -= af.rotorDir * shaft;
        }
        // Euler: I w' = tau - w x (I w)
        double ix = af.ix, iy = af.iy, iz = af.iz;
        double wx = s.w.x, wy = s.w.y, wz = s.w.z;
        double gx = wy * (iz * wz) - wz * (iy * wy);
        double gy = wz * (ix * wx) - wx * (iz * wz);
        double gz = wx * (iy * wy) - wy * (ix * wx);

        // --- Integrate (semi-implicit Euler) --------------------------------------------------
        s.v.fma(H / af.mass, force);
        s.w.add(H * (torque.x - gx) / ix, H * (torque.y - gy) / iy, H * (torque.z - gz) / iz);
        s.p.fma(H, s.v);
        dq.set(s.w.x, s.w.y, s.w.z, 0.0);
        s.q.mul(dq, dq); // dq = q (x) (0, w)
        s.q.set(s.q.x + 0.5 * H * dq.x, s.q.y + 0.5 * H * dq.y, s.q.z + 0.5 * H * dq.z, s.q.w + 0.5 * H * dq.w)
                .normalize();
        if (s.q.w < 0.0) s.q.set(-s.q.x, -s.q.y, -s.q.z, -s.q.w);
        // Rotor brake: engine off on the ground (after a landing or a shutdown). Without it the rotor
        // coasts on aerodynamic drag alone for many minutes before the hull can rest.
        double brake = !powered && env.onGround() ? af.rotorInertia * af.omegaN / ROTOR_BRAKE_SECONDS : 0.0;
        s.omega = Math.max(0.0, omega + H * (qe - spinDrag + qin - brake) / af.rotorInertia);
        s.theta0 += alphaCollective * (clamp(u.collective, 0.0, 1.0) - s.theta0);
        s.pedal += alphaYaw * (clamp(u.pedal, -1.0, 1.0) - s.pedal);
        if (powered) {
            s.fuel = Math.max(0.0, s.fuel - H * fuelPerJoule * (qe * omega / af.efficiency + af.idlePower));
        }

        // Skids: resting on the ground with less thrust than weight, the ground reacts every moment
        // (no spin from rotor torque, no tipping) and friction bleeds horizontal speed.
        if (env.onGround() && thrust < af.mass * env.g()) {
            s.w.zero();
            double keep = StrictMath.exp(-SKID_FRICTION * H);
            s.v.x *= keep;
            s.v.z *= keep;
        }

        s.thrust = thrust;
        s.engineTorque = qe;
        s.tailThrust = tail;
    }

    /**
     * Engine state machine. START spools the governor target up at {@code startRate} and hands
     * over to RUN at 95 % speed; STOP or an empty tank shuts down; FAILED is set from outside
     * (hull below 10 % health) and never clears.
     */
    private void engineLogic(HeliState s, HeliControl.EngineCmd cmd) {
        if (s.engine == HeliState.Engine.FAILED) return;
        if (cmd == HeliControl.EngineCmd.START && s.engine == HeliState.Engine.OFF && s.fuel > 0.0) {
            s.engine = HeliState.Engine.START;
            s.omegaTarget = s.omega;
        } else if (cmd == HeliControl.EngineCmd.STOP) {
            s.engine = HeliState.Engine.OFF;
        }
        if (s.engine != HeliState.Engine.OFF && s.fuel <= 0.0) s.engine = HeliState.Engine.OFF;
        if (s.engine == HeliState.Engine.START) {
            s.omegaTarget = Math.min(af.omegaN, s.omegaTarget + H * af.startRate);
            if (s.omega >= 0.95 * af.omegaN) s.engine = HeliState.Engine.RUN;
        }
        if (s.engine == HeliState.Engine.RUN) s.omegaTarget = af.omegaN;
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
