package com.neoalive.tacz_sewv.entity.ai.support;

/** Package-private access for {@code AcquisitionSelfCheck}. */
public final class FireRhythmProbe {

    private FireRhythmProbe() {}

    public static void check() {
        for (int shooter = 1; shooter < 200; shooter++) {
            for (int target = 1; target < 20; target++) {
                int t = FireRhythm.stretch(4, shooter, target);
                assert t >= 4 : "never faster than the weapon";
                assert t <= Math.round(4 * (FireRhythm.MIN + FireRhythm.SPAN)) : "bounded";
                assert FireRhythm.stretch(4, shooter, target) == t : "deterministic";
            }
        }
        assert FireRhythm.stretch(0, 5, 6) == 0;
        boolean differ = false;
        for (int s = 1; s < 50 && !differ; s++) {
            differ = FireRhythm.stretch(6, s, 7) != FireRhythm.stretch(6, s + 1, 7);
        }
        assert differ : "two guns on one target must not all share a rhythm";
    }
}
