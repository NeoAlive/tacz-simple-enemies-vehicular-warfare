package com.neoalive.tacz_sewv.entity.ai.core;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.level.Level;

import com.neoalive.tacz_sewv.config.SewvConfig;

/**
 * Per-level cap on ground-vehicle path searches per game tick ({@code pathSearchesPerTick}).
 *
 * <p>One search costs ~5 ms (512 nodes over a wide hull's footprint), and nothing else stops a dozen hulls
 * from all going stale in the same tick. The cap turns that into a queue: a hull refused a slot keeps
 * following the route it has and asks again next tick. Two rules keep it fair and bounded:
 * <ul>
 * <li>a hull that has waited {@code pathMaxWaitTicks} searches regardless of the cap, so none starves;</li>
 * <li>a low-priority hull ({@code groundFarLodBlocks}: idle and far from every player) may only use the
 *     lower half of the slots and waits twice as long before it may override.</li>
 * </ul>
 * The counter resets when the level's game time moves (including backwards), never on a modulo.
 */
public final class PathBudget {

    private static final class Slot {
        long tick = Long.MIN_VALUE;
        int used;
    }

    /** Weak on the level so an unloaded dimension's slot goes with it. Server thread only. */
    private static final Map<Level, Slot> SLOTS = new WeakHashMap<>();

    private PathBudget() {}

    /**
     * May a search run now? Pure decision, headless-testable.
     *
     * @param used          searches already granted on this level this tick
     * @param limit         {@code pathSearchesPerTick}; 0 = unlimited
     * @param waited        ticks this hull has been refused so far (0 = first ask)
     * @param maxWait       {@code pathMaxWaitTicks}
     * @param lowPriority   idle and far from every player
     */
    static boolean decide(int used, int limit, long waited, int maxWait, boolean lowPriority) {
        if (limit <= 0) return true;
        long bound = lowPriority ? 2L * maxWait : maxWait;
        if (waited >= bound) return true;
        int slots = lowPriority ? limit / 2 : limit;
        return used < slots;
    }

    /**
     * Ask for a search slot. {@code waitingSince} is the game time of this hull's first refused request, or
     * {@link Long#MIN_VALUE} if it has not been refused. On true the slot is counted.
     */
    public static boolean tryAcquire(Level level, long waitingSince, boolean lowPriority) {
        int limit;
        int maxWait;
        try {
            limit = SewvConfig.PATH_SEARCHES_PER_TICK.get();
            maxWait = SewvConfig.PATH_MAX_WAIT_TICKS.get();
        } catch (Throwable unbaked) {
            return true;
        }
        if (limit <= 0) return true;
        long now = level.getGameTime();
        Slot slot = SLOTS.computeIfAbsent(level, l -> new Slot());
        if (slot.tick != now) {
            slot.tick = now;
            slot.used = 0;
        }
        long waited = waitingSince == Long.MIN_VALUE || waitingSince > now ? 0 : now - waitingSince;
        if (!decide(slot.used, limit, waited, maxWait, lowPriority)) return false;
        slot.used++;
        return true;
    }
}
