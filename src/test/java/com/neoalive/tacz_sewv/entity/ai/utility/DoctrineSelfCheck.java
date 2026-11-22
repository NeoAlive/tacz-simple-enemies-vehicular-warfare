package com.neoalive.tacz_sewv.entity.ai.utility;

import com.neoalive.tacz_sewv.entity.ai.utility.Doctrine.Axis;

/**
 * Self-check for {@link DoctrineKnobs}. Run with {@code ./gradlew selfCheckDoctrine}.
 *
 * <p>Pins the two promises the knobs make: neutral is exactly the old behaviour, and every knob moves the way its
 * axis says, never past its clamp, with bail always under break-off.
 */
public final class DoctrineSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        neutralIsTodaysConstants();
        knobsMoveWithTheirAxis();
        bailStaysUnderBreakOffEverywhere();

        System.out.println("DoctrineSelfCheck OK");
    }

    static Doctrine only(Axis axis, int value) {
        int[] raw = new int[Axis.VALUES.length];
        raw[axis.ordinal()] = value;
        return Doctrine.ofAxes(raw);
    }

    private static void neutralIsTodaysConstants() {
        Doctrine n = Doctrine.NEUTRAL;
        close(1.0, DoctrineKnobs.ringScale(n), "neutral ring");
        close(0.25, DoctrineKnobs.breakOffHealth(n), "neutral break-off");
        close(0.15, DoctrineKnobs.bailHealth(n), "neutral bail");
        assert DoctrineKnobs.commitTicks(n) == 40 : "neutral commit";
        close(10.0, DoctrineKnobs.switchMargin(n), "neutral margin");
        close(1.0, DoctrineKnobs.acquisitionScale(n), "neutral acquisition");
        assert DoctrineKnobs.stalemateTicks(n) == 120 : "neutral stalemate";
        close(1.0, DoctrineKnobs.supportCooldownScale(n), "neutral support cooldown");
        close(1.0, DoctrineKnobs.rankBias(n, true), "neutral armour bias");
        close(1.0, DoctrineKnobs.rankBias(n, false), "neutral infantry bias");
        assert !DoctrineKnobs.smokeOnHit(n) : "neutral smoke on hit";
        close(1.0, DoctrineKnobs.courseSpeedCap(n), "neutral course speed");
    }

    private static void knobsMoveWithTheirAxis() {
        int lim = Doctrine.AXIS_LIMIT;
        assert DoctrineKnobs.ringScale(only(Axis.AGGRESSION, lim)) < 1.0 : "aggression closes the ring";
        assert DoctrineKnobs.ringScale(only(Axis.PRESERVATION, lim)) > 1.0 : "preservation opens the ring";
        assert DoctrineKnobs.breakOffHealth(only(Axis.PRESERVATION, lim)) > 0.25 : "preservation breaks off earlier";
        assert DoctrineKnobs.breakOffHealth(only(Axis.RISK_TOLERANCE, lim)) < 0.25 : "risk breaks off later";
        assert DoctrineKnobs.commitTicks(only(Axis.INITIATIVE, lim)) < 40 : "initiative re-plans sooner";
        assert DoctrineKnobs.switchMargin(only(Axis.INITIATIVE, lim)) < 10.0 : "initiative switches easier";
        assert DoctrineKnobs.acquisitionScale(only(Axis.INITIATIVE, lim)) < 1.0 : "initiative notices faster";
        assert DoctrineKnobs.stalemateTicks(only(Axis.MANEUVER, lim)) < 120 : "maneuver repositions sooner";
        assert DoctrineKnobs.supportCooldownScale(only(Axis.SUPPORT_RELIANCE, lim)) < 1.0 : "support calls sooner";
        assert DoctrineKnobs.rankBias(only(Axis.TARGET_PRIORITY, lim), true) < 1.0 : "armour first reads armour nearer";
        assert DoctrineKnobs.rankBias(only(Axis.TARGET_PRIORITY, -lim), false) < 1.0 : "infantry first reads infantry nearer";
        assert DoctrineKnobs.smokeOnHit(only(Axis.PRESERVATION, lim)) : "preservation screens when hit";
        assert DoctrineKnobs.courseSpeedCap(only(Axis.RISK_TOLERANCE, -lim)) < 1.0 : "risk-averse crews creep";
        assert DoctrineKnobs.courseSpeedCap(only(Axis.RISK_TOLERANCE, lim)) == 1.0 : "bold crews never slow";
    }

    /** Every corner of the axis cube at +-limit and zero: bail strictly under break-off, both inside their clamps. */
    private static void bailStaysUnderBreakOffEverywhere() {
        int n = Axis.VALUES.length;
        int[] levels = {-Doctrine.AXIS_LIMIT, 0, Doctrine.AXIS_LIMIT};
        int combos = (int) Math.pow(3, n);
        for (int c = 0; c < combos; c++) {
            int[] raw = new int[n];
            int x = c;
            for (int k = 0; k < n; k++) {
                raw[k] = levels[x % 3];
                x /= 3;
            }
            Doctrine d = Doctrine.ofAxes(raw);
            double brk = DoctrineKnobs.breakOffHealth(d);
            double bail = DoctrineKnobs.bailHealth(d);
            assert bail < brk : "bail " + bail + " not under break-off " + brk + " for " + d;
            assert brk >= 0.10 && brk <= 0.42 : "break-off out of range for " + d;
            assert bail > 0.0 : "bail must stay positive for " + d;
            double ring = DoctrineKnobs.ringScale(d);
            assert ring >= 0.65 && ring <= 1.35 : "ring out of range for " + d;
        }
    }

    static void close(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1e-9 : what + ": expected " + expected + " got " + actual;
    }
}
