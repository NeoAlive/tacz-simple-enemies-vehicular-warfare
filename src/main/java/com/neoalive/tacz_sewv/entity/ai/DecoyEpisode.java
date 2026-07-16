package com.neoalive.tacz_sewv.entity.ai;

import net.minecraft.util.RandomSource;

/**
 * The coin flip that decides whether one crew screens itself during one bout of damage —
 * smoke for a retreating ground crew, flares for a damaged airframe.
 *
 * <p>Both callers ask every tick while they are hurt, so the roll cannot happen at the
 * asking: the decoy input is a latch, and re-rolling would stutter it. The roll therefore
 * happens once per EPISODE, a run of consecutive ticks, and holds for the rest of it — so
 * about half of hurt crews screen and half don't, instead of every crew flickering.
 */
final class DecoyEpisode {

    private long lastTick = Long.MIN_VALUE;
    private boolean deploying;

    /**
     * Rolls if this tick starts a new episode, then answers whether this episode screens.
     *
     * @param now current game time; a gap in it marks a fresh episode
     */
    boolean roll(long now, RandomSource random, float chance) {
        // The sentinel is tested explicitly: now - Long.MIN_VALUE overflows negative and
        // would silently skip the roll for the first-ever episode.
        if (this.lastTick == Long.MIN_VALUE || now - this.lastTick > 1) {
            this.deploying = random.nextFloat() < chance;
        }
        this.lastTick = now;
        return this.deploying;
    }

    /** The standing decision, for callers that must release the latch on a tick they don't roll. */
    boolean isDeploying() {
        return this.deploying;
    }
}
