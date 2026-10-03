package com.neoalive.tacz_sewv.heli.control;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliEnv;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/**
 * Cascaded flight controller (plan section 4.6), run every sub-step. It is the only writer of
 * the stick and collective fields of {@link HeliControl}.
 *
 * <pre>
 * position (P + v_r) -> velocity (PI + a_r + drag ff, back-calculation anti-windup)
 *   -> thrust vector (gravity + tail-force ff, tilt limit with vertical priority)
 *   -> desired attitude (up = thrust direction, nose = reference heading)
 *   -> attitude error (shortest arc) -> body-rate command (P + yaw-rate ff)
 *   -> rate loop (PD, gain-scheduled by control effectiveness) -> cyclic / pedal
 * thrust magnitude -> collective by inverting the same RotorModel the physics uses.
 * </pre>
 *
 * Saturation is applied only at the stages listed in the plan, with limits from the airframe,
 * never from config. Gains come from the airframe's bandwidths, so a class is tuned in Hz-like
 * terms rather than raw gains.
 */
public final class HeliController {

    /** Seconds airborne before the velocity integrator is allowed to run (weight-on-wheels gate). */
    private static final double WOW_DELAY = 0.25;

    private final Airframe af;
    private final double kv, ki, tt, hoverCollective;
    private final double derivAlpha;

    // Memory.
    private final Vector3d xi = new Vector3d();
    private final Vector3d rateErrPrev = new Vector3d(Double.NaN, 0, 0);
    private final Vector3d rateDeriv = new Vector3d();
    private double airborne;
    private double lastLon, lastLat;

    /** Back-calculation on/off. Only a self-check turns it off, to prove the check bites. */
    public boolean antiWindup = true;

    // Diagnostics of the last step, for self-checks and debug telemetry.
    public boolean thrustSaturated, tiltSaturated, autorotationLaw;

    public HeliController(Airframe af, double g, double rho) {
        this.af = af;
        this.kv = 2.0 * af.velZeta * af.bwVel;
        this.ki = af.bwVel * af.bwVel;
        this.tt = 0.5 * kv / ki;
        this.hoverCollective = RotorModel.hoverCollective(af, g, rho);
        // Derivative filter corner at three times the rate bandwidth.
        this.derivAlpha = StrictMath.exp(-HeliPhysics.H * 3.0 * af.bwRate);
    }

    /** Forget integrators and filters (re-seat, takeover). */
    public void reset() {
        xi.zero();
        rateErrPrev.set(Double.NaN, 0, 0);
        rateDeriv.zero();
        airborne = 0.0;
    }

    public Vector3dc integrator() {
        return xi;
    }

    /**
     * One sub-step. {@code avoid} is the barrier force this sub-step (world frame); while it is
     * non-zero the integrator is frozen along it. Writes collective, cyclic and pedal into
     * {@code out}; the engine command is left to guidance.
     */
    public void step(HeliReference r, HeliState s, HeliEnv env, Vector3dc avoid, HeliControl out) {
        double g = env.g(), rho = env.rho(), m = af.mass;
        Vector3d wind = new Vector3d(env.wind());
        Quaterniond qInv = s.q.conjugate(new Quaterniond());

        // 1. Position: P plus velocity feed-forward, saturated. The envelope bounds the controller's
        //    own correction, never a reference that deliberately leaves it (an autorotation flare
        //    sinks far faster than vDescent): the limits widen to include the reference velocity.
        Vector3d vc = new Vector3d(r.p()).sub(s.p);
        vc.set(af.bwPos * vc.x, af.bwPosV * vc.y, af.bwPos * vc.z).add(r.v());
        double vh = Math.sqrt(vc.x * vc.x + vc.z * vc.z);
        double vhMax = Math.max(af.vMaxH, Math.sqrt(r.v().x() * r.v().x() + r.v().z() * r.v().z()));
        if (vh > vhMax) {
            vc.x *= vhMax / vh;
            vc.z *= vhMax / vh;
        }
        vc.y = clamp(vc.y, Math.min(-af.vDescent, r.v().y()), Math.max(af.vClimb, r.v().y()));

        // 2. Velocity: PI + reference acceleration + drag feed-forward.
        Vector3d ev = new Vector3d(vc).sub(s.v);
        Vector3d dragB = fuselageDrag(qInv.transform(new Vector3d(vc).sub(wind)), rho);
        Vector3d aff = s.q.transform(dragB).mul(-1.0 / m);
        Vector3d ac = new Vector3d(r.a()).add(ev.x * kv, ev.y * kv, ev.z * kv).fma(ki, xi).add(aff);

        // 3. Thrust vector: gravity feed-forward, cancel the tail rotor's side force, tilt limit.
        Vector3d tailW = s.q.transform(new Vector3d(af.coaxial ? 0.0 : -s.tailThrust, 0.0, 0.0));
        Vector3d t = new Vector3d(ac).add(0.0, g, 0.0).mul(m).sub(tailW);
        t.y = Math.max(t.y, 0.2 * m * g);
        double th = Math.sqrt(t.x * t.x + t.z * t.z);
        double thMax = t.y * StrictMath.tan(af.tiltMax);
        tiltSaturated = th > thMax;
        if (tiltSaturated) {
            t.x *= thMax / th;
            t.z *= thMax / th;
        }
        RotorModel.Flow flow = RotorModel.flow(af, s, g, rho, wind, env.groundY(), lastLon, lastLat);
        double tMax = RotorModel.maxThrust(af, rho, s.omega, flow);
        double tReq = t.length();
        double tSat = Math.min(tReq, tMax);
        thrustSaturated = tReq > tMax;
        Vector3d up = new Vector3d(t).div(tReq);

        // 4. Desired attitude: mast along the thrust direction, nose toward the reference heading.
        double yaw = r.yaw();
        Vector3d fh = new Vector3d(-StrictMath.sin(yaw), 0.0, StrictMath.cos(yaw));
        Vector3d zd = new Vector3d(fh).fma(-fh.dot(up), up).normalize();
        Vector3d xd = new Vector3d(up).cross(zd);
        Quaterniond qd = new Quaterniond().setFromNormalized(new Matrix3d(xd, up, zd));

        // 5. Attitude error, world frame, shortest arc (ties broken lexicographically), to body.
        Quaterniond qe = new Quaterniond(qd).mul(qInv);
        if (qe.w < 0.0 || (qe.w == 0.0 && firstNonZeroNegative(qe))) qe.set(-qe.x, -qe.y, -qe.z, -qe.w);
        double vn = Math.sqrt(qe.x * qe.x + qe.y * qe.y + qe.z * qe.z);
        Vector3d phi = vn < 1.0E-9
                ? new Vector3d(2.0 * qe.x, 2.0 * qe.y, 2.0 * qe.z)
                : new Vector3d(qe.x, qe.y, qe.z).mul(2.0 * StrictMath.atan2(vn, qe.w) / vn);
        qInv.transform(phi);

        // 6. Body-rate command: P on the error plus the reference yaw rate as feed-forward.
        Vector3d wff = qInv.transform(new Vector3d(0.0, -r.yawRate(), 0.0));
        Vector3d wc = new Vector3d(phi).mul(af.bwAtt).add(wff);
        wc.set(clamp(wc.x, -af.rateMaxPR, af.rateMaxPR), clamp(wc.y, -af.yawRateMax, af.yawRateMax),
                clamp(wc.z, -af.rateMaxPR, af.rateMaxPR));

        // 7. Rate loop: PD, gains scheduled by each channel's control effectiveness.
        Vector3d ew = new Vector3d(wc).sub(s.w);
        if (Double.isNaN(rateErrPrev.x)) rateErrPrev.set(ew);
        rateDeriv.mul(derivAlpha).fma((1.0 - derivAlpha) / HeliPhysics.H, new Vector3d(ew).sub(rateErrPrev));
        rateErrPrev.set(ew);
        double wr = s.omega / af.omegaN;
        double cyclicEff = Math.max(af.cyclicMax * (af.hubHeight * s.thrust + af.hubStiffness * wr * wr), 1.0);
        double kLon = af.bwRate * af.ix / cyclicEff, kLat = af.bwRate * af.iz / cyclicEff;
        double yawEff = Math.max((af.coaxial ? 1.0 : af.tailArm) * af.tailNominal * (rho / Airframe.RHO0) * wr * wr, 1.0);
        double kYaw = af.bwYaw * af.iy / yawEff;
        double antiTorque = af.coaxial ? 0.0 : af.rotorDir * s.engineTorque / yawEff;
        out.cLon = clamp(kLon * (ew.x + af.rateZeta / af.bwRate * rateDeriv.x), -1.0, 1.0);
        out.cLat = clamp(kLat * (ew.z + af.rateZeta / af.bwRate * rateDeriv.z), -1.0, 1.0);
        out.pedal = clamp(antiTorque + kYaw * (ew.y + af.rateZeta / af.bwYaw * rateDeriv.y), -1.0, 1.0);
        lastLon = out.cLon;
        lastLat = out.cLat;

        // 8. Collective: invert the rotor table; with the engine out, govern rotor speed instead
        //    until the flare height.
        boolean powered = s.engine == HeliState.Engine.START || s.engine == HeliState.Engine.RUN;
        autorotationLaw = !powered && flow.hr() > af.flareHeight;
        if (s.engine == HeliState.Engine.START) {
            out.collective = 0.0; // flat pitch while spooling: no thrust demand at low rotor speed
        } else if (autorotationLaw) {
            out.collective = clamp(af.autoCollective + af.autoKOmega * (s.omega - af.omegaN) / af.omegaN,
                    0.0, hoverCollective);
        } else {
            out.collective = RotorModel.collectiveFor(af, rho, s.omega, tSat, flow);
        }

        // 9. Integrator: back-calculation against what saturation actually allows, held on the
        //    ground until airborne for WOW_DELAY, frozen along the barrier force.
        boolean grounded = env.onGround() && (s.engine != HeliState.Engine.RUN || s.thrust < 0.9 * m * g);
        if (grounded) {
            xi.zero();
            airborne = 0.0;
            return;
        }
        airborne += HeliPhysics.H;
        if (airborne < WOW_DELAY) return;
        Vector3d aAch = new Vector3d(up).mul(tSat).add(tailW).div(m).sub(0.0, g, 0.0);
        Vector3d dxi = antiWindup ? new Vector3d(aAch).sub(ac).div(ki * tt).add(ev) : new Vector3d(ev);
        if (autorotationLaw) dxi.y = 0.0;
        double an = avoid.length();
        if (an > 0.0) {
            Vector3d n = new Vector3d(avoid).div(an);
            dxi.fma(-dxi.dot(n), n);
        }
        xi.fma(HeliPhysics.H, dxi);
        double limH = g * StrictMath.tan(af.tiltMax) / ki, limV = g / ki;
        xi.set(clamp(xi.x, -limH, limH), clamp(xi.y, -limV, limV), clamp(xi.z, -limH, limH));
    }

    private Vector3d fuselageDrag(Vector3d va, double rho) {
        return new Vector3d(-0.5 * rho * af.cdaX * Math.abs(va.x) * va.x,
                -0.5 * rho * af.cdaY * Math.abs(va.y) * va.y,
                -0.5 * rho * af.cdaZ * Math.abs(va.z) * va.z);
    }

    private static boolean firstNonZeroNegative(Quaterniond q) {
        if (q.x != 0.0) return q.x < 0.0;
        if (q.y != 0.0) return q.y < 0.0;
        return q.z < 0.0;
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
