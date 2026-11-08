package com.neoalive.tacz_sewv.crew;

/**
 * One hull's radio: the clip playing now plus up to {@link #SLOTS} waiting lines, never two of the
 * same group across the three. Waiting lines are ordered by tier, oldest first within a tier, and
 * expire when they have waited too long to still mean anything. Each clip waits for the previous one
 * to end plus a gap set by its own tier. Holds no Minecraft types and allocates nothing after
 * construction.
 */
public final class RadioQueue {

    public static final int SOFT = 0;
    public static final int HARD = 1;
    public static final int URGENT = 2;

    static final int SLOTS = 2;

    private static final int[] GAP = {25, 10, 2};
    // Counted from when the line was queued; each must outlast at least one clip ahead plus its gap.
    private static final int[] MAX_WAIT = {80, 100, 120};

    /** What the hull does with a line the queue lets through. */
    public interface Sink {
        /** False holds the line in the queue, for the local airtime gate. */
        boolean ready(Object ctx, int tier);

        /** Plays the line and returns how many ticks the clip lasts. */
        int play(Object ctx, int line, Object payload);
    }

    private long busyUntil = Long.MIN_VALUE;
    private int playingGroup = -1;
    private int count;
    private final int[] line = new int[SLOTS];
    private final int[] group = new int[SLOTS];
    private final int[] tier = new int[SLOTS];
    private final long[] expires = new long[SLOTS];
    private final Object[] payload = new Object[SLOTS];

    /** Owner's hull domain, cached by the caller; -1 until resolved. */
    public int domain = -1;

    public boolean isEmpty() {
        return count == 0;
    }

    public static int gap(int tier) {
        return GAP[tier];
    }

    public static int maxWait(int tier) {
        return MAX_WAIT[tier];
    }

    /** @return true when the line was played or queued */
    public boolean offer(long now, int lineId, int groupId, int tierId, Object data, Object ctx, Sink sink) {
        if (now < busyUntil && groupId == playingGroup) return false;
        for (int i = 0; i < count; i++) {
            if (group[i] != groupId) continue;
            if (tierId <= tier[i]) return false;
            remove(i);
            insert(now, lineId, groupId, tierId, data);
            return true;
        }
        if (count == 0 && now >= idleAt(tierId) && sink.ready(ctx, tierId)) {
            start(now, lineId, groupId, data, ctx, sink);
            return true;
        }
        if (count == SLOTS) {
            int victim = -1;
            for (int i = 0; i < count; i++) {
                if (victim < 0 || tier[i] < tier[victim]) victim = i;
            }
            if (tierId <= tier[victim]) return false;
            remove(victim);
        }
        insert(now, lineId, groupId, tierId, data);
        return true;
    }

    /** Called every hull tick while something is waiting. */
    public void drain(long now, Object ctx, Sink sink) {
        for (int i = count - 1; i >= 0; i--) {
            if (now >= expires[i]) remove(i);
        }
        if (count == 0 || now < idleAt(tier[0]) || !sink.ready(ctx, tier[0])) return;
        int l = line[0];
        int g = group[0];
        Object d = payload[0];
        remove(0);
        start(now, l, g, d, ctx, sink);
    }

    public void clear() {
        for (int i = 0; i < count; i++) payload[i] = null;
        count = 0;
    }

    private long idleAt(int tierId) {
        return busyUntil == Long.MIN_VALUE ? Long.MIN_VALUE : busyUntil + GAP[tierId];
    }

    private void start(long now, int l, int g, Object d, Object ctx, Sink sink) {
        busyUntil = now + Math.max(1, sink.play(ctx, l, d));
        playingGroup = g;
    }

    private void insert(long now, int l, int g, int t, Object d) {
        int at = count;
        while (at > 0 && tier[at - 1] < t) at--;
        for (int i = count; i > at; i--) {
            line[i] = line[i - 1];
            group[i] = group[i - 1];
            tier[i] = tier[i - 1];
            expires[i] = expires[i - 1];
            payload[i] = payload[i - 1];
        }
        line[at] = l;
        group[at] = g;
        tier[at] = t;
        expires[at] = now + MAX_WAIT[t];
        payload[at] = d;
        count++;
    }

    private void remove(int at) {
        for (int i = at; i < count - 1; i++) {
            line[i] = line[i + 1];
            group[i] = group[i + 1];
            tier[i] = tier[i + 1];
            expires[i] = expires[i + 1];
            payload[i] = payload[i + 1];
        }
        count--;
        payload[count] = null;
    }

    // Read by the self-check.
    int size() {
        return count;
    }

    int lineAt(int i) {
        return line[i];
    }

    long busyUntil() {
        return busyUntil;
    }
}
