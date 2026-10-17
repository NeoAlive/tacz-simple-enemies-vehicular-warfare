package com.neoalive.tacz_sewv.heli.guidance;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.HeliPhysics;

/**
 * The target as the attack procedures see it: a smooth (C1, bounded acceleration) estimate built
 * from the 20 Hz snapshots, with the ghost of plan section 4.8 when the target is lost.
 *
 * <p>Between snapshots the target is extrapolated linearly from the last one. A new snapshot (a
 * velocity change, a correction) would step that extrapolation, so the step is absorbed into a
 * residual that decays through a critically damped second-order filter: the output is
 * {@code raw(t) + e(t)}, continuous in position and velocity across every snapshot. A
 * constant-velocity target leaves the residual at exactly zero, so it is tracked without lag.
 *
 * <p><b>Ghost.</b> With no valid snapshot the last one keeps being extrapolated for
 * {@link #GRACE} seconds (the old {@code RUN_GATE_GRACE_TICKS}, 40), then freezes where it got to;
 * the freeze is absorbed like any other snapshot change. {@link #lost} turns true at that moment.
 */
final class TargetTrack {

    /** Ghost duration after the last valid snapshot, s. */
    static final double GRACE = 2.0;
    private static final double OMEGA = 1.0, H = HeliPhysics.H;

    private boolean known, frozen;
    private int id = -1;
    private double snapTime = Double.NaN, lastSeen = Double.NaN, sitTime = Double.NaN;
    private final Vector3d p0 = new Vector3d(), v0 = new Vector3d();
    private final Vector3d e = new Vector3d(), ed = new Vector3d(), edd = new Vector3d();
    private double clock = Double.NaN;

    /** Fold in the snapshot (once per snapshot; repeated calls with the same one are free). */
    void update(Situation sit) {
        if (sit.time == sitTime) return;
        sitTime = sit.time;
        if (sit.targetValid) {
            replace(sit.targetX, sit.targetY, sit.targetZ, sit.targetVx, sit.targetVy, sit.targetVz, sit.time);
            id = sit.targetId;
            lastSeen = sit.time;
            frozen = false;
        } else if (known && !frozen && sit.time - lastSeen > GRACE) {
            Vector3d at = raw(lastSeen + GRACE, new Vector3d());
            replace(at.x, at.y, at.z, 0, 0, 0, sit.time);
            frozen = true;
        }
    }

    private void replace(double x, double y, double z, double vx, double vy, double vz, double t) {
        if (known) {
            advance(t);
            Vector3d oldP = raw(clock, new Vector3d()), oldV = new Vector3d(v0);
            p0.set(x, y, z);
            v0.set(vx, vy, vz);
            snapTime = t;
            e.add(oldP.sub(raw(clock, new Vector3d())));
            ed.add(oldV.sub(v0));
        } else {
            p0.set(x, y, z);
            v0.set(vx, vy, vz);
            snapTime = t;
            clock = t;
            known = true;
        }
    }

    private Vector3d raw(double t, Vector3d out) {
        return out.set(v0).mul(t - snapTime).add(p0);
    }

    private void advance(double t) {
        while (clock + 0.5 * H < t) {
            edd.set(e).mul(-OMEGA * OMEGA).fma(-2.0 * OMEGA, ed);
            ed.fma(H, edd);
            e.fma(H, ed);
            clock += H;
        }
    }

    boolean known() {
        return known;
    }

    int id() {
        return id;
    }

    /** The ghost has run out: no valid snapshot for more than {@link #GRACE} seconds. */
    boolean lost() {
        return !known || frozen;
    }

    Vector3d p(double t) {
        advance(t);
        return raw(t, new Vector3d()).add(e);
    }

    Vector3d v(double t) {
        advance(t);
        return new Vector3d(v0).add(ed);
    }

    Vector3d a(double t) {
        advance(t);
        return new Vector3d(edd);
    }
}
