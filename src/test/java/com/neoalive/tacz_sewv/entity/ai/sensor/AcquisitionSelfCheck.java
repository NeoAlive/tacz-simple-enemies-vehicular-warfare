package com.neoalive.tacz_sewv.entity.ai.sensor;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;

import com.neoalive.tacz_sewv.entity.ai.sensor.Acquisition.Motion;
import com.neoalive.tacz_sewv.entity.ai.support.FireRhythmProbe;
import com.neoalive.tacz_sewv.entity.ai.suppression.InfantryArmorDoctrine;

/**
 * Self-check for target acquisition, the infantry armour doctrine and the fire rhythm: delays are
 * monotonic in what they claim to depend on, bounded, and bypassed where they must be.
 * Run via {@code ./gradlew selfCheckAcquisition}.
 */
public final class AcquisitionSelfCheck {

    private static final int B = Acquisition.INFANTRY_BASE_TICKS;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        bypasses();
        monotonic();
        bounded();
        armorDoctrine();
        FireRhythmProbe.check();

        System.out.println("acquisition self-check: OK");
    }

    private static int d(double dist, Motion m, int light, boolean hull, boolean fired, double stress) {
        return Acquisition.delayTicks(B, dist, m, light, hull, fired, stress, 0.5);
    }

    private static void bypasses() {
        assert d(4.0, Motion.SNEAK, 0, false, false, 1.0) == 0 : "point blank is instant";
        assert Acquisition.delayTicks(0, 60, Motion.SNEAK, 0, false, false, 1.0, 0.5) == 0 : "zero base disables";
        assert d(40, Motion.WALK, 15, false, true, 0.0) < d(40, Motion.WALK, 15, false, false, 0.0)
                : "a shooter gives itself away";
        assert d(40, Motion.WALK, 15, true, false, 0.0) < d(40, Motion.WALK, 15, false, false, 0.0)
                : "a hull is easy to see";
    }

    private static void monotonic() {
        assert d(64, Motion.WALK, 15, false, false, 0) > d(24, Motion.WALK, 15, false, false, 0) : "range";
        assert d(16, Motion.WALK, 15, false, false, 0) == d(10, Motion.WALK, 15, false, false, 0)
                : "range only matters past the clear ring";
        assert d(30, Motion.WALK, 0, false, false, 0) > d(30, Motion.WALK, 15, false, false, 0) : "darkness";
        assert d(30, Motion.SNEAK, 15, false, false, 0) > d(30, Motion.STILL, 15, false, false, 0) : "sneak > still";
        assert d(30, Motion.STILL, 15, false, false, 0) > d(30, Motion.WALK, 15, false, false, 0) : "still > walk";
        assert d(30, Motion.WALK, 15, false, false, 0) > d(30, Motion.SPRINT, 15, false, false, 0) : "walk > sprint";
        assert d(30, Motion.WALK, 15, false, false, 1.0) > d(30, Motion.WALK, 15, false, false, 0) : "stress slows";
    }

    private static void bounded() {
        int worst = Acquisition.delayTicks(200, 500, Motion.SNEAK, 0, false, false, 1.0, 1.0);
        assert worst == Acquisition.MAX_TICKS : "capped, so noticing is slowed and never prevented: " + worst;
        // Plain view, close, daylight: well under a second.
        assert d(12, Motion.WALK, 15, false, false, 0) <= 20;
    }

    private static void armorDoctrine() {
        assert InfantryArmorDoctrine.tier(EngineType.TRACK, false) == 1.0f : "a tank is the full tier";
        assert InfantryArmorDoctrine.tier(EngineType.TRACK, true) < 1.0f;
        assert InfantryArmorDoctrine.tier(EngineType.WHEEL, false) < InfantryArmorDoctrine.tier(EngineType.WHEEL, true);
        assert InfantryArmorDoctrine.tier(EngineType.EMPTY, false) == 0.0f;
        assert InfantryArmorDoctrine.tier(null, false) == 0.0f;
        assert InfantryArmorDoctrine.riflemanPressure(0.3f, 1.0f) > 0.3f;
        assert InfantryArmorDoctrine.riflemanPressure(0.9f, 1.0f) == 1.0f : "clamped";
        assert InfantryArmorDoctrine.riflemanConfidence(0.5f, 1.0f) < 0.5f;
        assert InfantryArmorDoctrine.riflemanConfidence(0.1f, 1.0f) == 0.0f : "clamped";
        assert InfantryArmorDoctrine.gunnerConfidence(0.5f, 1.0f) > 0.5f : "a launcher closes in";
        assert InfantryArmorDoctrine.riflemanPressure(0.3f, 0.0f) == 0.3f : "no hull, no change";
    }
}
