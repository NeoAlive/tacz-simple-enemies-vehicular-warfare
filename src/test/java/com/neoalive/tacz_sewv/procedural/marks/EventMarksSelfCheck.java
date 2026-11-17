package com.neoalive.tacz_sewv.procedural.marks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Pins {@link MarkRules} and {@link MarkDeck}. */
public final class EventMarksSelfCheck {

    private EventMarksSelfCheck() {}

    public static void main(String[] args) {
        band();
        footprints();
        creep();
        deck();
        System.out.println("EventMarksSelfCheck OK");
    }

    private static void band() {
        double[] b = MarkRules.electionBand(112);
        check(b[0] == 224 && b[1] == 336, "band at 112 is 224-336");
        check(b[0] > 112, "marks are elected past the simulated edge");
    }

    private static void footprints() {
        int g = MarkRules.GRID;
        int n = g * g;
        double spacing = 27.0;
        int[] flat = fill(n, 70);
        check(MarkRules.score(MarkRules.Profile.VEHICLE, flat, flat, g, spacing) == 0.0, "flat dry land scores 0 for vehicles");

        int[] gentle = ramp(g, 70, 10); // 10 blocks per 27: grade 0.37
        check(MarkRules.score(MarkRules.Profile.VEHICLE, gentle, gentle, g, spacing) < MarkRules.REJECT, "a 0.37 grade is drivable");
        int[] steep = ramp(g, 70, 17); // measured p25 step around a hilly spawn
        check(MarkRules.score(MarkRules.Profile.VEHICLE, steep, steep, g, spacing) == MarkRules.REJECT, "a 0.63 grade is too steep for hulls");
        check(MarkRules.score(MarkRules.Profile.INFANTRY, steep, steep, g, spacing) < MarkRules.REJECT, "riflemen take a 0.63 grade");
        check(MarkRules.score(MarkRules.Profile.VEHICLE, flat, flat, g, spacing)
                < MarkRules.score(MarkRules.Profile.VEHICLE, gentle, gentle, g, spacing), "flatter scores lower");

        int[] surface = fill(n, 70);
        int[] floor = fill(n, 70);
        surface[0] = 70;
        floor[0] = 65;
        check(MarkRules.score(MarkRules.Profile.VEHICLE, surface, floor, g, spacing) == MarkRules.REJECT, "any water rules out vehicles");
        check(MarkRules.score(MarkRules.Profile.INFANTRY, surface, floor, g, spacing) < MarkRules.REJECT, "infantry tolerates a pond");

        int[] ocean = fill(n, 63);
        int[] bed = fill(n, 50);
        check(MarkRules.score(MarkRules.Profile.WATER, ocean, bed, g, spacing) < MarkRules.REJECT, "deep ocean fits ships");
        check(MarkRules.score(MarkRules.Profile.WATER, flat, flat, g, spacing) == MarkRules.REJECT, "dry land does not fit ships");
        int[] sheet = fill(n, 62);
        check(MarkRules.score(MarkRules.Profile.WATER, ocean, sheet, g, spacing) == MarkRules.REJECT, "a one block sheet is not navigable");

        int[] superflat = fill(n, -60);
        check(MarkRules.score(MarkRules.Profile.VEHICLE, superflat, superflat, g, spacing) == 0.0, "superflat ground below sea level is dry");
        check(MarkRules.score(MarkRules.Profile.ANY, ocean, bed, g, spacing) == 0.0, "aircraft take anything");
        check(MarkRules.score(MarkRules.Profile.VEHICLE, new int[3], new int[3], g, spacing) == MarkRules.REJECT, "a short sample is rejected");
    }

    /** Rises {@code step} blocks per column. */
    private static int[] ramp(int g, int base, int step) {
        int[] a = new int[g * g];
        for (int r = 0; r < g; r++) for (int c = 0; c < g; c++) a[r * g + c] = base + c * step;
        return a;
    }

    private static void creep() {
        double[] p = MarkRules.creep(300, 0, 0, 0, 112);
        check(Math.abs(p[0] - (300 - MarkRules.CREEP_STEP)) < 1e-9 && Math.abs(p[1]) < 1e-9, "creeps one step toward the player");
        double stop = 112 * MarkRules.CREEP_STOP_SHARE;
        double[] q = MarkRules.creep(stop + 0.5, 0, 0, 0, 112);
        check(Math.abs(q[0] - stop) < 1e-9, "never creeps past the stop line");
        double[] r = MarkRules.creep(stop - 5, 0, 0, 0, 112);
        check(r[0] == stop - 5, "a mark inside the stop line stays put");
    }

    private static void deck() {
        Random random = new Random(42);
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 5; i++) players.add(new UUID(0, i));
        MarkDeck deck = new MarkDeck();

        // Every player is dealt once before anyone is dealt twice.
        for (int round = 0; round < 20; round++) {
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < players.size(); i++) {
                UUID who = deck.next(players, u -> true, random);
                check(who != null && seen.add(who), "round " + round + " deals each player once");
            }
        }

        // Over many rounds the shuffle is roughly even per position.
        Map<UUID, Integer> first = new HashMap<>();
        MarkDeck fresh = new MarkDeck();
        for (int i = 0; i < 5000; i++) {
            UUID who = fresh.next(players, u -> true, random);
            first.merge(who, 1, Integer::sum);
        }
        for (UUID u : players) {
            int n = first.getOrDefault(u, 0);
            check(n == 1000, "each player dealt exactly 1000 of 5000, got " + n);
        }

        // A player on cooldown is passed over and nobody else is starved.
        UUID cooling = players.get(2);
        MarkDeck skip = new MarkDeck();
        for (int i = 0; i < 40; i++) {
            UUID who = skip.next(players, u -> !u.equals(cooling), random);
            check(who != null && !who.equals(cooling), "cooling player is never dealt");
        }
        check(skip.next(players, u -> false, random) == null, "nobody eligible deals nobody");

        // Roster changes reshuffle without losing anyone.
        List<UUID> two = players.subList(0, 2);
        Set<UUID> got = new HashSet<>();
        got.add(deck.next(two, u -> true, random));
        got.add(deck.next(two, u -> true, random));
        check(got.equals(new HashSet<>(two)), "new roster deals both players");
    }

    private static int[] fill(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
