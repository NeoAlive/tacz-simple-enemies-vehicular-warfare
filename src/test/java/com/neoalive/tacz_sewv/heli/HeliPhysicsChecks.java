package com.neoalive.tacz_sewv.heli;

import java.util.List;
import java.util.function.DoubleFunction;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliEnv;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/** Group P: the plant (plan section 8). P7 lives in {@link HeliFlightSelfCheck}. */
final class HeliPhysicsChecks {

    static final List<String> CLASSES = List.of("light", "attack", "attack_coaxial", "utility");

    private HeliPhysicsChecks() {}

    static void run() {
        hoverEquilibrium();
        translationalLift();
        groundEffect();
        vortexRing();
        gyroscopic();
        signs();
        governor();
        autorotation();
        flareAuthority();
        flareLanding();
    }

    // P1 -------------------------------------------------------------------------------------------
    private static void hoverEquilibrium() {
        for (String cls : CLASSES) {
            Sim sim = new Sim(cls).hover(0, 200, 0, 0);
            double th = RotorModel.hoverCollective(sim.af, Sim.G, Sim.RHO);
            assert th >= 0.35 && th <= 0.55 : "P1 " + cls + ": hover collective " + th;
            sim.run(40, Sim.hold(0, 200, 0, 0)); // let the loop converge, then let go of the sticks
            sim.closedLoop = false;
            Vector3d start = new Vector3d(sim.s.p);
            sim.run(10, null);
            double drift = sim.s.p.distance(start);
            assert drift < 0.5 : "P1 " + cls + ": open-loop drift " + drift + " m in 10 s";
        }
    }

    // P2 -------------------------------------------------------------------------------------------
    private static void translationalLift() {
        Airframe af = Sim.airframe("light");
        double w = af.mass * Sim.G;
        double vEtl = af.etlMuPeak * af.tipSpeed;
        double p0 = RotorModel.powerRequired(af, Sim.RHO, w, 0.0);
        double pEtl = RotorModel.powerRequired(af, Sim.RHO, w, vEtl);
        assert pEtl < 0.85 * p0 : "P2: forward flight at mu_p not cheaper: " + pEtl + " vs " + p0;

        // Power bucket: rotor power plus parasite D V has an interior minimum and rises past it.
        double best = Double.MAX_VALUE, vStar = 0;
        for (double v = 0; v <= 120; v += 0.5) {
            double p = RotorModel.powerRequired(af, Sim.RHO, w, v) + 0.5 * Sim.RHO * af.cdaZ * v * v * v;
            if (p < best) {
                best = p;
                vStar = v;
            }
        }
        assert vStar > 0 && vStar < 120 : "P2: no power bucket in 0..120 m/s";
        System.out.printf(java.util.Locale.ROOT, "  P2 light: P(mu_p)/P(0) = %.2f, bucket at %.1f m/s (%.1f x vMaxH)%n",
                pEtl / p0, vStar, vStar / af.vMaxH);

        // A disk tilted by gamma with T cos(gamma) = W accelerates at g tan(gamma) from rest.
        double gamma = Math.toRadians(10.0);
        Sim sim = new Sim(af).hover(0, 200, 0, 0);
        sim.s.q.set(McPose.toQuat(0, gamma == 0 ? 0 : Math.toDegrees(gamma), 0));
        sim.s.theta0 = RotorModel.ctInverse(af, w / Math.cos(gamma) / (Sim.RHO * af.area * af.tipSpeed * af.tipSpeed));
        sim.u.collective = sim.s.theta0;
        sim.closedLoop = false;
        sim.physics.step(sim.s, sim.u, sim.env(), new Vector3d());
        double ah = Math.hypot(sim.s.v.x, sim.s.v.z) / HeliPhysics.H;
        double want = Sim.G * Math.tan(gamma);
        assert Math.abs(ah - want) < 0.05 * want : "P2: tilt acceleration " + ah + " vs g tan(gamma) " + want;
    }

    // P3 -------------------------------------------------------------------------------------------
    private static void groundEffect() {
        Airframe af = Sim.airframe("light");
        double r = af.radius;
        assert close(RotorModel.kGe(af, r), 1.0 / (1.0 - 1.0 / 16.0), 0.01) : "P3: K_GE(R) vs Cheeseman-Bennett";
        assert close(RotorModel.kGe(af, 2 * r), 1.0 / (1.0 - 1.0 / 64.0), 0.005) : "P3: K_GE(2R) vs Cheeseman-Bennett";
        double ratio = RotorModel.kGe(af, 4 * r) / RotorModel.kGe(af, 0.5 * r);
        assert close(ratio, 1.0 / 1.137, 0.01) : "P3: hover C_T ratio 0.5R/4R = " + ratio;
        assert RotorModel.kGe(af, 0.0) < 1.3 : "P3: ground effect must stay bounded at h = 0";
    }

    // P5 -------------------------------------------------------------------------------------------
    private static void vortexRing() {
        Airframe af = Sim.airframe("light");
        double vh = RotorModel.vh(af, Sim.G, Sim.RHO);
        // Support and minimum.
        for (double xi : new double[] {0.0, 0.2, 0.3, 1.6, 2.5}) {
            assert RotorModel.kVrs(af, -xi * vh, 0, vh) == 1.0 : "P5: loss outside support at xi " + xi;
        }
        assert RotorModel.kVrs(af, -0.8 * vh, af.vrsEtaHi * vh, vh) == 1.0 : "P5: loss at eta_hi";
        assert close(RotorModel.kVrs(af, -0.8 * vh, 0, vh), 1.0 - af.vrsLoss, 1e-12) : "P5: depth at xi_pk";
        // C1: one-sided finite-difference slopes agree at every breakpoint, on both axes and at the corner.
        double e = 1.0E-6;
        double[] xis = {af.vrsXiLo, af.vrsXiPeak, af.vrsXiHi};
        double[] etas = {0.0, 0.5 * af.vrsEtaHi, af.vrsEtaHi};
        for (double xi : xis) {
            for (double eta : new double[] {0.0, 0.3, 0.7}) {
                double l = (k(af, xi, eta, vh) - k(af, xi - e, eta, vh)) / e;
                double rr = (k(af, xi + e, eta, vh) - k(af, xi, eta, vh)) / e;
                assert Math.abs(l - rr) < 1.0E-4 : "P5: d/dxi jumps at xi " + xi + " eta " + eta + ": " + l + " vs " + rr;
            }
        }
        for (double eta : etas) {
            for (double xi : new double[] {0.5, 0.8, 1.2}) {
                double l = eta == 0 ? 0 : (k(af, xi, eta, vh) - k(af, xi, eta - e, vh)) / e;
                double rr = (k(af, xi, eta + e, vh) - k(af, xi, eta, vh)) / e;
                assert Math.abs(l - rr) < 1.0E-4 : "P5: d/deta jumps at eta " + eta + " xi " + xi + ": " + l + " vs " + rr;
            }
        }
        // Emergence: at fixed hover collective, a vertical descent past onset deepens; with forward
        // speed it does not. Onset of divergence is where K_ax,cap (1 - L B(xi)) drops below 1.
        double divergent = descent(af, 0.6, 0.0);
        double recovered = descent(af, 0.6, 1.2);
        System.out.printf(java.util.Locale.ROOT, "  P5 light: vertical descent from xi 0.6 -> xi %.2f after 3 s; with eta 1.2 -> xi %.2f%n",
                divergent, recovered);
        assert divergent > 0.9 : "P5: vertical descent at xi 0.6 should deepen into VRS, got " + divergent;
        assert recovered < 0.6 : "P5: forward speed should keep the descent from deepening, got " + recovered;
    }

    private static double k(Airframe af, double xi, double eta, double vh) {
        return RotorModel.kVrs(af, -xi * vh, eta * vh, vh);
    }

    /** Open loop, hover collective, start descending at xi with in-plane speed eta; returns xi after 3 s. */
    private static double descent(Airframe af, double xi, double eta) {
        double vh = RotorModel.vh(af, Sim.G, Sim.RHO);
        Sim sim = new Sim(af).hover(0, 500, 0, 0);
        sim.closedLoop = false;
        sim.u.pedal = af.rotorDir * RotorModel.hoverPower(af, Sim.G, Sim.RHO) / af.omegaN
                / (af.tailArm * af.tailNominal);
        sim.s.pedal = sim.u.pedal;
        sim.s.v.set(0, -xi * vh, eta * vh);
        for (int i = 0; i < 60; i++) {
            sim.s.w.zero();                       // isolate the vertical axis: hold attitude
            sim.s.q.set(McPose.toQuat(0, 0, 0));
            sim.tick(null);
        }
        return -sim.s.v.y / vh;
    }

    // P6 -------------------------------------------------------------------------------------------
    private static void gyroscopic() {
        Airframe af = Sim.airframe("light"); // I_z < I_x < I_y: x is the intermediate axis
        HeliPhysics ph = new HeliPhysics(af, 0.0);
        HeliState s = new HeliState();
        s.w.set(1.0, 0.001, 0.001);
        HeliEnv vacuum = new HeliEnv(0.0, 1.0E-12, new Vector3d(), Double.POSITIVE_INFINITY, false);
        HeliControl u = new HeliControl();
        double e0 = energy(af, s), l0 = angularMomentumWorld(af, s).length();
        boolean flipped = false;
        double maxE = 0, maxL = 0;
        for (int i = 0; i < 120 * 30; i++) {
            ph.step(s, u, vacuum, new Vector3d());
            if (s.w.x < 0) flipped = true;
            if (i < 120 * 10) {
                maxE = Math.max(maxE, Math.abs(energy(af, s) - e0) / e0);
                maxL = Math.max(maxL, Math.abs(angularMomentumWorld(af, s).length() - l0) / l0);
            }
        }
        System.out.printf(java.util.Locale.ROOT, "  P6: 10 s drift energy %.2e, |L| %.2e%n", maxE, maxL);
        assert flipped : "P6: no intermediate-axis flip in 30 s: omega x I omega missing?";
        assert maxE < 1.0E-3 && maxL < 1.0E-3 : "P6: rigid-body invariants drift E " + maxE + " L " + maxL;
    }

    private static double energy(Airframe af, HeliState s) {
        return 0.5 * (af.ix * s.w.x * s.w.x + af.iy * s.w.y * s.w.y + af.iz * s.w.z * s.w.z);
    }

    private static Vector3d angularMomentumWorld(Airframe af, HeliState s) {
        return s.q.transform(new Vector3d(af.ix * s.w.x, af.iy * s.w.y, af.iz * s.w.z));
    }

    // P8 -------------------------------------------------------------------------------------------
    private static void signs() {
        Airframe af = Sim.airframe("light");
        Sim a = trimmed(af);
        a.u.cLon += 0.2;
        a.run(0.2, null);
        Vector3d nose = a.s.q.transform(new Vector3d(0, 0, 1));
        assert a.s.w.x > 0 : "P8: forward cyclic must pitch nose down";
        assert a.s.v.dot(nose.x, 0, nose.z) > 0 : "P8: forward cyclic must accelerate along the nose";

        Sim b = trimmed(af);
        double vBefore = b.s.v.x;
        b.u.cLat += 0.2;
        b.run(0.2, null);
        Vector3d starboard = b.s.q.transform(new Vector3d(-1, 0, 0));
        assert b.s.w.z > 0 : "P8: starboard cyclic must roll starboard";
        assert (b.s.v.x - vBefore) * starboard.x > 0 : "P8: starboard cyclic must accelerate to starboard";

        Sim c = trimmed(af);
        c.u.pedal += 0.2;
        c.run(0.2, null);
        assert c.s.w.y > 0 : "P8: positive pedal must yaw nose left";

        Sim d = trimmed(af);
        d.u.collective += 0.15;
        d.run(0.3, null);
        assert d.s.w.y * af.rotorDir < 0 : "P8: pulling collective must yaw the fuselage against the rotor";
    }

    /** Converged closed-loop hover, then sticks frozen. */
    private static Sim trimmed(Airframe af) {
        Sim sim = new Sim(af).hover(0, 200, 0, 0);
        sim.run(30, Sim.hold(0, 200, 0, 0));
        sim.closedLoop = false;
        return sim;
    }

    // P9 -------------------------------------------------------------------------------------------
    /**
     * The governor holds rotor speed through a load step the engine can supply: 0.3 to the
     * collective whose hover power is 90 % of maxPower. Past the power envelope the rotor droops by
     * design (thrust falls with Omega^2), which the second half asserts.
     */
    private static void governor() {
        for (String cls : CLASSES) {
            Airframe af = Sim.airframe(cls);
            double tStep = af.mass * Sim.G;
            while (RotorModel.powerRequired(af, Sim.RHO, tStep * 1.001, 0.0) < 0.9 * af.maxPower) tStep *= 1.001;
            double step = RotorModel.ctInverse(af, tStep / (Sim.RHO * af.area * af.tipSpeed * af.tipSpeed));
            double worst = stepDroop(af, step), beyond = stepDroop(af, 1.0);
            System.out.printf(java.util.Locale.ROOT, "  P9 %s: 0.3 -> %.2f collective, rotor within %.2f%%; full collective droops %.1f%%%n",
                    cls, step, 100 * worst, 100 * beyond);
            assert worst < 0.02 : "P9 " + cls + ": rotor speed strayed " + worst + " through an in-envelope step";
            assert beyond > worst : "P9 " + cls + ": no droop past the power envelope";
        }
    }

    private static double stepDroop(Airframe af, double collective) {
        Sim sim = trimmed(af);
        sim.u.collective = 0.3;
        sim.run(2, null);
        sim.u.collective = collective;
        double worst = 0;
        for (int i = 0; i < 100; i++) {
            sim.tick(null);
            worst = Math.max(worst, Math.abs(sim.s.omega - af.omegaN) / af.omegaN);
        }
        return worst;
    }

    // P4 -------------------------------------------------------------------------------------------
    private static void autorotation() {
        for (String cls : CLASSES) {
            Airframe af = Sim.airframe(cls);
            Sim sim = new Sim(af).hover(0, 1500, 0, 0);
            // Best glide inside the envelope is at the speed limit: induced velocity, which dominates the
            // autorotative sink at this disk loading, falls with forward speed (Glauert).
            DoubleFunction<HeliReference> cruise = cruise(af.vMaxH, 1500);
            sim.run(30, cruise);
            double poweredPedal = sim.s.pedal;
            sim.u.engine = HeliControl.EngineCmd.STOP;
            sim.run(10, cruise);
            double minW = Double.MAX_VALUE, maxW = 0, sumH = 0, sumV = 0;
            for (int i = 0; i < 200; i++) {
                sim.tick(cruise);
                minW = Math.min(minW, sim.s.omega / af.omegaN);
                maxW = Math.max(maxW, sim.s.omega / af.omegaN);
                sumH += Math.hypot(sim.s.v.x, sim.s.v.z);
                sumV += -sim.s.v.y;
            }
            double glide = sumH / sumV;
            // Steady autorotation balances rotor power: T (v_d - kappa v_i) = P0 + Pf with T ~ W,
            // so the sink the physics should settle at is v_d = kappa v_i(V) + (P0 + Pf)/W.
            double w = af.mass * Sim.G, vH = sumH / 200;
            double sinkPred = af.kappa * RotorModel.induced(Sim.RHO, af.area, w, vH)
                    + (RotorModel.powerRequired(af, Sim.RHO, w, vH) - af.kappa * w * RotorModel.induced(Sim.RHO, af.area, w, vH)) / w;
            double glidePred = vH / sinkPred;
            System.out.printf(java.util.Locale.ROOT, "  P4 %s: rotor %.2f..%.2f Omega_N, glide %.2f (energy balance %.2f), pedal %.2f -> %.2f%n",
                    cls, minW, maxW, glide, glidePred, poweredPedal, sim.s.pedal);
            assert minW >= 0.9 && maxW <= 1.1 : "P4 " + cls + ": autorotation rotor speed " + minW + ".." + maxW;
            assert glide > 1.0 : "P4 " + cls + ": not gliding (more down than forward): " + glide;
            assert Math.abs(glide - glidePred) <= 0.15 * glidePred : "P4 " + cls + ": glide " + glide + " vs energy balance " + glidePred;
            if (!af.coaxial) {
                assert poweredPedal * sim.s.pedal < 0 : "P4 " + cls + ": anti-torque pedal must reverse in autorotation";
            }
        }
    }

    /** Level flight north-to-south at {@code speed}, heading south, at altitude {@code y}. */
    private static DoubleFunction<HeliReference> cruise(double speed, double y) {
        Vector3d v = new Vector3d(0, 0, speed), zero = new Vector3d();
        return t -> new HeliReference(t, new Vector3d(0, y, speed * t), v, zero, 0.0, 0.0);
    }

    // P10 ------------------------------------------------------------------------------------------
    private static void flareAuthority() {
        double alphaFlare = 2.0 * Math.toRadians(20.0) / (1.5 * 1.5);
        for (String cls : CLASSES) {
            Airframe af = Sim.airframe(cls);
            double w = af.mass * Sim.G;
            double high = pitchAccel(af, 0.9, w), low = pitchAccel(af, 0.75, 0.5 * w);
            System.out.printf(java.util.Locale.ROOT, "  P10 %s: pitch authority %.2f / %.2f rad/s^2 (flare needs %.2f)%n",
                    cls, high, low, alphaFlare);
            assert high >= 3.0 * alphaFlare : "P10 " + cls + ": authority at 0.9 Omega_N, T = W: " + high;
            assert low >= alphaFlare : "P10 " + cls + ": authority at 0.75 Omega_N, T = W/2: " + low;
        }
    }

    private static double pitchAccel(Airframe af, double omegaFrac, double thrust) {
        return af.cyclicMax * (af.hubHeight * thrust + af.hubStiffness * omegaFrac * omegaFrac) / af.ix;
    }

    // P11 ------------------------------------------------------------------------------------------
    private static void flareLanding() {
        StringBuilder failures = new StringBuilder();
        for (String cls : CLASSES) {
            Airframe af = Sim.airframe(cls);
            Sim sim = new Sim(af).hover(0, 150, 0, 0);
            DoubleFunction<HeliReference> glide = cruise(af.vMaxH, 150);
            sim.run(20, glide);
            sim.u.engine = HeliControl.EngineCmd.STOP;
            FlareRef flare = new FlareRef(sim);
            double vTouch = Double.NaN, wTouch = Double.NaN;
            for (int i = 0; i < 20 * 120 && Double.isNaN(vTouch); i++) {
                double vyBefore = sim.s.v.y;
                sim.tick(flare);
                if (sim.onGround) {
                    vTouch = -vyBefore;
                    wTouch = sim.s.omega / af.omegaN;
                }
            }
            System.out.printf(java.util.Locale.ROOT, "  P11 %s: flare from %.1f m at %.1f m/s sink, touchdown sink %.2f m/s, rotor %.2f Omega_N%n",
                    cls, flare.h0, flare.sink0, vTouch, wTouch);
            if (!(vTouch < 8.0) || !(wTouch >= 0.6)) {
                failures.append(String.format(java.util.Locale.ROOT, " %s(sink %.2f, rotor %.2f)", cls, vTouch, wTouch));
            }
        }
        assert failures.length() == 0 : "P11: touchdown sink must be < 8 m/s (SBW gate) with rotor >= 0.6 Omega_N:" + failures;
    }

    /**
     * Autorotative glide straight ahead until the flare height, then a boundary-value flare: height
     * follows the minimum-jerk quintic from the current (y, v_y, 0) to (ground, -1.5 m/s, 0) over
     * T_f = 2 h / (|v_y| + 1.5) (at least 2 s), and forward speed bleeds to zero over the same window,
     * which is where a real flare finds its energy. Below the flare height the controller tracks
     * this with the normal collective inversion, spending rotor energy.
     */
    private static final class FlareRef implements DoubleFunction<HeliReference> {
        private final Sim sim;
        private double t0 = Double.NaN, tf, y0, vy0, z0, vz0;
        double h0, sink0;

        FlareRef(Sim sim) {
            this.sim = sim;
        }

        @Override
        public HeliReference apply(double t) {
            Vector3d zero = new Vector3d();
            double hr = sim.s.p.y + sim.af.cgHeight + sim.af.hubHeight - sim.groundY;
            if (Double.isNaN(t0)) {
                if (hr > sim.af.flareHeight) {
                    return new HeliReference(t, new Vector3d(sim.s.p).add(0, 0, 1), new Vector3d(0, 0, sim.af.vMaxH), zero, 0, 0);
                }
                t0 = t;
                y0 = sim.s.p.y;
                vy0 = sim.s.v.y;
                z0 = sim.s.p.z;
                vz0 = sim.s.v.z;
                h0 = y0 - sim.groundY;
                sink0 = -vy0;
                tf = Math.max(2.0, 2.0 * h0 / (Math.abs(vy0) + 1.5));
            }
            double tau = Math.min(t - t0, tf);
            double[] y = quintic(y0, vy0, 0, sim.groundY, -1.5, 0, tf, tau);
            // Forward speed is held through the first half (sink is arrested while the disk still has
            // airspeed, out of the ring state) and bled to half in the second: a run-on touchdown.
            double[] zq = quintic(0, vz0, 0, 0, 0.5 * vz0, 0, tf / 2, Math.max(0.0, tau - tf / 2));
            double z = z0 + vz0 * Math.min(tau, tf / 2) + (tau > tf / 2 ? horizontalRunOn(vz0, tf / 2, tau - tf / 2) : 0);
            double vz = tau > tf / 2 ? zq[1] : vz0, az = tau > tf / 2 ? zq[2] : 0;
            double extra = t - t0 - tf;
            if (extra > 0) {
                return new HeliReference(t, new Vector3d(0, sim.groundY - 1.5 * extra, z), new Vector3d(0, -1.5, 0.5 * vz0), zero, 0, 0);
            }
            return new HeliReference(t, new Vector3d(0, y[0], z), new Vector3d(0, y[1], vz), new Vector3d(0, y[2], az), 0, 0);
        }
    }

    /** Distance covered while speed eases from v0 to v0/2 over T (integral of the quintic speed blend). */
    private static double horizontalRunOn(double v0, double T, double tau) {
        double s = Math.min(1.0, tau / T);
        double iw = T * (Math.pow(s, 4) * 2.5 - 3 * Math.pow(s, 5) + Math.pow(s, 6));
        return v0 * Math.min(tau, T) - 0.5 * v0 * iw;
    }

    /** Minimum-jerk quintic from (p0, v0, a0) to (p1, v1, a1) over T: {p, v, a} at tau. */
    static double[] quintic(double p0, double v0, double a0, double p1, double v1, double a1, double T, double tau) {
        double h = p1 - p0;
        double c3 = (20 * h - (8 * v1 + 12 * v0) * T - (3 * a0 - a1) * T * T) / (2 * T * T * T);
        double c4 = (-30 * h + (14 * v1 + 16 * v0) * T + (3 * a0 - 2 * a1) * T * T) / (2 * T * T * T * T);
        double c5 = (12 * h - 6 * (v1 + v0) * T + (a1 - a0) * T * T) / (2 * T * T * T * T * T);
        double t2 = tau * tau, t3 = t2 * tau, t4 = t3 * tau, t5 = t4 * tau;
        return new double[] {
                p0 + v0 * tau + 0.5 * a0 * t2 + c3 * t3 + c4 * t4 + c5 * t5,
                v0 + a0 * tau + 3 * c3 * t2 + 4 * c4 * t3 + 5 * c5 * t4,
                a0 + 6 * c3 * tau + 12 * c4 * t2 + 20 * c5 * t3};
    }

    private static boolean close(double a, double b, double rel) {
        return Math.abs(a - b) <= rel * Math.abs(b);
    }
}
