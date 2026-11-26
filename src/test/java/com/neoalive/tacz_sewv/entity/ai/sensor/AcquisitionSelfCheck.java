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
        losRing();
        deferredFilter();
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

    private static void losRing() {
        long[] r = SemLosCache.newRing();
        assert SemLosCache.find(r, 7, 100, 1) == -1 : "empty ring answers nothing";
        SemLosCache.put(r, 7, 100, true);
        assert SemLosCache.find(r, 7, 100, 1) == 1 : "same tick reuses";
        assert SemLosCache.find(r, 7, 101, 1) == -1 : "ticks=1 never crosses a tick";
        assert SemLosCache.find(r, 7, 102, 3) == 1 : "ticks=3 reuses two ticks later";
        assert SemLosCache.find(r, 7, 103, 3) == -1 : "and not three";
        assert SemLosCache.find(r, 7, 99, 3) == -1 : "game time going backwards is a miss";
        SemLosCache.put(r, 7, 104, false);
        assert SemLosCache.find(r, 7, 104, 1) == 0 : "a target keeps one slot, newest answer";
        for (int id = 20; id < 20 + SemLosCache.SLOTS; id++) SemLosCache.put(r, id, 105, true);
        assert SemLosCache.find(r, 7, 105, 5) == -1 : "four new targets push the oldest out";
        assert SemLosCache.find(r, 20, 105, 1) == 1 && SemLosCache.find(r, 23, 105, 1) == 1;
    }

    private static void deferredFilter() {
        java.util.Random rnd = new java.util.Random(1126);
        for (int round = 0; round < 200; round++) {
            int n = rnd.nextInt(60);
            java.util.List<Integer> base = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) base.add(rnd.nextInt(1000));
            int mod = 2 + rnd.nextInt(4);
            java.util.function.Predicate<Integer> p = v -> v % mod == 0;
            java.util.Comparator<Integer> byValue = Integer::compare;

            java.util.List<Integer> eager = new java.util.ArrayList<>();
            for (Integer v : base) if (p.test(v)) eager.add(v);
            eager.sort(byValue);

            com.neoalive.tacz_sewv.util.DeferredFilterList<Integer> lazy =
                    new com.neoalive.tacz_sewv.util.DeferredFilterList<>(new java.util.ArrayList<>(base), p);
            lazy.sort(byValue);
            java.util.List<Integer> walked = new java.util.ArrayList<>();
            for (Integer v : lazy) walked.add(v);
            assert walked.equals(eager) : "a full lazy walk equals the eager filter, round " + round;
            assert lazy.size() == eager.size() && (eager.isEmpty() || lazy.get(0).equals(eager.get(0)));

            int[] tests = {0};
            com.neoalive.tacz_sewv.util.DeferredFilterList<Integer> partial =
                    new com.neoalive.tacz_sewv.util.DeferredFilterList<>(new java.util.ArrayList<>(base), v -> {
                        tests[0]++;
                        return p.test(v);
                    });
            partial.sort(byValue);
            java.util.Iterator<Integer> it = partial.iterator();
            if (it.hasNext()) {
                assert it.next().equals(eager.get(0)) : "first match is the nearest passing one";
                java.util.List<Integer> sorted = new java.util.ArrayList<>(base);
                sorted.sort(byValue);
                int firstPass = 0;
                while (!p.test(sorted.get(firstPass))) firstPass++;
                int ahead = 1;
                while (firstPass + ahead < sorted.size() && !p.test(sorted.get(firstPass + ahead))) ahead++;
                assert tests[0] <= firstPass + ahead + 1 : "an early break tests only a prefix";
            }
            int before = tests[0];
            partial.sort(java.util.Comparator.reverseOrder());
            java.util.List<Integer> reversed = new java.util.ArrayList<>(eager);
            reversed.sort(java.util.Comparator.reverseOrder());
            java.util.List<Integer> again = new java.util.ArrayList<>();
            for (Integer v : partial) again.add(v);
            assert again.equals(reversed) : "sorting after a partial walk still answers the eager list";
            for (Integer v : partial) again.add(v);
            assert tests[0] <= before + n : "answers are memoised, a second walk does not test again";
        }
    }
}
