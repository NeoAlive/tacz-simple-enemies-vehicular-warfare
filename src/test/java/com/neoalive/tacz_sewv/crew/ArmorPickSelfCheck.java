package com.neoalive.tacz_sewv.crew;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * Headless check of {@link ArmorPick} (run: {@code ./gradlew selfCheckArmor}, needs {@code -ea}).
 * The point of the feature: pieces for one slot are a random draw, not first-listed-wins.
 */
public final class ArmorPickSelfCheck {

    private static final Map<String, EquipmentSlot> SLOTS = Map.of(
            "h1", EquipmentSlot.HEAD, "h2", EquipmentSlot.HEAD, "h3", EquipmentSlot.HEAD,
            "c1", EquipmentSlot.CHEST, "l1", EquipmentSlot.LEGS, "b1", EquipmentSlot.FEET);

    private static final Predicate<EquipmentSlot> ANY = s -> true;

    public static void main(String[] args) {
        singleCandidatePerSlotIsAlwaysIssued();
        sameSlotIsARandomDrawNotFirstWins();
        everyCandidateIsReachableAndRoughlyEven();
        duplicateIdDoublesItsOdds();
        unwantedAndUnknownAreSkipped();
        emptyListIssuesNothing();
        System.out.println("ArmorPickSelfCheck OK");
    }

    private static Map<EquipmentSlot, String> run(List<String> ids, Predicate<EquipmentSlot> wanted, Random r) {
        return ArmorPick.choose(ids, SLOTS::get, wanted, r::nextInt);
    }

    private static void singleCandidatePerSlotIsAlwaysIssued() {
        Map<EquipmentSlot, String> out = run(List.of("h1", "c1", "l1", "b1"), ANY, new Random(1));
        check(out.size() == 4 && out.get(EquipmentSlot.HEAD).equals("h1") && out.get(EquipmentSlot.FEET).equals("b1"),
                "one piece per slot behaves exactly as before");
    }

    private static void sameSlotIsARandomDrawNotFirstWins() {
        boolean sawLater = false;
        Random r = new Random(7);
        for (int i = 0; i < 200 && !sawLater; i++) {
            String h = run(List.of("h1", "h2", "h3"), ANY, r).get(EquipmentSlot.HEAD);
            sawLater = !h.equals("h1");
        }
        check(sawLater, "a later helmet must be reachable: the list is no longer first-listed-wins");
    }

    private static void everyCandidateIsReachableAndRoughlyEven() {
        int[] seen = new int[3];
        Random r = new Random(11);
        int n = 3000;
        for (int i = 0; i < n; i++) {
            String h = run(List.of("h1", "h2", "h3"), ANY, r).get(EquipmentSlot.HEAD);
            seen[h.charAt(1) - '1']++;
        }
        for (int c : seen) {
            check(c > n / 3 * 0.85 && c < n / 3 * 1.15, "three helmets should be about 1/3 each, got " + c + "/" + n);
        }
    }

    private static void duplicateIdDoublesItsOdds() {
        int h1 = 0;
        Random r = new Random(3);
        int n = 3000;
        for (int i = 0; i < n; i++) {
            if (run(List.of("h1", "h1", "h2"), ANY, r).get(EquipmentSlot.HEAD).equals("h1")) h1++;
        }
        check(h1 > n * 2 / 3 * 0.9 && h1 < n * 2 / 3 * 1.1, "an id listed twice should win about 2/3 of draws, got " + h1);
    }

    private static void unwantedAndUnknownAreSkipped() {
        Map<EquipmentSlot, String> helmetOnly = run(List.of("c1", "h1", "h2", "nope"), s -> s == EquipmentSlot.HEAD, new Random(5));
        check(helmetOnly.size() == 1 && helmetOnly.containsKey(EquipmentSlot.HEAD), "a support unit takes the HEAD slot only");
        Map<EquipmentSlot, String> filled = run(List.of("h1", "c1"), s -> s != EquipmentSlot.HEAD, new Random(5));
        check(filled.size() == 1 && filled.containsKey(EquipmentSlot.CHEST), "an already-filled slot is left alone");
        check(run(List.of("nope", "alsonope"), ANY, new Random(5)).isEmpty(), "ids that are not armor items issue nothing");
    }

    private static void emptyListIssuesNothing() {
        check(run(List.of(), ANY, new Random(1)).isEmpty(), "an empty list issues nothing");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
