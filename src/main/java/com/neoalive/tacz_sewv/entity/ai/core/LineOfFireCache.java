package com.neoalive.tacz_sewv.entity.ai.core;

/**
 * The AI fire gate's line-of-fire verdicts for one hull, one slot per seat.
 *
 * <p>SBW's AI fire loop asks {@code canShoot} for every Mob seat on every tick, so on a hull
 * with more than one AI gunner (a BMP-2's firing ports, a tank's commander MG) consecutive calls
 * come from different seats. A single shared slot was overwritten by each of them in turn and
 * never hit; the full verdict (occupancy, allied line, block clips, smoke query) ran on every
 * call. Direct-mapping by seat index means seats never evict each other.
 *
 * <p>A slot is valid for {@code ttl} game ticks and only for the same shooter and the same
 * target, so a crew that swaps seats or retargets inside the window never inherits a stale
 * verdict. A seat outside the slot range is simply not cached, which is always correct.
 * Kept free of Minecraft types so {@code LineOfFireCacheSelfCheck} can run it headless.
 */
public final class LineOfFireCache {

    public static final int MISS = 0;
    public static final int HIT_CLEAR = 1;
    public static final int HIT_BLOCKED = 2;

    /** Largest seat count seen in shipped hulls is 11 (fcp:bmp1). */
    static final int SLOTS = 16;

    // Allocated on first store: most hulls never host an AI gunner.
    private long[] tick;
    private int[] shooter;
    private int[] target;
    private boolean[] blocked;

    public int lookup(int seat, int shooterId, int targetId, long now, int ttl) {
        if (this.tick == null || seat < 0 || seat >= SLOTS) return MISS;
        if (this.tick[seat] == Long.MIN_VALUE || now - this.tick[seat] >= ttl) return MISS;
        if (this.shooter[seat] != shooterId || this.target[seat] != targetId) return MISS;
        return this.blocked[seat] ? HIT_BLOCKED : HIT_CLEAR;
    }

    public void store(int seat, int shooterId, int targetId, long now, boolean isBlocked) {
        if (seat < 0 || seat >= SLOTS) return;
        if (this.tick == null) {
            this.tick = new long[SLOTS];
            java.util.Arrays.fill(this.tick, Long.MIN_VALUE);
            this.shooter = new int[SLOTS];
            this.target = new int[SLOTS];
            this.blocked = new boolean[SLOTS];
        }
        this.tick[seat] = now;
        this.shooter[seat] = shooterId;
        this.target[seat] = targetId;
        this.blocked[seat] = isBlocked;
    }
}
