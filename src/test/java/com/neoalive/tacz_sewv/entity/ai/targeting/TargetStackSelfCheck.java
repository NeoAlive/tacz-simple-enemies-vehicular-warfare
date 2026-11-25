package com.neoalive.tacz_sewv.entity.ai.targeting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import com.neoalive.tacz_sewv.entity.ai.targeting.StackEntry.Kind;
import com.neoalive.tacz_sewv.entity.ai.targeting.TargetRanker.Candidate;
import com.neoalive.tacz_sewv.util.WorldTargetStack;

/**
 * Self-check for the target priority stack: row grammar, the cleaning rules, migration, the bounded
 * rank weight, the ranking key and the switch rule. Run via {@code ./gradlew selfCheckTargetStack}.
 */
public final class TargetStackSelfCheck {

    private static final double F = 4.0;

    public static void main(String[] args) {
        parsing();
        cleaning();
        exclusionLift();
        migration();
        weight();
        ordering();
        focus();
        workedNumbers();
        switchRule();
        System.out.println("target stack self-check: OK");
    }

    private static void parsing() {
        check(StackEntry.parse("minecraft:zombie").kind() == Kind.TYPE, "entity id");
        check(StackEntry.parse("Minecraft:Zombie").text().equals("minecraft:zombie"), "lower-cased");
        check(StackEntry.parse("@spore").kind() == Kind.MOD, "mod id");
        check(StackEntry.parse("cat:monster").kind() == Kind.CATEGORY, "category");
        check(StackEntry.parse("#minecraft:raiders").kind() == Kind.TAG, "tag");
        check(StackEntry.parse("group:hard_armour").group() == StackEntry.Group.HARD_ARMOUR, "group");
        for (String junk : new String[] {"", " ", "zombie", "a:b:c", "@", "cat:", "#", "group:nope", "bad id:x",
                "minecraft:", ":zombie"}) {
            check(StackEntry.parse(junk) == null, "junk rejected: '" + junk + "'");
        }
        for (String row : WorldTargetStack.DEFAULT_PRIORITY) {
            check(StackEntry.parse(row) != null, "default row parses: " + row);
        }
    }

    private static void cleaning() {
        WorldTargetStack.Stack s = WorldTargetStack.clean(
                List.of("cat:monster", "cat:monster", "junk", "group:troops", "superbwarfare:t_90a"),
                List.of("cat:creature", "cat:monster", "group:players", "group:heavy_creature", "superbwarfare:t_90a",
                        "minecraft:cow", "group:hard_armour"),
                id -> id.startsWith("superbwarfare:"));
        check(s.priority().equals(List.of("cat:monster", "group:troops", "superbwarfare:t_90a")),
                "priority de-duplicated, junk dropped: " + s.priority());
        check(s.excluded().equals(List.of("cat:creature", "group:heavy_creature", "minecraft:cow")),
                "excluded drops rows in priority, troops/players/armour groups and vehicles: " + s.excluded());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) many.add("minecraft:m" + i);
        check(WorldTargetStack.clean(many, List.of(), id -> false).priority().size() == WorldTargetStack.MAX_ROWS,
                "capped");
    }

    private static void exclusionLift() {
        int id = StackEntry.parse("skyfront:drone").specificity();
        int mod = StackEntry.parse("@skyfront").specificity();
        int cat = StackEntry.parse("cat:misc").specificity();
        int tag = StackEntry.parse("#minecraft:raiders").specificity();
        check(!StackEntry.stillExcluded(cat, id), "an id in priority lifts a category exclusion");
        check(!StackEntry.stillExcluded(cat, mod), "a mod in priority lifts a category exclusion");
        check(StackEntry.stillExcluded(id, cat), "an excluded id beats a priority category");
        check(StackEntry.stillExcluded(tag, mod), "an excluded tag beats a priority mod");
        check(StackEntry.stillExcluded(cat, cat), "a tie stays excluded");
        check(StackEntry.stillExcluded(cat, 0), "nothing in priority stays excluded");
        check(!StackEntry.stillExcluded(0, 0), "not excluded at all");
    }

    private static void migration() {
        WorldTargetStack.Stack s = WorldTargetStack.fromLegacy(new LinkedHashSet<>(List.of("creature", "ambient")));
        check(s.excluded().equals(List.of("cat:creature", "cat:ambient")), "old exclusions become cat: rows");
        check(s.priority().equals(WorldTargetStack.DEFAULT_PRIORITY), "priority gets the defaults");
        List<String> d = WorldTargetStack.DEFAULT_PRIORITY;
        check(d.indexOf("@xx_tripod_invasion") < d.indexOf("cat:organoid")
                && d.indexOf("cat:organoid") < d.indexOf("cat:infected")
                && d.indexOf("cat:infected") < d.indexOf("cat:monster"), "old soft-enemy tier order kept");
    }

    private static void weight() {
        for (int n = 1; n <= 256; n++) {
            check(TargetRanker.weight(0, n, F) == 1.0, "top row is x1");
            check(Math.abs(TargetRanker.weight(n, n, F) - F) < 1e-9, "unlisted is xF");
            for (int r = 1; r <= n; r++) {
                check(TargetRanker.weight(r, n, F) > TargetRanker.weight(r - 1, n, F), "strictly rising");
            }
        }
        check(TargetRanker.weight(7, 14, 1.0) == 1.0, "F = 1 is pure nearest");
        check(TargetRanker.weight(99, 14, F) == F, "bounded past the end");
    }

    private static void ordering() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 2000; trial++) {
            List<Candidate<Integer>> list = new ArrayList<>();
            int n = 1 + rnd.nextInt(12);
            for (int i = 0; i < 3 + rnd.nextInt(8); i++) {
                list.add(new Candidate<>(i, i, rnd.nextDouble() * 120, rnd.nextInt(n + 1), rnd.nextInt(5) > 0,
                        i == 0 && rnd.nextBoolean()));
            }
            TargetRanker.sort(list, n, F);
            for (int i = 1; i < list.size(); i++) {
                Candidate<Integer> a = list.get(i - 1);
                Candidate<Integer> b = list.get(i);
                check(a.hurtable() || !b.hurtable(), "hurtable before unhurtable");
                if (a.hurtable() == b.hurtable()) check(a.eff() <= b.eff(), "ascending effective distance");
            }
        }
        List<Candidate<String>> list = new ArrayList<>(List.of(
                new Candidate<>("tank", 1, 5, 0, false, false),
                new Candidate<>("rifleman", 2, 90, 13, true, false)));
        TargetRanker.sort(list, 14, F);
        check(list.get(0).ref.equals("rifleman"), "an unhurtable tank loses to any hurtable target");
    }

    private static void focus() {
        List<Candidate<String>> list = new ArrayList<>(List.of(
                new Candidate<>("near", 1, 50, 0, true, false),
                new Candidate<>("focus", 2, 80, 0, true, true)));
        TargetRanker.sort(list, 4, F);
        check(list.get(0).ref.equals("focus"), "focus wins inside its row");
        list = new ArrayList<>(List.of(
                new Candidate<>("top", 1, 30, 0, true, false),
                new Candidate<>("focus", 2, 20, 1, true, true),
                new Candidate<>("peer", 3, 60, 1, true, false)));
        TargetRanker.sort(list, 4, F);
        check(list.get(0).ref.equals("top"), "focus never crosses rows: " + list.get(0).ref);
    }

    private static void workedNumbers() {
        int n = WorldTargetStack.DEFAULT_PRIORITY.size();
        int armour = WorldTargetStack.DEFAULT_PRIORITY.indexOf("group:hard_armour");
        int troops = WorldTargetStack.DEFAULT_PRIORITY.indexOf("group:troops");
        check(first(n, cand("tank", 60, armour), cand("rifleman", 40, troops)).equals("tank"), "tank at 60 over a rifleman at 40");
        check(first(n, cand("tank", 60, armour), cand("rifleman", 25, troops)).equals("rifleman"), "a rifleman at 25 over the tank");
        check(first(n, cand("tank", 90, armour), cand("rifleman", 10, troops)).equals("rifleman"),
                "the close rifleman over a tank at 90");
    }

    private static void switchRule() {
        Candidate<String> current = cand("cur", 100, 0);
        Candidate<String> near = cand("near", 85, 0);
        Candidate<String> far = cand("far", 75, 0);
        TargetRanker.sort(new ArrayList<>(List.of(current, near, far)), 4, F);
        check(!TargetRanker.shouldSwitch(near, current), "15% better holds the current target");
        check(TargetRanker.shouldSwitch(far, current), "25% better switches");
        check(!TargetRanker.shouldSwitch(current, current), "never onto itself");
        Candidate<String> stuck = new Candidate<>("stuck", 9, 10, 0, false, false);
        Candidate<String> hurt = new Candidate<>("hurt", 10, 200, 4, true, false);
        TargetRanker.sort(new ArrayList<>(List.of(stuck, hurt)), 4, F);
        check(TargetRanker.shouldSwitch(hurt, stuck), "off a target it cannot hurt");
        check(!TargetRanker.shouldSwitch(stuck, hurt), "never onto one it cannot hurt");
    }

    private static Candidate<String> cand(String name, double dist, int rank) {
        return new Candidate<>(name, name.hashCode(), dist, rank, true, false);
    }

    @SafeVarargs
    private static String first(int n, Candidate<String>... cs) {
        List<Candidate<String>> list = new ArrayList<>(List.of(cs));
        TargetRanker.sort(list, n, F);
        return list.get(0).ref;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("TargetStackSelfCheck failed: " + what);
    }
}
