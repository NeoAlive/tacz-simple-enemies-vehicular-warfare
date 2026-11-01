package com.neoalive.tacz_sewv.wing;

import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.wing.control.WingCore;
import com.neoalive.tacz_sewv.wing.guidance.WingReference;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;

/** C1-C8: the controller cascade, closed loop. */
final class WingControlChecks {

    private WingControlChecks() {}

    static void run() {
        for (WingAirframe af : WingSim.table().classes().values()) {
            levelHold(af);
            altitudeStep(af);
            coordinatedTurn(af);
            saturatingTurn(af);
            stallProtection(af);
            noseAim(af);
            pathCapture(af);
            antiWindupBites(af);
        }
    }

    /** C1: perturbed start, straight-and-level reference: holds height, speed and track. */
    private static void levelHold(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed + 4.0, 0.0, 500.0, 0.0);
        sim.core.s.throttle = sim.core.u.throttle = 0.5;
        var ref = CtlSim.line(0.0, 500.0, 0.0, af.cruiseSpeed, Double.POSITIVE_INFINITY, 500.0);
        for (int i = 0; i < 25 * 20; i++) sim.tick(ref);
        double alt = 0.0, spd = 0.0, xt = 0.0;
        for (int i = 0; i < 60 * 20; i++) {
            sim.tick(ref);
            alt = Math.max(alt, Math.abs(sim.core.s.p.y - 500.0));
            spd = Math.max(spd, Math.abs(sim.speed() - af.cruiseSpeed));
            xt = Math.max(xt, Math.abs(sim.core.s.p.x));
        }
        assert alt < 2.0 : "C1: " + af.name + " altitude error " + alt;
        assert spd < 1.0 : "C1: " + af.name + " speed error " + spd;
        assert xt < 2.0 : "C1: " + af.name + " cross-track " + xt;
    }

    /** C2: +50 m step: captured without overshoot, never slow, never past vMax. */
    private static void altitudeStep(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 500.0, 0.0);
        var ref = CtlSim.line(0.0, 500.0, 0.0, af.cruiseSpeed, 5.0, 550.0);
        double minV = Double.POSITIVE_INFINITY, maxV = 0.0, peak = 0.0, captured = Double.NaN;
        for (int i = 0; i < 60 * 20; i++) {
            sim.tick(ref);
            minV = Math.min(minV, sim.speed());
            maxV = Math.max(maxV, sim.speed());
            peak = Math.max(peak, sim.core.s.p.y);
            if (Double.isNaN(captured) && Math.abs(sim.core.s.p.y - 550.0) < 3.0) captured = sim.t();
        }
        assert !Double.isNaN(captured) && captured < 45.0 : "C2: " + af.name + " captured +50 m at " + captured;
        assert peak - 550.0 < 8.0 : "C2: " + af.name + " overshot by " + (peak - 550.0);
        assert minV > 1.1 * sim.vs : "C2: " + af.name + " slowed to " + minV;
        assert maxV <= af.vMax : "C2: " + af.name + " reached " + maxV;
        assert Math.abs(sim.core.s.p.y - 550.0) < 2.0 : "C2: " + af.name + " settled at " + sim.core.s.p.y;
    }

    /** C3: a turn at 70 % of the bank limit: coordinated, on the circle, within the bank limit. */
    private static void coordinatedTurn(WingAirframe af) {
        double v = af.cruiseSpeed;
        double r = v * v / (WingSim.G * StrictMath.tan(0.7 * bankLimit(af, v)));
        CtlSim sim = new CtlSim(af).start(v, r, 500.0, 0.0);
        var ref = CtlSim.circle(0.0, 500.0, 0.0, r, v);
        for (int i = 0; i < 25 * 20; i++) sim.tick(ref);
        double beta = 0.0, radial = 0.0, bank = 0.0, alt = 0.0;
        for (int i = 0; i < 30 * 20; i++) {
            sim.tick(ref);
            beta = Math.max(beta, Math.abs(StrictMath.toDegrees(sim.core.s.beta)));
            radial = Math.max(radial, Math.abs(StrictMath.hypot(sim.core.s.p.x, sim.core.s.p.z) - r));
            bank = Math.max(bank, Math.abs(sim.bankDeg()));
            alt = Math.max(alt, Math.abs(sim.core.s.p.y - 500.0));
        }
        assert beta < 2.0 : "C3: " + af.name + " sideslip " + beta + " deg";
        assert radial < 0.1 * r + 5.0 : "C3: " + af.name + " radial error " + radial + " on r=" + r;
        assert bank < StrictMath.toDegrees(af.bankMax) + 2.0 : "C3: " + af.name + " bank " + bank;
        assert alt < 5.0 : "C3: " + af.name + " altitude error in the turn " + alt;
    }

    /** The bank the controller allows at {@code v}: bankMax, or less where the turn-rate limit binds. */
    private static double bankLimit(WingAirframe af, double v) {
        return Math.min(af.bankMax, StrictMath.atan(af.turnRateMax * v / WingSim.G));
    }

    /** C4: a circle far too tight to fly: the limits hold and it never stalls. */
    private static void saturatingTurn(WingAirframe af) {
        double v = af.cruiseSpeed;
        double r = 0.3 * v * v / (WingSim.G * StrictMath.tan(bankLimit(af, v)));
        CtlSim sim = new CtlSim(af).start(v, r, 500.0, 0.0);
        var ref = CtlSim.circle(0.0, 500.0, 0.0, r, v);
        double bank = 0.0, nz = 0.0, alpha = Double.NEGATIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 40 * 20; i++) {
            sim.tick(ref);
            assert sim.finite() : "C4: " + af.name + " went non-finite";
            if (i < 20) continue; // let the first sub-steps' nz observable settle
            bank = Math.max(bank, Math.abs(sim.bankDeg()));
            nz = Math.max(nz, sim.core.s.nz);
            alpha = Math.max(alpha, sim.core.s.alpha);
            minV = Math.min(minV, sim.speed());
        }
        assert bank < StrictMath.toDegrees(af.bankMax) + 3.0 : "C4: " + af.name + " bank " + bank;
        assert nz < af.nMax + 0.3 : "C4: " + af.name + " load factor " + nz;
        assert alpha < af.alphaStall : "C4: " + af.name + " alpha " + StrictMath.toDegrees(alpha);
        assert minV > sim.vs : "C4: " + af.name + " slowed to " + minV + " (stall " + sim.vs + ")";
    }

    /** C5: asked to climb 600 m while slow: alpha stays protected, speed stays above the stall. */
    private static void stallProtection(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(1.4 * new CtlSim(af).vs, 0.0, 500.0, 0.0);
        var ref = CtlSim.line(0.0, 500.0, 0.0, sim.vs, 0.0, 1100.0);
        double alpha = Double.NEGATIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 60 * 20; i++) {
            sim.tick(ref);
            alpha = Math.max(alpha, sim.core.s.alpha);
            minV = Math.min(minV, sim.speed());
        }
        assert alpha < af.alphaMax + StrictMath.toRadians(1.5) : "C5: " + af.name + " alpha " + StrictMath.toDegrees(alpha);
        assert minV > 1.05 * sim.vs : "C5: " + af.name + " slowed to " + minV + " (stall " + sim.vs + ")";
        assert sim.core.s.p.y > 520.0 : "C5: " + af.name + " must still climb, at " + sim.core.s.p.y;
    }

    /**
     * C6: 25 s holding cruise dirty (airbrake, gear, flaps) pins the throttle and starves the energy
     * loop; cleaning up must not then overshoot. With back-calculation off the wound-up integrator
     * keeps the throttle open and the speed overshoots several m/s, which is what proves the check bites.
     */
    private static void antiWindupBites(WingAirframe af) {
        double on = windupOvershoot(af, true), off = windupOvershoot(af, false);
        assert on < 1.5 : "C6: " + af.name + " overspeed with anti-windup " + on;
        assert off > on + 3.0 : "C6: " + af.name + " anti-windup must make a difference (on " + on + ", off " + off + ")";
    }

    private static double windupOvershoot(WingAirframe af, boolean aw) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 800.0, 0.0);
        sim.core.ctl.antiWindup = aw;
        double v = af.cruiseSpeed;
        WingCore.ReferenceSource ref = (t, s) -> WingReference.flypath(t, new Vector3d(0.0, 800.0, s.p.z + 1.0),
                new Vector3d(0.0, 0.0, v), new Vector3d());
        sim.core.u.brakeCmd = 1.0;
        sim.core.u.gearDown = true;
        sim.core.u.flap = 1.0;
        double over = 0.0;
        for (int i = 0; i < 70 * 20; i++) {
            if (sim.t() >= 25.0) {
                sim.core.u.brakeCmd = 0.0;
                sim.core.u.gearDown = false;
                sim.core.u.flap = 0.0;
            }
            sim.tick(ref);
            if (sim.t() > 25.0) over = Math.max(over, sim.speed() - v);
        }
        return over;
    }

    /** C7: NOSE_AIM puts the boresight on a fixed direction 8 deg right and 4 deg low. */
    private static void noseAim(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 2000.0, 0.0);
        Vector3d nose = sim.core.s.q.transform(new Vector3d(0, 0, 1));
        double yaw = StrictMath.toRadians(8.0), dip = StrictMath.toRadians(4.0);
        // Southbound: starboard is -X. Aim 8 deg right of and 4 deg below the current nose line.
        double pitch0 = StrictMath.asin(nose.y);
        double el = pitch0 - dip;
        Vector3d aim = new Vector3d(-StrictMath.sin(yaw) * StrictMath.cos(el), StrictMath.sin(el),
                StrictMath.cos(yaw) * StrictMath.cos(el)).normalize();
        WingCore.ReferenceSource ref = (t, s) -> WingReference.noseAim(t, s.p, new Vector3d(0, 0, af.cruiseSpeed), aim);
        double err = 180.0;
        for (int i = 0; i < 6 * 20; i++) {
            sim.tick(ref);
            Vector3d f = sim.core.s.q.transform(new Vector3d(0, 0, 1));
            err = StrictMath.toDegrees(StrictMath.acos(Math.max(-1.0, Math.min(1.0, f.dot(aim)))));
        }
        assert err < 1.0 : "C7: " + af.name + " boresight error " + err + " deg";
    }

    /** C8: capture a line 40 m to starboard without crossing it by much. */
    private static void pathCapture(WingAirframe af) {
        CtlSim sim = new CtlSim(af).start(af.cruiseSpeed, 0.0, 500.0, 0.0);
        var ref = CtlSim.line(-40.0, 500.0, 0.0, af.cruiseSpeed, Double.POSITIVE_INFINITY, 500.0);
        double cross = 0.0;
        for (int i = 0; i < 40 * 20; i++) {
            sim.tick(ref);
            cross = Math.max(cross, -40.0 - sim.core.s.p.x); // past the line to the west
        }
        double err = Math.abs(sim.core.s.p.x + 40.0);
        assert err < 2.0 : "C8: " + af.name + " still " + err + " m off the line";
        assert cross < 8.0 : "C8: " + af.name + " crossed the line by " + cross;
        double bank = Math.abs(McPose.toEuler(sim.core.s.q, null).rollDeg());
        assert bank < 3.0 : "C8: " + af.name + " still banked " + bank;
    }
}
