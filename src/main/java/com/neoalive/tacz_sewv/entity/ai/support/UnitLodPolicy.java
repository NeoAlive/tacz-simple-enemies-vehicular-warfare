package com.neoalive.tacz_sewv.entity.ai.support;

/**
 * The decisions behind {@link UnitLod}, free of Minecraft types so {@code UnitLodPolicySelfCheck}
 * can pin them headless.
 *
 * <p>Vanilla runs a mob's full goal selection only when {@code (serverTickCount + id)} is even and
 * on odd ticks ticks just the {@code requiresUpdateEveryTick} goals. A throttled unit evaluates when
 * {@code (serverTickCount + id) % stride == 0} with an EVEN stride, so every evaluation it keeps is
 * one vanilla would have made anyway, and every tick it skips becomes an ordinary odd tick. Phasing
 * on the id staggers units across the window, so a throttled army never evaluates on one tick.
 */
public final class UnitLodPolicy {

    public static final byte FULL = 0;
    public static final byte MID = 1;
    public static final byte FAR = 2;

    /** Vanilla's own selection stride. */
    public static final int VANILLA_STRIDE = 2;
    public static final int MAX_STRIDE = 16;

    private UnitLodPolicy() {}

    /**
     * @param nearestPlayerDistSq horizontal distance² to the nearest player, ignored when none
     * @param near   FULL inside this many blocks
     * @param far    MID inside this many blocks, FAR beyond; clamped to at least {@code near}
     * @param exempt the unit must never be throttled
     */
    public static byte tierFor(double nearestPlayerDistSq, double near, double far, boolean exempt, boolean hasPlayer) {
        if (exempt || !hasPlayer) return FULL;
        double f = Math.max(near, far);
        if (nearestPlayerDistSq < near * near) return FULL;
        if (nearestPlayerDistSq < f * f) return MID;
        return FAR;
    }

    /** Rounds up to even and clamps to [2, 16], so the vanilla-parity invariant cannot be configured away. */
    public static int normaliseStride(int stride) {
        int s = Math.max(VANILLA_STRIDE, Math.min(MAX_STRIDE, stride));
        return (s & 1) == 0 ? s : s + 1;
    }

    /** Stride for a tier; FAR is never finer than MID. Both inputs already normalised. */
    public static int strideFor(byte tier, int midStride, int farStride) {
        return switch (tier) {
            case MID -> midStride;
            case FAR -> Math.max(midStride, farStride);
            default -> VANILLA_STRIDE;
        };
    }

    public static boolean due(int serverTick, int id, int stride) {
        return Math.floorMod(serverTick + id, stride) == 0;
    }
}
