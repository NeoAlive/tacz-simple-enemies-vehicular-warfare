package com.neoalive.tacz_sewv.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of the chunk-ticket coverage hysteresis and linger list.
 * Run via {@code ./gradlew selfCheckChunkTickets}.
 */
public final class ChunkTicketPolicySelfCheck {

    public static void main(String[] args) {
        radius();
        band();
        neverFlaps();
        resumesWhileStillTicking();
        clampedMargins();
        noPlayers();
        lingerAdoptAndExpire();
        lingerCap();
        lingerOff();
        System.out.println("ChunkTicketPolicySelfCheck OK");
    }

    private static void radius() {
        assert ChunkTicketPolicy.entityTickingRadius(12) == 10;
        assert ChunkTicketPolicy.entityTickingRadius(1) == 0 : "never negative";
    }

    /** sim 12 -> R 10, release 4, acquire 2: suspend at <= 6, resume at > 8. */
    private static void band() {
        assert ChunkTicketPolicy.nextSuspended(false, 6, 12, 4, 2) : "deep inside suspends";
        assert !ChunkTicketPolicy.nextSuspended(false, 7, 12, 4, 2) : "inside the band, not yet";
        assert ChunkTicketPolicy.nextSuspended(true, 8, 12, 4, 2) : "inside the band, stays suspended";
        assert !ChunkTicketPolicy.nextSuspended(true, 9, 12, 4, 2) : "near the edge resumes";
    }

    /** Walking out and back one chunk at a time switches exactly once each way. */
    private static void neverFlaps() {
        boolean s = false;
        int switches = 0;
        int[] walk = {0, 2, 4, 6, 7, 8, 7, 8, 9, 10, 9, 8, 7, 6, 5};
        for (int d : walk) {
            boolean next = ChunkTicketPolicy.nextSuspended(s, d, 12, 4, 2);
            if (next != s) switches++;
            s = next;
        }
        assert switches == 3 : "suspend, resume, suspend; got " + switches;
    }

    /** A resume must fire while the chunk is still inside the entity-ticking radius. */
    private static void resumesWhileStillTicking() {
        for (int sim = 3; sim <= 32; sim++) {
            int r = ChunkTicketPolicy.entityTickingRadius(sim);
            assert !ChunkTicketPolicy.nextSuspended(true, r, sim, 4, 2)
                    : "at the very edge (sim " + sim + ") a suspended ticket must already be back";
        }
    }

    private static void clampedMargins() {
        // acquire 0 is clamped to 1; release <= acquire is clamped to acquire + 1.
        assert !ChunkTicketPolicy.nextSuspended(true, 10, 12, 1, 0) : "edge always resumes";
        assert ChunkTicketPolicy.nextSuspended(false, 8, 12, 1, 1) : "release clamped to 2 -> <= 8";
        assert !ChunkTicketPolicy.nextSuspended(false, 9, 12, 1, 1);
    }

    private static void noPlayers() {
        assert !ChunkTicketPolicy.nextSuspended(true, -1, 12, 4, 2) : "nobody in the level: hold it";
    }

    private static void lingerAdoptAndExpire() {
        ChunkTicketPolicy.Linger l = new ChunkTicketPolicy.Linger();
        List<Long> released = new ArrayList<>();
        l.push(1L, 100, 2, released::add);
        assert l.adopt(1L) : "a lingering chunk is re-adopted";
        assert !l.adopt(1L) : "...only once";
        assert released.isEmpty() : "adopting releases nothing";

        l.push(2L, 100, 2, released::add);
        assert l.expire(99, released::add) == 0;
        assert l.expire(100, released::add) == 1 : "expires at its deadline";
        assert released.equals(List.of(2L));
        assert l.size() == 0;
    }

    private static void lingerCap() {
        ChunkTicketPolicy.Linger l = new ChunkTicketPolicy.Linger();
        List<Long> released = new ArrayList<>();
        l.push(1L, 100, 2, released::add);
        l.push(2L, 100, 2, released::add);
        l.push(3L, 100, 2, released::add);
        assert released.equals(List.of(1L)) : "the oldest is evicted at the cap";
        l.releaseAll(released::add);
        assert released.equals(List.of(1L, 2L, 3L));
        assert l.size() == 0;
    }

    private static void lingerOff() {
        ChunkTicketPolicy.Linger l = new ChunkTicketPolicy.Linger();
        List<Long> released = new ArrayList<>();
        l.push(5L, 100, 0, released::add);
        assert released.equals(List.of(5L)) : "cap 0 releases immediately";
        assert l.size() == 0;
    }
}
