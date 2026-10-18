package com.neoalive.tacz_sewv.util;

import java.util.function.LongConsumer;

/**
 * The decisions behind {@link ChunkTicket}, kept free of Minecraft types so
 * {@code ChunkTicketPolicySelfCheck} can pin them headless.
 *
 * <p>Two independent savings, both bounded by one hard constraint: a Forge ticking ticket is
 * level 31, and only the forced chunk itself is entity-ticking (its neighbours are 32, block
 * ticking only). So the owner's own chunk must always be forced or entity-ticked by a player,
 * which is why neither of these is a "hold a 3x3 and move it less often" scheme — an owner that
 * stepped into an unforced neighbour would stop ticking and could never move its ticket again.
 */
public final class ChunkTicketPolicy {

    private ChunkTicketPolicy() {}

    /**
     * Vanilla entity-ticks chunks up to {@code simulationDistance - 2} from a player (its player
     * ticket sits at level {@code 33 - simulationDistance}; entity ticking needs 31 or lower).
     */
    public static int entityTickingRadius(int simulationDistance) {
        return Math.max(0, simulationDistance - 2);
    }

    /**
     * Whether a ticket should be suspended, given whether it is now and the Chebyshev chunk
     * distance to the nearest player.
     *
     * <p>Hysteresis inside the entity-ticking radius {@code R}: suspend once the owner is at
     * least {@code releaseMargin} chunks inside it ({@code d <= R - releaseMargin}), resume once
     * it is within {@code acquireMargin} of the edge ({@code d > R - acquireMargin}), and keep
     * the current state in between. {@code acquireMargin >= 1} guarantees a resume happens while
     * the chunk is still entity-ticked, so the handover never leaves a gap; the release margin is
     * clamped above it so the band can never collapse into flapping.
     */
    public static boolean nextSuspended(
            boolean suspended, int nearestPlayerChunks, int simulationDistance,
            int releaseMargin, int acquireMargin) {
        int r = entityTickingRadius(simulationDistance);
        int acquire = Math.max(1, acquireMargin);
        int release = Math.max(acquire + 1, releaseMargin);
        if (nearestPlayerChunks < 0) return false; // no player in this level
        if (suspended) return nearestPlayerChunks <= r - acquire;
        return nearestPlayerChunks <= r - release;
    }

    /** Chebyshev distance in chunks. */
    public static int chunkDistance(int ax, int az, int bx, int bz) {
        return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
    }

    /**
     * Chunks recently left, kept forced until a game-time deadline so an owner that re-crosses
     * the same boundary (an orbit, a racetrack hold) re-adopts them instead of paying for a fresh
     * load and an unload-time save each pass. Chunks are {@code ChunkPos.toLong()} keys.
     */
    public static final class Linger {

        private long[] chunks = new long[0];
        private long[] deadlines = new long[0];
        private int size;

        public int size() {
            return this.size;
        }

        /** Takes {@code chunk} back out if it is lingering; true when it was. */
        public boolean adopt(long chunk) {
            for (int i = 0; i < this.size; i++) {
                if (this.chunks[i] == chunk) {
                    removeAt(i);
                    return true;
                }
            }
            return false;
        }

        /**
         * Starts {@code chunk} lingering until {@code deadline}. With {@code cap <= 0} it is
         * released at once; at the cap the oldest lingering chunk is released to make room.
         */
        public void push(long chunk, long deadline, int cap, LongConsumer release) {
            if (cap <= 0) {
                release.accept(chunk);
                return;
            }
            while (this.size >= cap) {
                release.accept(this.chunks[0]);
                removeAt(0);
            }
            if (this.chunks.length < cap) {
                this.chunks = java.util.Arrays.copyOf(this.chunks, cap);
                this.deadlines = java.util.Arrays.copyOf(this.deadlines, cap);
            }
            this.chunks[this.size] = chunk;
            this.deadlines[this.size] = deadline;
            this.size++;
        }

        /** Releases every chunk whose deadline has passed; returns how many. */
        public int expire(long now, LongConsumer release) {
            int expired = 0;
            for (int i = 0; i < this.size; ) {
                if (now >= this.deadlines[i]) {
                    release.accept(this.chunks[i]);
                    removeAt(i);
                    expired++;
                } else {
                    i++;
                }
            }
            return expired;
        }

        public void releaseAll(LongConsumer release) {
            for (int i = 0; i < this.size; i++) release.accept(this.chunks[i]);
            this.size = 0;
        }

        // Insertion order is age order, which push() relies on to evict the oldest.
        private void removeAt(int i) {
            int tail = this.size - i - 1;
            if (tail > 0) {
                System.arraycopy(this.chunks, i + 1, this.chunks, i, tail);
                System.arraycopy(this.deadlines, i + 1, this.deadlines, i, tail);
            }
            this.size--;
        }
    }
}
