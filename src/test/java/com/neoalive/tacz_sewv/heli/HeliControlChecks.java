package com.neoalive.tacz_sewv.heli;

import java.util.Map;
import java.util.function.DoubleFunction;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.guidance.DubinsPlanar;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliControl;
import com.neoalive.tacz_sewv.heli.physics.HeliState;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/** Group C: the cascaded controller (plan section 8). */
final class HeliControlChecks {

    /** Worst tilt / body rate seen across the controller checks (C7). */
    private static double maxTilt, maxRatePR;

    private HeliControlChecks() {}

    static void run() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            altitudeStep(cls);
            crosswindHover(cls);
            squareTransit(cls);
            takeoff(cls);
        }
        saturation();
        inversion();
        System.out.printf(java.util.Locale.ROOT, "  C7: worst tilt %.1f deg, worst pitch/roll rate %.1f deg/s%n", maxTilt, Math.toDegrees(maxRatePR));
    }

    private static void observe(Sim sim) {
        maxTilt = Math.max(maxTilt, sim.tiltDeg());
        maxRatePR = Math.max(maxRatePR, Math.max(Math.abs(sim.s.w.x), Math.abs(sim.s.w.z)));
        assert sim.tiltDeg() <= Math.toDegrees(sim.af.tiltMax) + 5.0 : "C7: tilt " + sim.tiltDeg();
        assert Math.abs(sim.s.w.x) <= 1.5 * sim.af.rateMaxPR && Math.abs(sim.s.w.z) <= 1.5 * sim.af.rateMaxPR
                : "C7: body rate exceeds the limit";
    }

    // C1 -------------------------------------------------------------------------------------------
    private static void altitudeStep(String cls) {
        Sim sim = new Sim(cls).hover(0, 100, 0, 0);
        sim.run(20, Sim.hold(0, 100, 0, 0));
        DoubleFunction<HeliReference> ref = Sim.hold(0, 110, 0, 0);
        double peak = 0, settledBy = Double.NaN;
        for (int i = 0; i < 20 * 25; i++) {
            sim.tick(ref);
            observe(sim);
            peak = Math.max(peak, sim.s.p.y - 110);
            boolean in = Math.abs(sim.s.p.y - 110) <= 0.2;
            if (in && Double.isNaN(settledBy)) settledBy = i / 20.0;
            if (!in) settledBy = Double.NaN;
        }
        System.out.printf(java.util.Locale.ROOT, "  C1 %s: overshoot %.2f m, settled (2%%) by %.1f s%n", cls, peak, settledBy);
        assert peak < 1.0 : "C1 " + cls + ": 10 m step overshoot " + peak;
        assert settledBy <= 15.0 : "C1 " + cls + ": not settled within 2% by 15 s";
    }

    // C2 -------------------------------------------------------------------------------------------
    private static void crosswindHover(String cls) {
        Sim sim = new Sim(cls).hover(0, 100, 0, 0);
        sim.wind.set(8, 0, 0);
        sim.run(40, Sim.hold(0, 100, 0, 0));
        double err = sim.s.p.distance(0, 100, 0);
        // Tilt the thrust must take to balance the side load it actually carries: drag plus tail force.
        Vector3d side = new Vector3d(-sim.s.tailThrust, 0, 0);
        if (sim.af.coaxial) side.zero();
        sim.s.q.transform(side);
        double drag = 0.5 * Sim.RHO * sim.af.cdaX * 64.0;
        double horiz = Math.hypot(side.x + drag, side.z);
        double want = Math.toDegrees(Math.atan2(horiz, sim.af.mass * Sim.G));
        // The force balance predicts the THRUST tilt: the disk normal, which lateral cyclic (trimming
        // the tail rotor's roll coupling) holds off the mast.
        double tLat = Math.tan(sim.u.cLat * sim.af.cyclicMax), tLon = Math.tan(sim.u.cLon * sim.af.cyclicMax);
        Vector3d disk = sim.s.q.transform(new Vector3d(-tLat, 1, tLon).normalize());
        double thrustTilt = Math.toDegrees(Math.acos(disk.y));
        System.out.printf(java.util.Locale.ROOT, "  C2 %s: 8 m/s crosswind, error %.2f m, thrust tilt %.2f (force balance %.2f) deg%n",
                cls, err, thrustTilt, want);
        assert err < 0.5 : "C2 " + cls + ": crosswind hover error " + err;
        assert Math.abs(thrustTilt - want) < 1.0 : "C2 " + cls + ": thrust tilt " + thrustTilt + " vs " + want;
    }

    // C3 -------------------------------------------------------------------------------------------
    private static void squareTransit(String cls) {
        SquareRef ref = new SquareRef(15.0, 100.0);
        Sim sim = ref.startOnPath(new Sim(cls));
        double sq = 0, worst = 0, worstAlt = 0;
        int n = 0;
        for (int i = 0; i < (int) (ref.loop.length() / 15.0 * 20); i++) {
            sim.tick(ref);
            observe(sim);
            if (i < 60) continue; // 3 s for the loops to settle onto the path
            double xt = ref.crossTrack(sim.s.p.x, sim.s.p.z);
            sq += xt * xt;
            n++;
            worst = Math.max(worst, xt);
            worstAlt = Math.max(worstAlt, Math.abs(sim.s.p.y - 100));
        }
        double rms = Math.sqrt(sq / n);
        System.out.printf(java.util.Locale.ROOT, "  C3 %s: square at 15 m/s, cross-track rms %.2f max %.2f m, altitude +-%.2f m%n",
                cls, rms, worst, worstAlt);
        assert rms < 2.0 && worst < 5.0 : "C3 " + cls + ": cross-track rms " + rms + " max " + worst;
        assert worstAlt < 1.5 : "C3 " + cls + ": altitude error " + worstAlt;
    }

    /** A closed 200 m square of Dubins legs, flown at constant speed, nose along the track. */
    static final class SquareRef implements DoubleFunction<HeliReference> {
        final DubinsPlanar.Path[] legs = new DubinsPlanar.Path[4];
        final double[] start;
        final DubinsPlanar.Path loop;
        private final double speed, y;

        SquareRef(double speed, double y) {
            this.speed = speed;
            this.y = y;
            double[][] c = {{0, 0}, {200, 0}, {200, 200}, {0, 200}};
            double r = 40.0;
            double s2 = Math.sqrt(0.5);
            double[][] h = {{s2, -s2}, {s2, s2}, {-s2, s2}, {-s2, -s2}};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                legs[i] = DubinsPlanar.shortest(c[i][0], c[i][1], h[i][0], h[i][1], c[j][0], c[j][1], h[j][0], h[j][1], r);
            }
            start = c[0];
            double len = 0;
            for (DubinsPlanar.Path p : legs) len += p.length();
            loop = new DubinsPlanar.Path(java.util.List.of(), len, "loop");
        }

        /** Put the hull on the path at t = 0: start pose, nose along the track, already at speed. */
        Sim startOnPath(Sim sim) {
            HeliReference r = apply(0.0);
            sim.hover(r.p().x(), r.p().y(), r.p().z(), Math.toDegrees(r.yaw()));
            sim.s.v.set(r.v());
            return sim;
        }

        @Override
        public HeliReference apply(double t) {
            double s = Math.max(0.0, t) * speed;
            double[] o = sample(s);
            Vector3d p = new Vector3d(o[0], y, o[1]);
            Vector3d v = new Vector3d(o[2] * speed, 0, o[3] * speed);
            // centripetal: kappa V^2 toward the left normal (d.z, -d.x)
            Vector3d a = new Vector3d(o[3], 0, -o[2]).mul(o[4] * speed * speed);
            double yaw = Math.atan2(-o[2], o[3]);
            return new HeliReference(t, p, v, a, yaw, -o[4] * speed);
        }

        double[] sample(double s) {
            double[] out = new double[5];
            double rem = s % loop.length();
            for (DubinsPlanar.Path leg : legs) {
                if (rem <= leg.length()) {
                    leg.sample(rem, out);
                    return out;
                }
                rem -= leg.length();
            }
            legs[3].sample(legs[3].length(), out);
            return out;
        }

        double crossTrack(double x, double z) {
            double best = Double.MAX_VALUE;
            double[] o = new double[5];
            for (DubinsPlanar.Path leg : legs) {
                for (double s = 0; s <= leg.length(); s += 0.25) {
                    leg.sample(s, o);
                    best = Math.min(best, Math.hypot(o[0] - x, o[1] - z));
                }
            }
            return best;
        }
    }

    // C5 -------------------------------------------------------------------------------------------
    private static void takeoff(String cls) {
        Sim sim = new Sim(cls);
        sim.s.p.set(0, 0, 0);
        sim.s.q.set(new org.joml.Quaterniond());
        sim.onGround = true;
        sim.u.engine = HeliControl.EngineCmd.START;
        DoubleFunction<HeliReference> ref = Sim.hold(0, 30, 0, 0);
        double peak = 0;
        boolean heldOnGround = true;
        for (int i = 0; i < 20 * 60; i++) {
            sim.tick(ref);
            if (sim.s.engine == HeliState.Engine.RUN) sim.u.engine = HeliControl.EngineCmd.HOLD;
            if (sim.onGround && sim.ctl.integrator().length() != 0.0) heldOnGround = false;
            peak = Math.max(peak, sim.s.p.y - 30);
        }
        System.out.printf(java.util.Locale.ROOT, "  C5 %s: takeoff to 30 m, overshoot %.2f m, final %.2f m%n", cls, peak, sim.s.p.y);
        assert heldOnGround : "C5 " + cls + ": integrator ran while on the ground";
        assert peak < 3.0 : "C5 " + cls + ": takeoff overshoot " + peak;
        assert Math.abs(sim.s.p.y - 30) < 0.5 : "C5 " + cls + ": did not settle at 30 m";
    }

    // C4 -------------------------------------------------------------------------------------------
    private static void saturation() {
        double with = climbOvershoot(true), without = climbOvershoot(false);
        System.out.printf(java.util.Locale.ROOT, "  C4: saturated 20 m climb overshoot %.2f m with back-calculation, %.2f m without%n",
                with, without);
        assert with < 2.0 : "C4: overshoot with anti-windup " + with;
        assert without >= 2.0 : "C4: negative control did not fail (" + without + "): the check does not bite";
    }

    /** A heavy hull (T/W 1.12, power-limited) climbing 20 m: thrust saturates for the whole climb. */
    private static double climbOvershoot(boolean antiWindup) {
        Airframe heavy = Sim.airframe("light").with(Map.of("mass", 1400 * 1.25));
        Sim sim = new Sim(heavy).hover(0, 100, 0, 0);
        sim.ctl.antiWindup = antiWindup;
        sim.run(10, Sim.hold(0, 100, 0, 0));
        double peak = 0;
        for (int i = 0; i < 20 * 60; i++) {
            sim.tick(Sim.hold(0, 120, 0, 0));
            peak = Math.max(peak, sim.s.p.y - 120);
        }
        return peak;
    }

    // C6 -------------------------------------------------------------------------------------------
    private static void inversion() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            Airframe af = Sim.airframe(cls);
            for (int i = 0; i < af.ctTheta.length; i++) {
                double th = af.ctTheta[i];
                assert Math.abs(RotorModel.ctInverse(af, RotorModel.ct(af, th)) - th) < 1e-12 : "C6 node " + th;
                if (i > 0) {
                    double mid = 0.5 * (th + af.ctTheta[i - 1]);
                    assert Math.abs(RotorModel.ctInverse(af, RotorModel.ct(af, mid)) - mid) < 1e-12 : "C6 mid " + mid;
                }
            }
            assert RotorModel.ctInverse(af, -1) == 0.0 && RotorModel.ctInverse(af, 1) == 1.0 : "C6 clamps";
        }
    }
}
