package com.neoalive.tacz_sewv.wing;

import java.util.Map;

import com.neoalive.tacz_sewv.wing.physics.AeroModel;
import com.neoalive.tacz_sewv.wing.physics.WingAirframe;
import com.neoalive.tacz_sewv.wing.physics.WingTrim;

/**
 * Informational: what SBW's own {@code aircraftEngine} gives a player in each shipped hull, next
 * to what our class row gives the AI, so the gap is visible whenever a row is retuned. Prints only;
 * the realistic-per-class decision means the two are not required to match.
 *
 * <p>SBW's straight-and-level speed law (reference source, commit 993063bed): per tick
 * {@code v <- 1.04 v f + 0.047 power SpeedRate}, {@code f = clamp(0.96 - 0.0017 Resistance v^2, 0.01, 0.99)},
 * lift cancelling gravity. Power is capped at 1 in the air and 3 with sprint. Note the 1.04: the
 * velocity-steering term feeds back into speed, which is why SBW planes are faster than the bare
 * thrust/drag balance suggests. Its sustained yaw rate
 * is {@code YawSpeed x (0.3 + 3.2 x |roll| / 90)} deg/tick, quoted here at 60 degrees of bank.
 */
final class SbwEnvelope {

    private record Hull(String id, String cls, double speedRate, double resistance, double yawSpeed) {}

    private static final Hull[] HULLS = {
        new Hull("a_10a", "attack_jet", 1.0, 1.0, 1.0),
        new Hull("j_16", "fighter_jet", 1.0, 1.0, 1.0),
        new Hull("ju_87", "dive_bomber", 0.7, 1.2, 1.2),
        new Hull("kv_16", "light_prop", 0.6, 1.3, 1.2),
        new Hull("ac_130h", "gunship", 0.6, 1.0, 0.9),
    };

    private SbwEnvelope() {}

    static void print() {
        Map<String, WingAirframe> classes = WingSim.table().classes();
        System.out.println("  envelope (m/s, deg/s)   SBW top p1/p3   SBW turn60 | ours stall  top   turn@cruise");
        for (Hull h : HULLS) {
            WingAirframe af = classes.get(h.cls);
            double p1 = sbwTop(h, 1.0), p3 = sbwTop(h, 3.0);
            double turn = 20.0 * h.yawSpeed * (0.3 + 3.2 * 60.0 / 90.0);
            double vs = AeroModel.stallSpeed(af, WingSim.G, WingSim.RHO, 0.0);
            double top = WingTrim.topSpeed(af, WingSim.G, WingSim.RHO);
            System.out.printf("  %-8s %-12s %6.1f/%5.1f %8.1f   | %6.1f %6.1f %8.1f%n",
                    h.id, h.cls, p1, p3, turn, vs, top, ourTurn(af, vs));
        }
    }

    private static double sbwTop(Hull h, double power) {
        double v = 0.0;
        for (int i = 0; i < 20000; i++) {
            double f = Math.max(0.01, Math.min(0.99, 0.96 - 0.0017 * h.resistance * v * v));
            v = 1.04 * v * f + 0.047 * power * h.speedRate;
        }
        return v * 20.0;
    }

    /**
     * Turn rate at cruise, bounded by the lift available, by nMax, and by the turn-rate limit the
     * controller enforces through its bank cap.
     */
    private static double ourTurn(WingAirframe af, double vs) {
        double v = af.cruiseSpeed;
        double n = Math.min(af.nMax, (v / vs) * (v / vs));
        double aero = WingSim.G * StrictMath.sqrt(Math.max(0.0, n * n - 1.0)) / v;
        return StrictMath.toDegrees(Math.min(aero, af.turnRateMax));
    }
}
