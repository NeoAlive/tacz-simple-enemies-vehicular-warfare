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
        doctrineOwnsTemperament();
        infantryKnobs();

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
        close(1.0, DoctrineKnobs.stalemateScale(n), "neutral stalemate");
        close(1.0, DoctrineKnobs.supportCooldownScale(n), "neutral support cooldown");
        close(1.0, DoctrineKnobs.rankBias(n, true), "neutral armour bias");
        close(1.0, DoctrineKnobs.rankBias(n, false), "neutral infantry bias");
        assert !DoctrineKnobs.smokeOnHit(n) : "neutral smoke on hit";
        assert !DoctrineKnobs.creepsOnCourse(n) : "neutral creeps";
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
        assert DoctrineKnobs.stalemateScale(only(Axis.MANEUVER, lim)) < 1.0 : "maneuver repositions sooner";
        assert DoctrineKnobs.supportCooldownScale(only(Axis.SUPPORT_RELIANCE, lim)) < 1.0 : "support calls sooner";
        assert DoctrineKnobs.rankBias(only(Axis.TARGET_PRIORITY, lim), true) < 1.0 : "armour first reads armour nearer";
        assert DoctrineKnobs.rankBias(only(Axis.TARGET_PRIORITY, -lim), false) < 1.0 : "infantry first reads infantry nearer";
        assert DoctrineKnobs.smokeOnHit(only(Axis.PRESERVATION, lim)) : "preservation screens when hit";
        assert DoctrineKnobs.creepsOnCourse(only(Axis.RISK_TOLERANCE, -lim)) : "risk-averse crews creep";
        assert !DoctrineKnobs.creepsOnCourse(only(Axis.RISK_TOLERANCE, lim)) : "bold crews never slow";
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

    /**
     * The hull seed varies crews of one doctrine but cannot override it: a full-lean doctrine puts every hull in its
     * own temperament band, whatever the seed. (Neutral variety is pinned by BattlePositionSelfCheck.)
     */
    private static void doctrineOwnsTemperament() {
        assert Doctrine.CAUTION_LEAN > Doctrine.CAUTION_SPREAD : "spread outweighs doctrine";
        Doctrine careful = Doctrine.ofAxes(new int[] {-5, 5, 0, 0, 0, 0, 0, -5});
        Doctrine bold = Doctrine.ofAxes(new int[] {5, -5, 0, 0, 0, 0, 0, 5});
        for (long seed = 0; seed < 4000; seed++) {
            long s = seed * 0x9E3779B97F4A7C15L;
            assert Doctrine.Temperament.of(Doctrine.caution(careful, s)) == Doctrine.Temperament.CAUTIOUS
                    : "a full-caution hull read " + Doctrine.caution(careful, s);
            assert Doctrine.Temperament.of(Doctrine.caution(bold, s)) == Doctrine.Temperament.BOLD
                    : "a full-bold hull read " + Doctrine.caution(bold, s);
            double c = Doctrine.caution(Doctrine.NEUTRAL, s);
            assert Math.abs(c - 0.5) <= Doctrine.CAUTION_SPREAD + 1e-9 : "spread escaped its band: " + c;
        }
    }

    /** Neutral leaves SEM's numbers alone; the axes move them the right way and never out of 0..1. */
    private static void infantryKnobs() {
        Doctrine n = Doctrine.NEUTRAL;
        close(0.4f, DoctrineKnobs.infantryConfidence(n, 0.4f), "neutral confidence");
        close(0.4f, DoctrineKnobs.infantryPressure(n, 0.4f), "neutral pressure");
        assert DoctrineKnobs.infantryMayAdvance(n) : "neutral may advance";
        Doctrine bold = Doctrine.ofAxes(new int[] {5, -5, 0, 5, 0, 0, 0, 5});
        Doctrine careful = Doctrine.ofAxes(new int[] {-5, 5, 0, -5, 0, 0, 0, -5});
        assert DoctrineKnobs.infantryConfidence(bold, 0.4f) > 0.4f : "bold riflemen are not emboldened";
        assert DoctrineKnobs.infantryConfidence(careful, 0.4f) < 0.4f : "careful riflemen are not steadier";
        assert DoctrineKnobs.infantryPressure(careful, 0.4f) > 0.4f : "careful riflemen shrug off fire";
        assert DoctrineKnobs.infantryPressure(bold, 0.4f) < 0.4f : "bold riflemen feel fire more";
        assert !DoctrineKnobs.infantryMayAdvance(careful) : "a passive doctrine still advances";
        for (float v = 0f; v <= 1f; v += 0.05f) {
            for (Doctrine d : new Doctrine[] {bold, careful}) {
                float c = DoctrineKnobs.infantryConfidence(d, v);
                float p = DoctrineKnobs.infantryPressure(d, v);
                assert c >= 0f && c <= 1f && p >= 0f && p <= 1f : "infantry knob left 0..1 at " + v;
            }
        }
    }

    static void close(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1e-9 : what + ": expected " + expected + " got " + actual;
    }
}
