package com.neoalive.tacz_sewv.entity.ai.support;
import com.neoalive.tacz_sewv.entity.ai.goal.DriveHelicopterGoal;

/**
 * Headless check for guided heli standoff geometry. Run via {@code ./gradlew selfCheckHeli}.
 *
 * <p>Asserts that {@link DriveHelicopterGoal#guidedStandoffRing} keeps the implied nose
 * depression ≤ the configured max on flat / hill / valley height-above-target cases, and that
 * the min-standoff floor holds when height collapses (peak).
 */
public final class HeliStandoffSelfCheck {

    private static final double MAX_DEP = 45.0;
    private static final double MIN_STANDOFF = 28.0;
    private static final double EPS_DEG = 0.05;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        // Flat: cruise 40 above target → ring = 40/tan(45) = 40 → depression = 45°
        assertDepressionOk(40.0, "flat");

        // Valley: target lower → larger height → larger ring, depression still at max
        assertDepressionOk(70.0, "valley");

        // Hill: target higher, small positive height → ring may hit min floor; depression ≤ max
        assertDepressionOk(10.0, "hill");

        // Peak / target at or above cruise: height ≤ 0 → floor only
        double peakRing = DriveHelicopterGoal.guidedStandoffRing(0.0, MAX_DEP, MIN_STANDOFF);
        assert peakRing == MIN_STANDOFF : "peak height=0 → min standoff, got " + peakRing;
        double belowRing = DriveHelicopterGoal.guidedStandoffRing(-20.0, MAX_DEP, MIN_STANDOFF);
        assert belowRing == MIN_STANDOFF : "target above hold → min standoff, got " + belowRing;

        // Floor wins over a tiny height that would otherwise collapse inside knife range
        double tiny = DriveHelicopterGoal.guidedStandoffRing(1.0, MAX_DEP, MIN_STANDOFF);
        assert tiny == MIN_STANDOFF : "tiny height must floor at min standoff, got " + tiny;

        // Stage-1 rappel: RAPPEL is a committed phase but not a firing-run racetrack phase.
        assert DriveHelicopterGoal.RunPhase.valueOf("RAPPEL") == DriveHelicopterGoal.RunPhase.RAPPEL;
        assert !DriveHelicopterGoal.isFiringRunPhase(DriveHelicopterGoal.RunPhase.RAPPEL);
        assert !DriveHelicopterGoal.isFiringRunPhase(DriveHelicopterGoal.RunPhase.IDLE);
        assert DriveHelicopterGoal.isFiringRunPhase(DriveHelicopterGoal.RunPhase.ATTACK);

        RappelSupport.selfCheck();

        System.out.println("heli standoff self-check: OK");
    }

    private static void assertDepressionOk(double heightAboveTarget, String label) {
        double ring = DriveHelicopterGoal.guidedStandoffRing(heightAboveTarget, MAX_DEP, MIN_STANDOFF);
        assert ring >= MIN_STANDOFF : label + " ring below min: " + ring;
        double dep = Math.toDegrees(Math.atan2(Math.max(heightAboveTarget, 0.0), ring));
        assert dep <= MAX_DEP + EPS_DEG
                : label + " depression " + dep + "° exceeds max " + MAX_DEP + "° (ring=" + ring + ")";
    }
}
