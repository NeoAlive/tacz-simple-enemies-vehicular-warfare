package com.neoalive.tacz_sewv.heli;

import java.util.SplittableRandom;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.entity.ai.plane.Dubins;
import com.neoalive.tacz_sewv.entity.ai.plane.DubinsPath;
import com.neoalive.tacz_sewv.heli.guidance.DubinsPlanar;
import com.neoalive.tacz_sewv.heli.guidance.HeliReference;
import com.neoalive.tacz_sewv.heli.guidance.Prefilter;
import com.neoalive.tacz_sewv.heli.guidance.ReferenceBlend;
import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;

/** Guidance math available in Phase 1: Dubins port (part of R1/R2), blend (R6), prefilter (R7). */
final class HeliGuidanceChecks {

    private HeliGuidanceChecks() {}

    static void run() {
        dubins();
        blend();
        prefilter();
    }

    // R1/R2 geometry ----------------------------------------------------------------------------------
    private static void dubins() {
        // Handedness: heading south, the target behind and to the east (Minecraft's left) is a left U-turn.
        DubinsPlanar.Path u = DubinsPlanar.shortest(0, 0, 0, 1, 50, 0, 0, -1, 10);
        assert u.word().charAt(0) == 'L' : "R1: east of a south-facing hull must be a LEFT turn, got " + u.word();

        SplittableRandom rng = new SplittableRandom(0xD0B1L);
        double[] a = new double[5], b = new double[5];
        int mirrorChecked = 0;
        for (int i = 0; i < 5000; i++) {
            double r = rng.nextDouble(20, 120);
            double x0 = rng.nextDouble(-300, 300), z0 = rng.nextDouble(-300, 300);
            double x1 = rng.nextDouble(-300, 300), z1 = rng.nextDouble(-300, 300);
            double h0 = rng.nextDouble(0, 2 * Math.PI), h1 = rng.nextDouble(0, 2 * Math.PI);
            double dx0 = Math.sin(h0), dz0 = Math.cos(h0), dx1 = Math.sin(h1), dz1 = Math.cos(h1);
            DubinsPlanar.Path p = DubinsPlanar.shortest(x0, z0, dx0, dz0, x1, z1, dx1, dz1, r);

            p.sample(0, a);
            assert Math.hypot(a[0] - x0, a[1] - z0) < 1e-6 && Math.hypot(a[2] - dx0, a[3] - dz0) < 1e-9
                    : "R1: path does not start at the start pose";
            p.sample(p.length(), a);
            assert Math.hypot(a[0] - x1, a[1] - z1) < 1e-6 && Math.hypot(a[2] - dx1, a[3] - dz1) < 1e-6
                    : "R1: path does not end at the end pose (" + p.word() + ")";
            double s = 0;
            for (int k = 0; k + 1 < p.segments().size(); k++) {
                DubinsPlanar.Segment seg = p.segments().get(k);
                seg.sample(seg.length(), a);
                p.segments().get(k + 1).sample(0, b);
                assert Math.hypot(a[0] - b[0], a[1] - b[1]) < 1e-6 && Math.hypot(a[2] - b[2], a[3] - b[3]) < 1e-6
                        : "R1: join " + k + " of " + p.word() + " is not C1";
                s += seg.length();
            }
            for (double q = 0; q <= p.length(); q += p.length() / 50) {
                p.sample(q, a);
                assert Math.abs(a[4]) <= 1.0 / r + 1e-9 : "R2: curvature exceeds 1/R_min";
            }

            // Cross-validate against the plane solver on mirrored inputs (x -> -x).
            DubinsPath plane = Dubins.computePath(new Vec3(-x0, 0, z0), new Vec3(-dx0, 0, dz0),
                    new Vec3(-x1, 0, z1), new Vec3(-dx1, 0, dz1), r);
            assert Math.abs(plane.totalLength() - p.length()) < 1e-6
                    : "R1: port disagrees with plane Dubins: " + p.length() + " vs " + plane.totalLength() + " (" + p.word() + ")";
            mirrorChecked++;
        }
        System.out.printf(java.util.Locale.ROOT, "  R1/R2: %d random paths C1, on-pose, curvature-bounded, equal to the plane solver mirrored%n",
                mirrorChecked);
    }

    // R6 -----------------------------------------------------------------------------------------------
    private static void blend() {
        SplittableRandom rng = new SplittableRandom(0xB1E9DL);
        for (int i = 0; i < 2000; i++) {
            Poly o = new Poly(rng), in = new Poly(rng);
            double ts = rng.nextDouble(0, 5);
            HeliReference ro = o.at(ts), ri = in.at(ts);
            double dp = new Vector3d(ri.p()).distance(ro.p()), dv = new Vector3d(ri.v()).distance(ro.v());
            double tb = ReferenceBlend.window(dp, dv, 0.75, 100.0, 10.0, 5.0);

            HeliReference start = ReferenceBlend.blend(ro, ri, ts, tb, ts);
            assertSame(start, ro, "R6: blend must equal the outgoing reference at the switch");
            double te = ts + tb * (1.0 + 1e-12); // (ts + tb - ts)/tb is not exactly 1 in floating point
            HeliReference end = ReferenceBlend.blend(o.at(te), in.at(te), ts, tb, te);
            assertSame(end, in.at(te), "R6: blend must equal the incoming reference at the end");

            // Velocity and acceleration are the analytic derivatives of the blended position.
            double t = ts + rng.nextDouble(0.05, 0.95) * tb, e = 1e-4;
            Vector3d pPlus = new Vector3d(ReferenceBlend.blend(o.at(t + e), in.at(t + e), ts, tb, t + e).p());
            Vector3d pMinus = new Vector3d(ReferenceBlend.blend(o.at(t - e), in.at(t - e), ts, tb, t - e).p());
            HeliReference mid = ReferenceBlend.blend(o.at(t), in.at(t), ts, tb, t);
            Vector3d fdv = new Vector3d(pPlus).sub(pMinus).div(2 * e);
            Vector3d fda = new Vector3d(pPlus).add(pMinus).sub(new Vector3d(mid.p()).mul(2)).div(e * e);
            assert fdv.distance(mid.v()) < 1e-4 * (1 + fdv.length()) : "R6: v_b is not dp_b/dt";
            assert fda.distance(mid.a()) < 1e-2 * (1 + fda.length()) : "R6: a_b is not d2p_b/dt2";
        }
        // Window bounds for a pure offset: added speed <= vBlend, added acceleration <= aBlend.
        double dp = 40, tb = ReferenceBlend.window(dp, 0, 0.75, 100, 10, 5);
        assert ReferenceBlend.MAX_W1 * dp / tb <= 10 + 1e-9 && ReferenceBlend.MAX_W2 * dp / (tb * tb) <= 5 + 1e-9
                : "R6: window too short for its bounds";
    }

    private static void assertSame(HeliReference a, HeliReference b, String what) {
        assert new Vector3d(a.p()).distance(b.p()) < 1e-9 && new Vector3d(a.v()).distance(b.v()) < 1e-9
                && new Vector3d(a.a()).distance(b.a()) < 1e-9 && Math.abs(ReferenceBlend.wrapRad(a.yaw() - b.yaw())) < 1e-9
                : what;
    }

    /** A random cubic-in-time reference with analytic derivatives. */
    private static final class Poly {
        final double[][] c = new double[3][4];
        final double y0, y1;

        Poly(SplittableRandom r) {
            for (double[] axis : c) for (int k = 0; k < 4; k++) axis[k] = r.nextDouble(-20, 20) / (1 + k * k);
            y0 = r.nextDouble(-Math.PI, Math.PI);
            y1 = r.nextDouble(-1, 1);
        }

        HeliReference at(double t) {
            Vector3d p = new Vector3d(), v = new Vector3d(), a = new Vector3d();
            for (int i = 0; i < 3; i++) {
                double[] k = c[i];
                double pi = k[0] + k[1] * t + k[2] * t * t + k[3] * t * t * t;
                double vi = k[1] + 2 * k[2] * t + 3 * k[3] * t * t;
                double ai = 2 * k[2] + 6 * k[3] * t;
                p.setComponent(i, pi);
                v.setComponent(i, vi);
                a.setComponent(i, ai);
            }
            return new HeliReference(t, p, v, a, y0 + y1 * t, y1);
        }
    }

    // R7 -----------------------------------------------------------------------------------------------
    private static void prefilter() {
        Prefilter f = new Prefilter();
        f.reset(0);
        double h = HeliPhysics.H, prev = 0, prevRate = 0, maxJerkFreeStep = 0;
        for (int i = 0; i < 120 * 20; i++) {
            double target = i < 120 ? 0 : 10; // a 10 m step in the parameter
            double y = f.step(target, 1.0, h, -4.5, 8.0);
            assert Math.abs(y - prev) <= 8.0 * h + 1e-12 : "R7: filtered value stepped";
            assert f.rate() <= 8.0 + 1e-12 && f.rate() >= -4.5 - 1e-12 : "R7: rate limit broken";
            maxJerkFreeStep = Math.max(maxJerkFreeStep, Math.abs(f.rate() - prevRate));
            prev = y;
            prevRate = f.rate();
        }
        assert Math.abs(prev - 10) < 0.01 : "R7: did not converge, at " + prev;
        assert maxJerkFreeStep <= 1.0 * 1.0 * 10 * h + 1e-9 : "R7: rate is not continuous";
    }
}
