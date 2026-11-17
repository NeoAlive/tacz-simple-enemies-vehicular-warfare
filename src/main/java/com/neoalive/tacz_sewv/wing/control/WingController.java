package com.neoalive.tacz_sewv.wing.control;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import com.neoalive.tacz_sewv.heli.physics.McPose;
import com.neoalive.tacz_sewv.wing.guidance.WingReference;
import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.Propulsion;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingControl;
import com.neoalive.tacz_sewv.wing.physics.WingEnv;
import com.neoalive.tacz_sewv.wing.physics.WingPhysics;
import com.neoalive.tacz_sewv.wing.physics.WingState;

/**
 * Cascaded fixed-wing flight controller, run every sub-step. The only writer of the stick,
 * throttle and applied-brake fields of {@link WingControl}.
 *
 * <pre>
 * FLYPATH:
 *   lateral   L1 on the reference line (+ curvature feedforward) -> lateral accel -> bank phi
 *   longit.   TECS: total energy rate error  -> throttle  (drag/climb feedforward + PI)
 *                   energy balance rate error -> flight-path angle gamma (feedforward + PI)
 *             theta = gamma + alpha needed for the load factor (inverted lift curve)
 * NOSE_AIM:   boresight error in the body frame -> pitch / yaw rate, bank-to-turn for the lateral
 *             error; throttle holds speed
 * attitude -> body-rate commands (+ coordinated-turn feedforward, sideslip feedback on the rudder)
 * protections on the rate commands: alpha limit, load-factor limits, rate limits; bank is capped by
 *             the lift available, bankMax and the turn-rate limit
 * rate loops: nonlinear dynamic inversion of the same AeroModel the physics flies, so the gains
 *             schedule with dynamic pressure by construction -> elevator / aileron / rudder
 * </pre>
 *
 * Saturations live here, with limits from the airframe and never from config. Integrators use
 * back-calculation anti-windup, as {@code HeliController} does. Gains come from the airframe's
 * bandwidths ({@code control.*}), so classes are tuned in natural-frequency terms.
 */
public final class WingController {

    /** Climb and descent angles the energy loops may ask for at most (rad). */
    private static final double GAMMA_CLIMB_MAX = StrictMath.toRadians(30.0), GAMMA_DESCENT_MAX = StrictMath.toRadians(30.0);
    /** Below this multiple of the stall speed, pitch stops trading for height and only guards speed. */
    private static final double UNDERSPEED = 1.2;
    /** Speed-command floor as a multiple of the stall speed. */
    private static final double SPEED_FLOOR = 1.25;
    /** Floor on the dynamic pressure the inversion divides by, as a fraction of 1 g stall q. */
    private static final double QBAR_FLOOR = 0.5;
    /** Speed-derivative filter time constant, s. */
    private static final double VDOT_TAU = 0.1;
    /** Minimum L1 distance, m. */
    private static final double L1_MIN = 20.0;
    /** Bank per radian of lateral boresight error, NOSE_AIM. */
    private static final double AIM_BANK_GAIN = 2.5;
    /** Roll attitude bandwidth as a multiple of {@code control.att} (roll may be a little brisker than pitch). */
    public static final double ROLL_ATT = 1.2;
    /**
     * Integrator bounds: gamma in rad, throttle as a fraction. Belt and braces under the
     * back-calculation, so no transient can leave a large integrator behind.
     */
    private static final double GAMMA_I_MAX = 0.25, THROTTLE_I_MAX = 0.4;
    private static final double H = WingPhysics.H;

    private final WingAirframe af;
    private final double g, rho, vStall, qbarFloor, aVdot;
    private final double kE, kEi, kB, kBi;
    /**
     * Back-calculation tracking time constant, s: the integrator bleeds (saturated - unsaturated) / tt
     * per second. Continuous, not a per-step fraction: a per-step fraction is a 60/s bleed at 120 Hz,
     * which drives the integrator to cancel a fast-moving proportional term and leaves it pointing the
     * wrong way the moment that term relaxes (seen as a dive commanded during terrain recovery).
     */
    private final double tt;

    // Memory.
    private double thrI, gamI, vPrev = Double.NaN, vDotF;

    /** Back-calculation on/off. Only a self-check turns it off, to prove the check bites. */
    public boolean antiWindup = true;

    // Diagnostics of the last step, for self-checks and telemetry.
    public double phiCmd, thetaCmd, gammaCmd, speedCmd;
    public boolean alphaLimited, nLimited, underspeed, throttleSaturated;

    private final Quaterniond qInv = new Quaterniond();
    private final Vector3d va = new Vector3d();
    private final Vector3d tmp = new Vector3d();

    public WingController(WingAirframe af, double g, double rho) {
        this.af = af;
        this.g = g;
        this.rho = rho;
        this.vStall = AeroModel.stallSpeed(af, g, rho, 0.0);
        this.qbarFloor = QBAR_FLOOR * 0.5 * rho * vStall * vStall;
        this.aVdot = 1.0 - StrictMath.exp(-H / VDOT_TAU);
        this.kE = 1.0;
        this.kEi = 0.5 * af.bwTecs;
        this.kB = 1.0;
        this.kBi = 0.3 * af.bwTecs;
        this.tt = 1.0 / af.bwTecs;
    }

    public double stallSpeed() {
        return vStall;
    }

    public void reset() {
        thrI = gamI = 0.0;
        vPrev = Double.NaN;
        vDotF = 0.0;
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    public void step(WingReference r, WingState s, WingEnv env, WingControl u) {
        // --- Measurements -----------------------------------------------------------------------
        McPose.Euler e = McPose.toEuler(s.q, null);
        double theta = -StrictMath.toRadians(e.pitchDeg());
        double phi = StrictMath.toRadians(e.rollDeg());
        qInv.set(s.q).conjugate();
        qInv.transform(tmp.set(s.v).sub(env.wind()), va);
        double vAir = Math.max(va.length(), 0.1);
        double alpha = StrictMath.atan2(-va.y, va.z);
        double beta = StrictMath.asin(clamp(-va.x / vAir, -1.0, 1.0));
        double airY = s.v.y - env.wind().y();
        double gamma = StrictMath.asin(clamp(airY / vAir, -1.0, 1.0));
        double hdot = s.v.y;
        if (Double.isNaN(vPrev)) vPrev = vAir;
        vDotF += aVdot * ((vAir - vPrev) / H - vDotF);
        vPrev = vAir;
        double p = s.w.z, q = -s.w.x, rr = -s.w.y;
        double qbar = 0.5 * rho * vAir * vAir;
        double qbarE = Math.max(qbar, qbarFloor);
        double weight = af.mass * g;
        double tMax = Math.max(Propulsion.maxThrust(af, vAir), 1.0);

        // The bank the wing can actually support at this speed (no more than nMax, nor alphaMax).
        double nAvail = Math.min(af.nMax, AeroModel.cl(af, af.alphaMax, s.flap) * qbar * af.area / weight);
        double bankLimit = nAvail > 1.0 ? Math.min(af.bankMax, StrictMath.acos(1.0 / nAvail)) : 0.0;
        bankLimit = Math.min(bankLimit, StrictMath.atan(af.turnRateMax * vAir / g));
        underspeed = vAir < UNDERSPEED * vStall;
        if (underspeed) bankLimit = Math.min(bankLimit, 0.5 * af.bankMax);

        double vRef = r.v().length();
        double pCmd, qCmd, rCmd;
        double hdotDem, vdotDem;
        double wh, wv;

        if (r.mode() == WingReference.Mode.NOSE_AIM && r.aim() != null) {
            // --- Boresight tracking ---------------------------------------------------------------
            Vector3d ab = qInv.transform(new Vector3d(r.aim()), new Vector3d());
            double epsUp = StrictMath.atan2(ab.y, ab.z);
            double epsRight = StrictMath.atan2(-ab.x, ab.z);
            phiCmd = clamp(AIM_BANK_GAIN * epsRight, -bankLimit, bankLimit);
            double psiDot = g * StrictMath.tan(phi) / vAir;
            pCmd = af.bwAtt * ROLL_ATT * (phiCmd - phi);
            qCmd = af.bwAtt * epsUp + psiDot * StrictMath.sin(phi);
            rCmd = af.bwAtt * epsRight * StrictMath.cos(phi) + psiDot * StrictMath.cos(phi);
            speedCmd = Math.max(vRef, SPEED_FLOOR * vStall);
            hdotDem = hdot; // pitch is the aim's: throttle only holds speed
            vdotDem = clamp(af.bwTecs * (speedCmd - vAir), -0.3 * g, 0.3 * g);
            wh = 0.0;
            wv = 1.0;
            gammaCmd = gamma;
            thetaCmd = theta + epsUp;
        } else {
            // --- Lateral: L1 on the reference line ------------------------------------------------
            double tx = r.v().x(), tz = r.v().z();
            double tn = StrictMath.sqrt(tx * tx + tz * tz);
            double gx = s.v.x, gz = s.v.z;
            double vg = StrictMath.sqrt(gx * gx + gz * gz);
            if (tn < 1.0) { // no horizontal reference velocity: hold the current track
                tx = vg > 1.0 ? gx : -StrictMath.sin(StrictMath.toRadians(e.yawDeg()));
                tz = vg > 1.0 ? gz : StrictMath.cos(StrictMath.toRadians(e.yawDeg()));
                tn = StrictMath.sqrt(tx * tx + tz * tz);
            }
            tx /= tn;
            tz /= tn;
            double dx = s.p.x - r.p().x(), dz = s.p.z - r.p().z();
            double along = dx * tx + dz * tz;
            double rx = -tz, rz = tx; // starboard of the reference track
            double xtrack = dx * rx + dz * rz;
            double l1 = Math.max(L1_MIN, af.l1Period * af.l1Zeta * Math.max(vg, vStall) / Math.PI);
            double ahead = Math.abs(xtrack) < l1 ? StrictMath.sqrt(l1 * l1 - xtrack * xtrack) : 0.0;
            // Closest point on the line is ref + t * along; aim L1 ahead of it along the line.
            double aimX = r.p().x() + tx * (along + ahead);
            double aimZ = r.p().z() + tz * (along + ahead);
            double wx = aimX - s.p.x, wz = aimZ - s.p.z;
            double hx = vg > 1.0 ? gx / vg : tx, hz = vg > 1.0 ? gz / vg : tz;
            double eta = StrictMath.atan2((-hz) * wx + hx * wz, hx * wx + hz * wz);
            eta = clamp(eta, -0.5 * Math.PI, 0.5 * Math.PI);
            double vgEff = Math.max(vg, vStall);
            double aLat = 2.0 * vgEff * vgEff * StrictMath.sin(eta) / l1;
            // Curvature feedforward, only near the path and while tracking along it. Far off a
            // circle, the L1 term (turn back) and the circle's own centripetal term point opposite
            // ways and cancel exactly at max L1 authority: the aircraft then flies straight away
            // forever, level, with both terms "working".
            double ffWeight = Math.max(0.0, 1.0 - Math.abs(xtrack) / l1) * Math.max(0.0, hx * tx + hz * tz);
            aLat += ffWeight * (r.a().x() * rx + r.a().z() * rz);
            phiCmd = clamp(StrictMath.atan(aLat / g), -bankLimit, bankLimit);

            // --- Longitudinal targets ---------------------------------------------------------------
            double base = Math.max(vRef, SPEED_FLOOR * vStall);
            speedCmd = clamp(base - af.bwTecs * 0.5 * along, SPEED_FLOOR * vStall, af.vMax);
            if (base > 1.0) speedCmd = clamp(speedCmd, 0.75 * base, 1.25 * base);
            speedCmd = clamp(speedCmd, SPEED_FLOOR * vStall, af.vMax);
            double drag = qbar * af.area * dragCoeff(s, weight, qbar, gamma, phi);
            double sinClimb = clamp((tMax - drag) / weight, 0.02, StrictMath.sin(GAMMA_CLIMB_MAX));
            hdotDem = clamp(r.v().y() + af.bwTecs * (r.p().y() - s.p.y),
                    -vAir * StrictMath.sin(GAMMA_DESCENT_MAX), vAir * sinClimb);
            vdotDem = clamp(af.bwTecs * (speedCmd - vAir), -0.3 * g, 0.3 * g);
            wh = underspeed ? 0.0 : 1.0;
            wv = underspeed ? 2.0 : 1.0;

            // --- Pitch: energy balance ---------------------------------------------------------------
            double bDem = wh * g * hdotDem - wv * vAir * vdotDem;
            double bNow = wh * g * hdot - wv * vAir * vDotF;
            double gv = g * vAir;
            double gUnsat = bDem / gv + kB * (bDem - bNow) / gv + gamI;
            double gSat = clamp(gUnsat, -GAMMA_DESCENT_MAX, StrictMath.asin(sinClimb) + StrictMath.toRadians(5.0));
            gamI += H * kBi * (bDem - bNow) / gv;
            if (antiWindup) gamI = clamp(gamI + H * (gSat - gUnsat) / tt, -GAMMA_I_MAX, GAMMA_I_MAX);
            gammaCmd = gSat;

            // Flight path through load factor, not through Euler pitch: in a bank, pitch attitude
            // moves only through q cos(phi) and stops being the thing that controls height (a 56
            // degree orbit hunted +-7 m commanding 28 degrees of pitch it could not get). Instead:
            // gammaDot = (g / V)(n cos(phi) - cos(gamma)), solved for the n that closes gamma.
            double cosPhi = Math.max(StrictMath.cos(phi), 0.2);
            double gammaDot = af.bwAtt * (gammaCmd - gamma);
            double nCmd = (StrictMath.cos(gamma) + vAir * gammaDot / g) / cosPhi;
            nCmd = clamp(nCmd, af.nMin, Math.max(1.0, nAvail));
            // Alpha for that load factor (inverted attached lift curve).
            double clNeed = nCmd * weight / (qbarE * af.area);
            double alphaCmd = (clNeed - af.cl0 - af.flapCl * s.flap) / af.clAlpha;
            alphaLimited = alphaCmd > af.alphaMax;
            alphaCmd = clamp(alphaCmd, -0.6 * af.alphaMax, af.alphaMax);
            thetaCmd = gammaCmd + alphaCmd;

            double psiDot = g * StrictMath.tan(phi) / vAir;
            pCmd = af.bwAtt * ROLL_ATT * (phiCmd - phi);
            // The pitch rate that load factor needs in coordinated flight, plus closing alpha onto it
            // (the short-period loop).
            qCmd = g / vAir * (nCmd - StrictMath.cos(gamma) * StrictMath.cos(phi)) + 2.0 * af.bwAtt * (alphaCmd - alpha);
            rCmd = psiDot * StrictMath.cos(phi) * StrictMath.cos(theta);
        }

        // Sideslip: yaw the nose into the relative wind (beta > 0 = wind from starboard).
        rCmd += af.yawDamper * af.bwAtt * beta;

        // --- Throttle: total energy ---------------------------------------------------------------
        double eDem = g * hdotDem + vAir * vdotDem;
        double eNow = g * hdot + vAir * vDotF;
        double drag = qbar * af.area * dragCoeff(s, weight, qbar, gamma, phi);
        double tReq = drag + weight * clamp(hdotDem / vAir, -1.0, 1.0) + af.mass * vdotDem;
        double perE = af.mass / (vAir * tMax);
        double thrUnsat = tReq / tMax + kE * perE * (eDem - eNow) + thrI;
        double thr = clamp(thrUnsat, 0.0, 1.0);
        thrI += H * kEi * perE * (eDem - eNow);
        if (antiWindup) thrI = clamp(thrI + H * (thr - thrUnsat) / tt, -THROTTLE_I_MAX, THROTTLE_I_MAX);
        throttleSaturated = thr != thrUnsat;
        u.throttle = thr;
        // Overspeed: the airbrake, once the throttle is already closed and still too fast; in a
        // nose-aimed dive as soon as it runs over the run speed (dive brakes: gravity, not the
        // engine, is what accelerates a dive, so waiting for the throttle to close is too late).
        boolean dive = r.mode() == WingReference.Mode.NOSE_AIM;
        double over = Math.max(vAir - af.vMax, thr <= 0.0 || dive ? vAir - speedCmd - (dive ? 1.0 : 2.0) : 0.0);
        u.brake = Math.max(u.brakeCmd, clamp(over / 6.0, 0.0, 1.0));

        // --- Protections on the rate commands -----------------------------------------------------
        double alphaFloor = -0.6 * af.alphaMax;
        qCmd = Math.min(qCmd, 2.0 * af.bwAtt * (af.alphaMax - alpha));
        qCmd = Math.max(qCmd, 2.0 * af.bwAtt * (alphaFloor - alpha));
        nLimited = false;
        if (s.nz > af.nMax) {
            qCmd = Math.min(qCmd, q - af.bwAtt * (s.nz - af.nMax) * g / vAir);
            nLimited = true;
        } else if (s.nz < af.nMin) {
            qCmd = Math.max(qCmd, q + af.bwAtt * (af.nMin - s.nz) * g / vAir);
            nLimited = true;
        }
        pCmd = clamp(pCmd, -af.rateRoll, af.rateRoll);
        qCmd = clamp(qCmd, -af.ratePitch, af.ratePitch);
        rCmd = clamp(rCmd, -af.rateYaw, af.rateYaw);

        // --- Rate loops: dynamic inversion --------------------------------------------------------
        double vn = Math.max(vAir, 3.0);
        double pHat = p * af.span / (2.0 * vn), qHat = q * af.chord / (2.0 * vn), rHat = rr * af.span / (2.0 * vn);
        double qsb = qbarE * af.area * af.span, qsc = qbarE * af.area * af.chord;
        double auth = s.degraded ? af.degradedAuthority : 1.0;
        double alphaM = clamp(alpha, -0.5 * Math.PI, 0.5 * Math.PI);

        double mRoll = af.iz * af.bwRate * (pCmd - p) + af.restDamping * af.iz * p;
        double mPitch = af.ix * af.bwRate * (qCmd - q) + af.restDamping * af.ix * q;
        double mYaw = af.iy * af.bwRate * (rCmd - rr) + af.restDamping * af.iy * rr;
        u.aileron = clamp((mRoll / qsb - af.clBeta * beta - af.clp * pHat) / af.clDa / auth, -1.0, 1.0);
        u.elevator = clamp((mPitch / qsc - af.cm0 - af.cmAlpha * alphaM - af.cmq * qHat) / af.cmDe / auth, -1.0, 1.0);
        u.rudder = clamp((mYaw / qsb - af.cnBeta * beta - af.cnr * rHat) / af.cnDr / auth, -1.0, 1.0);
    }

    /** Drag coefficient estimate for the current configuration at the lift a turn/climb needs. */
    private double dragCoeff(WingState s, double weight, double qbar, double gamma, double phi) {
        double cl = StrictMath.cos(gamma) * weight / (Math.max(qbar, qbarFloor) * af.area * Math.max(StrictMath.cos(phi), 0.2));
        return af.cd0 + af.inducedK * cl * cl + af.flapCd * s.flap + af.gearCd * s.gear + af.brakeCd * s.brake;
    }
}
