package com.neoalive.tacz_sewv.heli.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Table-driven rotor model, shared by {@link HeliPhysics} and the controller's collective
 * inversion, so the two can never disagree about thrust. Pure functions; see plan section 4.2 for
 * the equations.
 *
 * <p>T = rho A (Omega R)^2 [C_T(theta0) - c_lambda V_c/(Omega R)] K_ETL K_GE K_VRS. The axial-inflow
 * term is ADDITIVE, the blade-element structure (C_T ~ (sigma a / 4)(pitch - inflow)): air coming up
 * through the disk makes thrust even at flat pitch, which is what lets a rotor autorotate. A
 * multiplicative form (C_T(theta0) x K_ax) was tried first and cannot: at zero pitch it makes no
 * thrust whatever the upflow, so there is no autorotative torque. Rotor torques act about the spin
 * axis: profile Q0, induced plus climb Qi, transmission friction Qf (these three are the spin
 * drag, positive-definite), and the autorotative inflow torque Qin = T max(0, -V_c) / Omega.
 */
public final class RotorModel {

    /** Air-flow state through the disk for one sub-step. */
    /**
     * @param kMul    K_ETL K_GE K_VRS
     * @param ctAxial additive axial-inflow term, -c_lambda V_c/(Omega R): positive in descent
     */
    public record Flow(double omegaR, double vc, double vPar, double mu, double hr, double vh, double kMul,
                       double ctAxial, double nx, double ny, double nz) {}

    private RotorModel() {}

    /** C_T at normalised collective {@code theta0}: piecewise linear, clamped at the ends. */
    public static double ct(Airframe af, double theta0) {
        double[] x = af.ctTheta, y = af.ctValue;
        if (theta0 <= x[0]) return y[0];
        for (int i = 1; i < x.length; i++) {
            if (theta0 <= x[i]) return y[i - 1] + (theta0 - x[i - 1]) * (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
        }
        return y[y.length - 1];
    }

    /** Exact inverse of {@link #ct} (the table is strictly increasing); clamps to [0, 1]. */
    public static double ctInverse(Airframe af, double c) {
        double[] x = af.ctTheta, y = af.ctValue;
        if (c <= y[0]) return x[0];
        for (int i = 1; i < y.length; i++) {
            if (c <= y[i]) return x[i - 1] + (c - y[i - 1]) * (x[i] - x[i - 1]) / (y[i] - y[i - 1]);
        }
        return x[x.length - 1];
    }

    public static double maxCt(Airframe af) {
        return af.ctValue[af.ctValue.length - 1];
    }

    /** Hover induced velocity at nominal mass: sqrt(m g / (2 rho A)). */
    public static double vh(Airframe af, double g, double rho) {
        return Math.max(1.0E-6, StrictMath.sqrt(af.mass * g / (2.0 * rho * af.area)));
    }

    /** Smoothstep on [0, 1], clamped. */
    public static double smooth(double x) {
        double c = Math.max(0.0, Math.min(1.0, x));
        return c * c * (3.0 - 2.0 * c);
    }

    /** Translational lift: 1 + k x e^(1-x), x = (mu/mu_p)^2. Peaks at 1 + k when mu = mu_p. */
    public static double kEtl(Airframe af, double mu) {
        double r = mu / af.etlMuPeak;
        double x = r * r;
        return 1.0 + af.etlK * x * StrictMath.exp(1.0 - x);
    }

    /** Ground effect: 1 + k exp(-c h/R), h = rotor-plane height above ground (clamped at 0). */
    public static double kGe(Airframe af, double hr) {
        if (!(hr < Double.POSITIVE_INFINITY)) return 1.0;
        return 1.0 + af.geK * StrictMath.exp(-af.geC * Math.max(hr, 0.0) / af.radius);
    }

    /**
     * Axial inflow, additive in C_T: -c_lambda V_c,eff/(Omega R). Climbing through the disk loses
     * thrust (heave damping); descending gains it, which is what drives the rotor in autorotation.
     * Inside the ring state the recirculating wake absorbs the descent, so the upflow does not reach
     * the blades as extra angle of attack: in descent V_c,eff = V_c (1 - B G), faded by the same C1
     * window as the ring loss. Without that fade the descent gain masks the ring state entirely.
     */
    public static double ctAxial(Airframe af, double vc, double omegaR, double ring) {
        double vEff = vc < 0.0 ? vc * (1.0 - ring) : vc;
        return -af.axialK * vEff / omegaR;
    }

    /**
     * Ring-state window B(xi) G(eta) in [0, 1], xi = descent / v_h, eta = in-plane speed / v_h.
     * Zero outside (xi_lo, xi_hi) x [0, eta_hi), 1 at (xi_pk, 0), C1 throughout.
     */
    public static double ring(Airframe af, double vc, double vPar, double vh) {
        double xi = Math.max(0.0, -vc) / vh;
        double b;
        if (xi <= af.vrsXiLo || xi >= af.vrsXiHi) b = 0.0;
        else if (xi <= af.vrsXiPeak) b = smooth((xi - af.vrsXiLo) / (af.vrsXiPeak - af.vrsXiLo));
        else b = 1.0 - smooth((xi - af.vrsXiPeak) / (af.vrsXiHi - af.vrsXiPeak));
        double gEta = 1.0 - smooth(Math.min(vPar / vh / af.vrsEtaHi, 1.0));
        return b * gEta;
    }

    /** Vortex-ring loss 1 - L_max B(xi) G(eta), applied multiplicatively. */
    public static double kVrs(Airframe af, double vc, double vPar, double vh) {
        return 1.0 - af.vrsLoss * ring(af, vc, vPar, vh);
    }

    /**
     * Flow through the disk for the hull's current state. {@code cLon}/{@code cLat} set the disk
     * normal; {@code wind} is world-frame; {@code groundY} is the ground under the hub.
     */
    public static Flow flow(Airframe af, HeliState s, double g, double rho, Vector3d wind, double groundY,
                            double cLon, double cLat) {
        double omegaR = Math.max(s.omega * af.radius, 1.0);
        double tLat = StrictMath.tan(cLat * af.cyclicMax), tLon = StrictMath.tan(cLon * af.cyclicMax);
        double inv = 1.0 / StrictMath.sqrt(tLat * tLat + 1.0 + tLon * tLon);
        double nx = -tLat * inv, ny = inv, nz = tLon * inv;

        Quaterniond qInv = s.q.conjugate(new Quaterniond());
        Vector3d va = qInv.transform(new Vector3d(s.v).sub(wind));
        double vc = va.x * nx + va.y * ny + va.z * nz;
        double px = va.x - vc * nx, py = va.y - vc * ny, pz = va.z - vc * nz;
        double vPar = StrictMath.sqrt(px * px + py * py + pz * pz);

        Vector3d hub = s.q.transform(new Vector3d(0.0, af.hubHeight, 0.0));
        double hr = s.p.y + af.cgHeight + hub.y - groundY;

        double vh = vh(af, g, rho);
        double mu = vPar / omegaR;
        double ring = ring(af, vc, vPar, vh);
        double kMul = kEtl(af, mu) * kGe(af, hr) * (1.0 - af.vrsLoss * ring);
        return new Flow(omegaR, vc, vPar, mu, hr, vh, kMul, ctAxial(af, vc, omegaR, ring), nx, ny, nz);
    }

    /** Thrust at rotor speed {@code omega} for the given flow and actual collective. Never negative. */
    public static double thrust(Airframe af, double rho, double omega, double theta0, Flow f) {
        double vt = omega * af.radius;
        if (vt == 0.0) return 0.0;
        return Math.max(0.0, rho * af.area * vt * vt * (ct(af, theta0) + f.ctAxial()) * f.kMul());
    }

    /** Thrust ceiling at full collective in this flow. */
    public static double maxThrust(Airframe af, double rho, double omega, Flow f) {
        double vt = omega * af.radius;
        return Math.max(0.0, rho * af.area * vt * vt * (maxCt(af) + f.ctAxial()) * f.kMul());
    }

    /** Collective that makes thrust {@code t} in this flow (the controller's inversion), clamped to [0, 1]. */
    public static double collectiveFor(Airframe af, double rho, double omega, double t, Flow f) {
        double vt = omega * af.radius;
        double denom = rho * af.area * vt * vt * f.kMul();
        return denom > 1.0E-9 ? ctInverse(af, t / denom - f.ctAxial()) : 0.0;
    }

    /** Induced velocity, hover momentum value blended to Glauert's v_i0^2/V at speed. */
    public static double induced(double rho, double area, double thrust, double vPar) {
        double vi0sq = thrust / (2.0 * rho * area);
        return vi0sq / StrictMath.sqrt(vPar * vPar + vi0sq + 1.0E-12);
    }

    /** Profile drag torque: rho A (Omega R)^2 R (sigma Cd0 / 8)(1 + 4.65 mu^2). */
    public static double profileTorque(Airframe af, double rho, double omega, double mu) {
        double vt = omega * af.radius;
        return rho * af.area * vt * vt * af.radius * (af.sigmaCd0 / 8.0) * (1.0 + 4.65 * mu * mu);
    }

    /** Shaft power to produce thrust {@code t} in steady flight at in-plane speed {@code vPar}, nominal RPM. */
    public static double powerRequired(Airframe af, double rho, double t, double vPar) {
        double mu = vPar / af.tipSpeed;
        return af.kappa * t * induced(rho, af.area, t, vPar)
                + profileTorque(af, rho, af.omegaN, mu) * af.omegaN
                + af.frictionCoeff * af.omegaN * af.omegaN;
    }

    /** Nominal hover power: the fuel-burn anchor and the tail-authority reference. */
    public static double hoverPower(Airframe af, double g, double rho) {
        return powerRequired(af, rho, af.mass * g, 0.0);
    }

    /** Collective that hovers out of ground effect at nominal RPM. */
    public static double hoverCollective(Airframe af, double g, double rho) {
        return ctInverse(af, af.mass * g / (rho * af.area * af.tipSpeed * af.tipSpeed));
    }
}
