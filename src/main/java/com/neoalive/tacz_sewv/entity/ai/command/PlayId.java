package com.neoalive.tacz_sewv.entity.ai.command;

import javax.annotation.Nullable;

/**
 * Stable play registry — ordinal is the play-weight row index. New play = new constant + weights
 * row (mirrors {@code Action}).
 */
public enum PlayId {
    FRONTAL_FIX_AND_FLANK("frontal_fix_and_flank"),
    DOUBLE_ENVELOPMENT("double_envelopment"),
    BOUNDING_OVERWATCH_ADVANCE("bounding_overwatch_advance"),
    FIGHTING_WITHDRAWAL("fighting_withdrawal"),
    HOLD_DEFEND("hold_defend");

    public static final PlayId[] VALUES = values();
    public static final String KEY_PREFIX = "play.";

    public final String key;

    PlayId(String key) {
        this.key = key;
    }

    /** Full datapack key, e.g. {@code play.hold_defend}. */
    public String fullKey() {
        return KEY_PREFIX + this.key;
    }

    @Nullable
    public static PlayId byKey(String suffix) {
        for (PlayId p : VALUES) {
            if (p.key.equals(suffix)) return p;
        }
        return null;
    }

    @Nullable
    public static PlayId byFullKey(String full) {
        if (full == null || !full.startsWith(KEY_PREFIX)) return null;
        return byKey(full.substring(KEY_PREFIX.length()));
    }
}
