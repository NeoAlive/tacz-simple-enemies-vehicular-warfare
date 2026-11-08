package com.neoalive.tacz_sewv.crew;

import java.util.ArrayList;
import java.util.List;

/** Pins the crew radio queue rules. Run with {@code ./gradlew selfCheckRadioQueue}. */
public final class RadioQueueSelfCheck {

    private static final int CLIP = 40;

    private static final class Rec implements RadioQueue.Sink {
        final List<Integer> played = new ArrayList<>();
        boolean airtime = true;

        @Override
        public boolean ready(Object ctx, int tier) {
            return tier != RadioQueue.SOFT || airtime;
        }

        @Override
        public int play(Object ctx, int line, Object payload) {
            played.add(line);
            return CLIP;
        }
    }

    public static void main(String[] args) {
        playsAtOnceWhenIdle();
        diversityAcrossSlots();
        sameGroupReplacesOnlyHigher();
        urgentJumpsFront();
        fullQueueEvictsOnlyLower();
        expiryPerTier();
        gapPerTier();
        softWaitsForAirtime();
        domainGating();
        System.out.println("radio queue self-check: OK");
    }

    private static void playsAtOnceWhenIdle() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        check(q.offer(0, 1, 1, RadioQueue.HARD, null, null, r), "idle offer accepted");
        check(r.played.equals(List.of(1)) && q.isEmpty(), "idle line plays at once");
        check(q.busyUntil() == CLIP, "busy for the clip length");
    }

    private static void diversityAcrossSlots() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        q.offer(0, 1, 1, RadioQueue.HARD, null, null, r);
        check(!q.offer(1, 2, 1, RadioQueue.URGENT, null, null, r), "group of the playing clip rejected");
        check(q.offer(1, 3, 2, RadioQueue.HARD, null, null, r), "other group queued");
        check(!q.offer(2, 4, 2, RadioQueue.HARD, null, null, r), "same pending group, same tier rejected");
        check(q.offer(2, 5, 3, RadioQueue.SOFT, null, null, r), "third group queued");
        check(q.size() == 2, "two waiting");
    }

    private static void sameGroupReplacesOnlyHigher() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        q.offer(0, 1, 1, RadioQueue.HARD, null, null, r);
        q.offer(1, 2, 2, RadioQueue.SOFT, null, null, r);
        check(!q.offer(1, 3, 2, RadioQueue.SOFT, null, null, r), "equal tier does not replace");
        check(q.offer(1, 4, 2, RadioQueue.URGENT, null, null, r), "higher tier replaces");
        check(q.size() == 1 && q.lineAt(0) == 4, "replacement took the slot");
    }

    private static void urgentJumpsFront() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        q.offer(0, 1, 1, RadioQueue.HARD, null, null, r);
        q.offer(1, 2, 2, RadioQueue.HARD, null, null, r);
        q.offer(1, 3, 3, RadioQueue.URGENT, null, null, r);
        check(q.lineAt(0) == 3 && q.lineAt(1) == 2, "urgent ahead of hard");
    }

    private static void fullQueueEvictsOnlyLower() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        q.offer(0, 1, 1, RadioQueue.HARD, null, null, r);
        q.offer(1, 2, 2, RadioQueue.HARD, null, null, r);
        q.offer(1, 3, 3, RadioQueue.SOFT, null, null, r);
        check(!q.offer(1, 4, 4, RadioQueue.SOFT, null, null, r), "full queue rejects an equal low tier");
        check(q.offer(1, 5, 5, RadioQueue.HARD, null, null, r), "full queue takes a higher tier");
        check(q.size() == 2 && q.lineAt(0) == 2 && q.lineAt(1) == 5, "soft line evicted");
    }

    private static void expiryPerTier() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        q.offer(0, 1, 1, RadioQueue.URGENT, null, null, r);
        r.airtime = false; // hold the soft line so only expiry can remove it
        q.offer(1, 2, 2, RadioQueue.SOFT, null, null, r);
        long at = 1 + RadioQueue.maxWait(RadioQueue.SOFT);
        q.drain(at - 1, null, r);
        check(q.size() == 1, "soft still waiting just before expiry");
        q.drain(at, null, r);
        check(q.isEmpty() && r.played.equals(List.of(1)), "soft expired unplayed");
    }

    private static void gapPerTier() {
        for (int tier : new int[] {RadioQueue.SOFT, RadioQueue.HARD, RadioQueue.URGENT}) {
            RadioQueue q = new RadioQueue();
            Rec r = new Rec();
            q.offer(0, 1, 1, RadioQueue.HARD, null, null, r);
            q.offer(0, 2, 2, tier, null, null, r);
            long due = CLIP + RadioQueue.gap(tier);
            q.drain(CLIP, null, r);
            check(r.played.size() == 1 || RadioQueue.gap(tier) == 0, "nothing plays while the clip runs");
            q.drain(due - 1, null, r);
            check(r.played.size() == 1, "gap respected for tier " + tier);
            q.drain(due, null, r);
            check(r.played.equals(List.of(1, 2)), "plays when the gap ends for tier " + tier);
        }
        for (int tier : new int[] {RadioQueue.SOFT, RadioQueue.HARD, RadioQueue.URGENT}) {
            // An average clip is about 46 ticks; a line behind one must survive it.
            check(RadioQueue.maxWait(tier) > 46 + RadioQueue.gap(tier), "tier " + tier + " outlasts one clip ahead");
        }
        check(RadioQueue.gap(RadioQueue.URGENT) < RadioQueue.gap(RadioQueue.HARD)
                && RadioQueue.gap(RadioQueue.HARD) < RadioQueue.gap(RadioQueue.SOFT), "urgent shortest gap");
    }

    private static void softWaitsForAirtime() {
        RadioQueue q = new RadioQueue();
        Rec r = new Rec();
        r.airtime = false;
        check(q.offer(0, 1, 1, RadioQueue.SOFT, null, null, r), "soft queued when airtime busy");
        check(r.played.isEmpty() && q.size() == 1, "soft held");
        r.airtime = true;
        q.drain(1, null, r);
        check(r.played.equals(List.of(1)), "soft plays once airtime frees");
    }

    private static void domainGating() {
        for (CrewRadio.Line l : CrewRadio.Line.values()) {
            boolean warning = l.name().startsWith("WARN_");
            for (CrewRadio.Domain d : CrewRadio.Domain.values()) {
                boolean b = CrewRadio.blocked(l, d);
                if (warning) check(b == (d != CrewRadio.Domain.AIR), l + " is a cockpit line, aircraft only");
            }
        }
        check(CrewRadio.blocked(CrewRadio.Line.DAMAGE_TRACK, CrewRadio.Domain.AIR), "no tracks in the air");
        check(CrewRadio.blocked(CrewRadio.Line.LOAD_AP, CrewRadio.Domain.AIR), "no loader in the air");
        check(CrewRadio.blocked(CrewRadio.Line.ENGINE_START, CrewRadio.Domain.FIXED), "emplacements start no engine");
        check(!CrewRadio.blocked(CrewRadio.Line.VEHICLE_HIT, CrewRadio.Domain.AIR), "aircraft still get hit lines");
        check(!CrewRadio.blocked(CrewRadio.Line.DAMAGE_TRACK, CrewRadio.Domain.GROUND), "ground keeps tracks");
        // A blocked line's fallback chain must not lead back to an allowed line on that domain.
        for (CrewRadio.Line l : CrewRadio.Line.values()) {
            for (CrewRadio.Domain d : CrewRadio.Domain.values()) {
                if (!CrewRadio.blocked(l, d)) continue;
                for (CrewRadio.Line f = l.fallback; f != null; f = f.fallback) {
                    check(CrewRadio.blocked(f, d) || l.name().startsWith("PLAN_"),
                            l + " blocked on " + d + " but falls back to " + f);
                }
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
