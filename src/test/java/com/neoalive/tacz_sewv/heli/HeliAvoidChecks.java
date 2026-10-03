package com.neoalive.tacz_sewv.heli;

import java.util.List;
import java.util.function.DoubleFunction;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.avoid.AvoidBias;
import com.neoalive.tacz_sewv.heli.avoid.AvoidForce;
import com.neoalive.tacz_sewv.heli.avoid.ObstacleSet;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.physics.Airframe;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;
import com.neoalive.tacz_sewv.heli.physics.RotorModel;

/** Group A: collision avoidance math (plan section 8). A3 (touchdown exclusion) arrives with Land. */
final class HeliAvoidChecks {

    static final double HALF_WIDTH = 2.5, HEIGHT = 3.0;

    private HeliAvoidChecks() {}

    static void run() {
        biasSmoothness();
        noPenetration();
        latch();
        fastDescentProbe();
    }

    static AvoidForce barrierFor(Airframe af) {
        double aC = Math.max(Sim.G * Math.tan(af.tiltMax),
                RotorModel.maxCt(af) * Sim.RHO * af.area * af.tipSpeed * af.tipSpeed / af.mass - Sim.G);
        return new AvoidForce(aC, af.vMaxH, af.vDescent, 2.0, HALF_WIDTH, HEIGHT, 1.0, 0.05);
    }

    // A1 -------------------------------------------------------------------------------------------
    private static void biasSmoothness() {
        AvoidBias bias = new AvoidBias(8.0);
        double h = HeliPhysics.H, v = 20.0;
        Vector3d zero = new Vector3d();
        HeliReference prev = null;
        double maxDv = 0;
        for (int i = 0; i < 120 * 12; i++) {
            double t = i * h;
            // A 40 m ridge appears 60 m ahead at t = 1 s and is gone at t = 6 s.
            if (i % 6 == 0) {
                if (t >= 1.0 && t < 6.0) bias.demand(Math.max(5.0, 60.0 - v * (t - 1.0)), 140.0, 100.0, v, 1, 10.0, 5.0);
                else bias.demand(Double.POSITIVE_INFINITY, 0, 0, 0, 1, 0, 0);
            }
            bias.advance(h);
            HeliReference r = bias.apply(new HeliReference(t, new Vector3d(0, 100, v * t), new Vector3d(0, 0, v), zero, 0, 0));
            if (prev != null) {
                double dp = new Vector3d(r.p()).distance(prev.p());
                assert dp <= (v + 8.0 + 20.0) * h : "A1: biased position stepped by " + dp;
                maxDv = Math.max(maxDv, new Vector3d(r.v()).distance(prev.v()));
            }
            assert bias.altitude() >= 0 : "A1: altitude bias went negative";
            prev = r;
        }
        double bound = 2.0 * AvoidBias.OMEGA * AvoidBias.OMEGA * (44.0 + 10.0) * h; // b'' <= 2 w^2 |b*|, per sub-step
        assert maxDv <= bound : "A1: biased velocity stepped by " + maxDv + " (bound " + bound + ")";
        assert bias.altitude() < 1.0 : "A1: bias did not decay after the ridge, still " + bias.altitude();
    }

    // A2 -------------------------------------------------------------------------------------------
    private static void noPenetration() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            Airframe af = Sim.airframe(cls);
            for (double angle : new double[] {0, 30, 60, 89}) {
                Sim sim = new Sim(af).hover(60, 100, 0, 0);
                sim.barrier = barrierFor(af);
                sim.obstacles = new ObstacleSet(List.of(
                        new ObstacleSet.Box(1L, 100, -100, -2000, 101, 400, 2000, 0, 0, 0)), Double.NaN);
                double rad = Math.toRadians(angle);
                Vector3d dir = new Vector3d(Math.cos(rad), 0, Math.sin(rad));
                sim.s.v.set(dir).mul(af.vMaxH);
                Vector3d v = new Vector3d(dir).mul(af.vMaxH), zero = new Vector3d();
                Vector3d p0 = new Vector3d(sim.s.p);
                DoubleFunction<HeliReference> ref = t -> new HeliReference(t, new Vector3d(p0).fma(t, v), v, zero, 0, 0);
                double minGap = Double.MAX_VALUE;
                for (int i = 0; i < 200; i++) {
                    sim.tick(ref);
                    minGap = Math.min(minGap, 100 - (sim.s.p.x + HALF_WIDTH));
                }
                assert minGap >= 0 : "A2 " + cls + ": penetrated the wall at " + angle + " deg by " + (-minGap);
            }
            // Ground at the maximum commanded descent, reference going through it.
            Sim sim = new Sim(af).hover(0, 40, 0, 0);
            sim.barrier = barrierFor(af);
            sim.obstacles = new ObstacleSet(List.of(), 0.0);
            sim.s.v.set(0, -af.vDescent, 0);
            Vector3d vDown = new Vector3d(0, -af.vDescent, 0), zero = new Vector3d();
            DoubleFunction<HeliReference> down = t -> new HeliReference(t, new Vector3d(0, 40 - af.vDescent * t, 0), vDown, zero, 0, 0);
            boolean touched = false;
            for (int i = 0; i < 400; i++) {
                sim.tick(down);
                touched |= sim.onGround;
            }
            assert !touched : "A2 " + cls + ": barrier let a max-rate descent reach the ground";

            // Cruise at 35 m over flat ground: the speed-dependent band keeps the barrier silent.
            Sim cruise = new Sim(af).hover(0, 35, 0, 0);
            cruise.barrier = barrierFor(af);
            cruise.obstacles = new ObstacleSet(List.of(), 0.0);
            Vector3d vc = new Vector3d(0, 0, 15);
            cruise.s.v.set(vc);
            DoubleFunction<HeliReference> level = t -> new HeliReference(t, new Vector3d(0, 35, 15 * t), vc, zero, 0, 0);
            for (int i = 0; i < 400; i++) {
                cruise.tick(level);
                assert cruise.avoid.lengthSquared() == 0.0 : "A2 " + cls + ": barrier force during level cruise at 35 m";
            }
        }
    }

    // A5 -------------------------------------------------------------------------------------------
    /** The amended band rule: follows closing speed outside the band, ratchets up only inside it. */
    private static void latch() {
        AvoidForce f = new AvoidForce(10.0, 25.0, 5.0, 2.0, 2.0, 3.0, 1.0, 0.05);
        double rDetect = f.band(25.0, Double.POSITIVE_INFINITY);
        ObstacleSet.Box wall = new ObstacleSet.Box(7L, 100, -100, -100, 101, 100, 100, 0, 0, 0);
        ObstacleSet set = new ObstacleSet(List.of(wall), Double.NaN);

        f.update(set, at(rDetect + 5), toward(10));
        assert Double.isNaN(f.bandOf(7L)) : "A5: tracked outside r_detect";

        // Outside the band it follows the closing speed both ways (no force there either way).
        f.update(set, at(30), toward(10));
        assert f.bandOf(7L) == f.band(10, rDetect) : "A5: band not sized from the closing speed";
        f.update(set, at(29), toward(15));
        assert f.bandOf(7L) == f.band(15, rDetect) : "A5: band did not grow with speed outside it";
        f.update(set, at(28), toward(10));
        assert f.bandOf(7L) == f.band(10, rDetect) : "A5: band did not relax with speed outside it";

        // Inside the band it only ratchets up.
        f.update(set, at(10), toward(15));
        double inside = f.bandOf(7L);
        assert inside > 10 : "A5: setup should leave the hull inside its band";
        f.update(set, at(9), toward(5));
        assert f.bandOf(7L) == inside : "A5: band shrank while the barrier was acting";
        f.update(set, at(8), toward(20));
        assert f.bandOf(7L) == f.band(20, rDetect) : "A5: band did not ratchet up with speed inside it";

        // C1 at the edge: no chatter for a cooling rule to suppress.
        AvoidForce e = new AvoidForce(10.0, 25.0, 5.0, 2.0, 2.0, 3.0, 1.0, 0.05);
        e.update(set, at(20), toward(10));
        double rb = e.bandOf(7L), prev = 0, worstStep = 0;
        for (double d = rb + 0.5; d >= rb - 0.5; d -= 0.01) {
            double fx = Math.abs(e.force(set, at(d), toward(10), 1000, new Vector3d()).x);
            worstStep = Math.max(worstStep, Math.abs(fx - prev));
            prev = fx;
        }
        assert worstStep < 1000 * (40 + 20) * 0.01 : "A5: force steps at the band edge: " + worstStep;

        f.update(set, at(rDetect + 1.5), toward(10));
        assert Double.isNaN(f.bandOf(7L)) : "A5: not dropped beyond r_detect + margin";

        // The ground band is independent of lateral ones.
        AvoidForce g = new AvoidForce(10.0, 25.0, 5.0, 2.0, 2.0, 3.0, 1.0, 0.05);
        ObstacleSet both = new ObstacleSet(List.of(wall), 0.0);
        g.update(both, new Vector3d(100 - 2 - 3, 2.0, 0), new Vector3d(5, -3, 0));
        double gb = g.bandOf(ObstacleSet.GROUND);
        assert !Double.isNaN(gb) && !Double.isNaN(g.bandOf(7L)) : "A5: both bands should be tracked";
        g.update(both, new Vector3d(-100, 2.0, 0), new Vector3d(-5, -3, 0));
        assert Double.isNaN(g.bandOf(7L)) && g.bandOf(ObstacleSet.GROUND) == gb
                : "A5: dropping the wall changed the ground band";
    }

    private static Vector3d toward(double speed) {
        return new Vector3d(speed, 0, 0);
    }

    private static Vector3d at(double gap) {
        return new Vector3d(100 - 2 - gap, 0, 0);
    }

    // Informational: the case flagged for Phase 1 ------------------------------------------------------
    private static void fastDescentProbe() {
        for (String cls : HeliPhysicsChecks.CLASSES) {
            Airframe af = Sim.airframe(cls);
            Sim sim = new Sim(af).hover(0, 100, 0, 0);
            sim.barrier = barrierFor(af);
            sim.obstacles = new ObstacleSet(List.of(), 0.0);
            sim.s.v.set(0, -30, 0);
            Vector3d zero = new Vector3d();
            DoubleFunction<HeliReference> intoGround = t -> new HeliReference(t, new Vector3d(0, -50, 0), zero, zero, 0, 0);
            double vTouch = Double.NaN, maxForce = 0;
            int signFlips = 0;
            double prevVy = sim.s.v.y;
            for (int i = 0; i < 400 && Double.isNaN(vTouch); i++) {
                double vyBefore = sim.s.v.y;
                sim.tick(intoGround);
                maxForce = Math.max(maxForce, sim.avoid.y / (af.mass * Sim.G));
                if (Math.signum(sim.s.v.y) != Math.signum(prevVy) && Math.abs(sim.s.v.y) > 0.5) signFlips++;
                prevVy = sim.s.v.y;
                if (sim.onGround) vTouch = -vyBefore;
            }
            System.out.printf(java.util.Locale.ROOT,
                    "  FINDING %s: 30 m/s descent from 100 m, reference below ground: %s, peak barrier %.1f g, %d vertical reversals%n",
                    cls, Double.isNaN(vTouch) ? "stopped above ground"
                            : String.format(java.util.Locale.ROOT, "ground contact at %.1f m/s", vTouch),
                    maxForce, signFlips);
        }
    }
}
