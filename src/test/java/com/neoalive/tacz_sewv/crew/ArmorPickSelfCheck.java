package com.neoalive.tacz_sewv.crew;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * Headless check of {@link ArmorPick} (run: {@code ./gradlew selfCheckArmor}, needs {@code -ea}).
 * The point of the feature: pieces for one slot are a random draw, not first-listed-wins; known
 * sets lock together when ≥2 pieces are present.
 */
public final class ArmorPickSelfCheck {

    private static final Map<String, EquipmentSlot> SLOTS = Map.ofEntries(
            Map.entry("h1", EquipmentSlot.HEAD), Map.entry("h2", EquipmentSlot.HEAD),
            Map.entry("h3", EquipmentSlot.HEAD),
            Map.entry("c1", EquipmentSlot.CHEST), Map.entry("c2", EquipmentSlot.CHEST),
            Map.entry("l1", EquipmentSlot.LEGS), Map.entry("l2", EquipmentSlot.LEGS),
            Map.entry("b1", EquipmentSlot.FEET),
            Map.entry("set_h", EquipmentSlot.HEAD), Map.entry("set_c", EquipmentSlot.CHEST),
            Map.entry("set_l", EquipmentSlot.LEGS),
            Map.entry("other_h", EquipmentSlot.HEAD));

    private static final List<List<String>> TEST_SETS = List.of(
            List.of("set_h", "set_c", "set_l"),
            List.of("c2", "l2") // incomplete set: chest+legs, no helmet
    );

    private static final Predicate<EquipmentSlot> ANY = s -> true;

    public static void main(String[] args) {
        singleCandidatePerSlotIsAlwaysIssued();
        sameSlotIsARandomDrawNotFirstWins();
        everyCandidateIsReachableAndRoughlyEven();
        duplicateIdDoublesItsOdds();
        unwantedAndUnknownAreSkipped();
        emptyListIssuesNothing();
        matchingSetLocksTogether();
        incompleteSetFillsMissingSlotAtRandom();
        noSetFallsBackToIndependentDraw();
        System.out.println("ArmorPickSelfCheck OK");
    }

    private static Map<EquipmentSlot, String> run(List<String> ids, Predicate<EquipmentSlot> wanted, Random r) {
        return ArmorPick.choose(ids, SLOTS::get, wanted, r::nextInt, List.of());
    }

    private static Map<EquipmentSlot, String> runSets(List<String> ids, Random r) {
        return ArmorPick.choose(ids, SLOTS::get, ANY, r::nextInt, TEST_SETS);
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

    private static void matchingSetLocksTogether() {
        Random r = new Random(42);
        for (int i = 0; i < 50; i++) {
            Map<EquipmentSlot, String> out = runSets(
                    List.of("set_h", "set_c", "set_l", "h1", "c1", "l1"), r);
            check(out.get(EquipmentSlot.HEAD).equals("set_h")
                            && out.get(EquipmentSlot.CHEST).equals("set_c")
                            && out.get(EquipmentSlot.LEGS).equals("set_l"),
                    "a full matching set must lock all three slots together");
        }
    }

    private static void incompleteSetFillsMissingSlotAtRandom() {
        // c2+l2 is a set with no helmet — chest/legs lock, helmet rolls between other_h and h1.
        boolean sawOther = false;
        boolean sawH1 = false;
        Random r = new Random(99);
        for (int i = 0; i < 200; i++) {
            Map<EquipmentSlot, String> out = runSets(List.of("c2", "l2", "other_h", "h1"), r);
            check(out.get(EquipmentSlot.CHEST).equals("c2") && out.get(EquipmentSlot.LEGS).equals("l2"),
                    "incomplete set must still lock the pieces it has");
            String h = out.get(EquipmentSlot.HEAD);
            if ("other_h".equals(h)) sawOther = true;
            if ("h1".equals(h)) sawH1 = true;
        }
        check(sawOther && sawH1, "missing set slot must roll among the remaining helmets");
    }

    private static void noSetFallsBackToIndependentDraw() {
        // Only one piece of each test set present — no set eligible; chest should still reach c1.
        boolean sawC1 = false;
        Random r = new Random(13);
        for (int i = 0; i < 200 && !sawC1; i++) {
            Map<EquipmentSlot, String> out = runSets(List.of("set_c", "c1", "h1"), r);
            sawC1 = "c1".equals(out.get(EquipmentSlot.CHEST));
        }
        check(sawC1, "with no eligible set, slots fall back to an independent draw");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
