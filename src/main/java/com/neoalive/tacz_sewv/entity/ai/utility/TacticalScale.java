package com.neoalive.tacz_sewv.entity.ai.utility;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * {@code tacticalScale}: one multiplier for the command tier's geometry and the ground crews' engagement rings.
 * A multiplier rather than new defaults, because a changed default never reaches an existing toml.
 * Unreadable config (headless self-checks, early boot) is the neutral 1.0.
 */
public final class TacticalScale {

    private TacticalScale() {}

    public static double get() {
        try {
            return SewvConfig.TACTICAL_SCALE.get();
        } catch (Throwable unbaked) {
            return 1.0;
        }
    }

    public static double of(double base) {
        return base * get();
    }
}
