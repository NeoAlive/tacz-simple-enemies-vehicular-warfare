package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * Per-engagement firing rhythm, Steve's Army's {@code FirePersonality} made deterministic: the AI
 * interval between shots is stretched by a factor hashed from (shooter, target), so two guns on the
 * same target, or one gun on two targets, stop firing in lockstep. Re-rolled by a target change, like
 * SA's; never persisted, never random, so client, server and a repeat run agree.
 */
public final class FireRhythm {

    /** Interval multiplier range: [{@code MIN}, {@code MIN + SPAN}). Never faster than the weapon. */
    static final double MIN = 1.0;
    static final double SPAN = 0.25;

    private FireRhythm() {}

    public static int stretch(int intervalTicks, int shooterId, int targetId) {
        if (intervalTicks <= 0) return intervalTicks;
        double h = (((shooterId * 31 + targetId) * 0x9E3779B9) >>> 16) / 65536.0;
        return (int) Math.max(intervalTicks, Math.round(intervalTicks * (MIN + SPAN * h)));
    }
}
