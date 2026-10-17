package com.neoalive.tacz_sewv.compat;

import net.minecraft.world.entity.LivingEntity;

/**
 * Combined third-party-enemy target-priority classification for
 * {@link com.neoalive.tacz_sewv.entity.ai.goal.SoftEnemyTargetPriorityGoal}. Spore
 * ({@link SporeCompat}) and Phayriosis Two ({@link PhayriosisCompat}) are detected and
 * classified completely independently of each other — each mod's tier still applies with the
 * other absent, and neither compat class ever throws when its own mod is missing.
 */
public final class SoftEnemyTargeting {

    private SoftEnemyTargeting() {}

    /**
     * Ordinal order IS priority order (lowest → highest): a plain vanilla {@code MONSTER} (or
     * anything neither compat recognises) is {@link #NONE}; Spore's {@code infected} and any
     * Phayriosis mob are a slight nudge above that at {@link #MINOR}; Spore's {@code organoid}
     * / {@code experiments} — "important targets that define a gameplay" — outrank both at
     * {@link #PRIME}.
     */
    public enum Tier {
        NONE,
        MINOR,
        PRIME
    }

    /** Cheap early-out so the priority goal never scans when neither mod is installed. */
    public static boolean anyPresent() {
        return SporeCompat.present() || PhayriosisCompat.present();
    }

    public static Tier classify(LivingEntity target) {
        Tier spore = SporeCompat.tierOf(target);
        if (spore == Tier.PRIME) return Tier.PRIME;
        Tier phayriosis = PhayriosisCompat.tierOf(target);
        return phayriosis.ordinal() > spore.ordinal() ? phayriosis : spore;
    }
}
