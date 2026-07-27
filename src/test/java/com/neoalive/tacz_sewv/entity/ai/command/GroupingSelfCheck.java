package com.neoalive.tacz_sewv.entity.ai.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless self-check for sticky battle-group clustering. Run via {@code ./gradlew selfCheck}.
 *
 * <p>What it protects: join/leave hysteresis and the diameter cap. If either evaporates in the
 * implementation, groups flicker every scan or smear across the map — and Stage 2+ resets with them.
 */
public final class GroupingSelfCheck {

    private static final int FACTION = 0;
    /**
     * join=48, leave=64, diameter=128 → maxRadius=64.
     * Leave band fits inside the diameter ball; mid-band (56) is join-no / leave-yes.
     */
    private static final GroupParams PARAMS = new GroupParams(48.0, 64.0, 128.0, 2);

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        twoDistantClustersAreTwoGroups();
        chainDoesNotCollapsePastDiameter();
        joinLeaveHysteresis();
        belowMinSizeDissolves();
        loneUnitFormsNoGroup();
        newArrivalJoinsNearestExisting();

        System.out.println("command grouping self-check: OK");
    }

    /** Two clusters 200 blocks apart → two groups. */
    private static void twoDistantClustersAreTwoGroups() {
        AtomicInteger ids = new AtomicInteger(1);
        List<UnitPos> units = List.of(
                u(10, 0, 0), u(11, 20, 0),
                u(20, 200, 0), u(21, 220, 0));
        List<AssignedGroup> groups = Grouping.groupAssignments(units, List.of(), PARAMS, ids::getAndIncrement);
        assertEq(2, groups.size(), "two distant clusters");
        assertDistinctGroups(groups, Set.of(10, 11), Set.of(20, 21));
    }

    /**
     * A chain A–B–C–D spaced so pairwise neighbors are within join radius but the span exceeds
     * max diameter — must NOT become one group (no single-linkage smear).
     */
    private static void chainDoesNotCollapsePastDiameter() {
        // Spacing 40: join=48 so neighbors can link stepwise, but a 0..120 span (diameter 120)
        // cannot fit in a ball of radius 64.
        Map<Integer, UnitPos> pos = new HashMap<>();
        List<UnitPos> units = new ArrayList<>();
        for (UnitPos u : List.of(u(1, 0, 0), u(2, 40, 0), u(3, 80, 0), u(4, 120, 0))) {
            units.add(u);
            pos.put(u.id, u);
        }
        AtomicInteger ids = new AtomicInteger(1);
        List<AssignedGroup> groups = Grouping.groupAssignments(units, List.of(), PARAMS, ids::getAndIncrement);
        assertTrue(groups.size() >= 2, "chain must split, got " + groups.size());
        for (AssignedGroup g : groups) {
            assertTrue(g.memberIds.length < 4, "chain collapsed into one group: " + Arrays.toString(g.memberIds));
            assertWithinDiameter(g, pos, PARAMS.maxRadius());
        }
    }

    /**
     * Join at R_join; leave only past R_leave. A unit at the mid-band does not join as a stranger
     * and does stay as a member — the hysteresis band that plans love to evaporate.
     */
    private static void joinLeaveHysteresis() {
        double mid = (PARAMS.joinRadius + PARAMS.leaveRadius) / 2.0; // 56
        assertTrue(mid > PARAMS.joinRadius && mid < PARAMS.leaveRadius, "mid-band setup");
        assertTrue(mid <= PARAMS.maxRadius(), "mid-band must fit inside diameter ball");

        AtomicInteger ids = new AtomicInteger(100);
        ExistingGroup existing = new ExistingGroup(1, FACTION, new int[]{1, 2}, 10.0, 0.0);
        UnitPos a = u(1, 0, 0);
        UnitPos b = u(2, 20, 0);
        // Stranger at mid-band from centroid 10 → x = 10 + mid.
        UnitPos stranger = u(3, 10.0 + mid, 0);

        // Stranger must NOT join (dist to centroid = mid > joinRadius).
        List<AssignedGroup> noJoin = Grouping.groupAssignments(
                List.of(a, b, stranger), List.of(existing), PARAMS, ids::getAndIncrement);
        AssignedGroup core = findGroupContaining(noJoin, 1);
        assertTrue(core != null && !contains(core, 3),
                "stranger at mid-band must not join (got " + describe(noJoin) + ")");

        // Same position, but unit 3 was already a member — must STAY (mid < leaveRadius).
        ExistingGroup withThree = new ExistingGroup(1, FACTION, new int[]{1, 2, 3}, 10.0, 0.0);
        List<AssignedGroup> stays = Grouping.groupAssignments(
                List.of(a, b, stranger), List.of(withThree), PARAMS, ids::getAndIncrement);
        AssignedGroup kept = findGroupContaining(stays, 1);
        assertTrue(kept != null && contains(kept, 3),
                "member at mid-band must stay (leave hysteresis); got " + describe(stays));

        // Drift past leaveRadius → leaves.
        UnitPos drifted = u(3, 10.0 + PARAMS.leaveRadius + 1.0, 0);
        List<AssignedGroup> left = Grouping.groupAssignments(
                List.of(a, b, drifted), List.of(withThree), PARAMS, ids::getAndIncrement);
        AssignedGroup after = findGroupContaining(left, 1);
        assertTrue(after != null && !contains(after, 3),
                "member past leaveRadius must leave; got " + describe(left));

        // Fresh unit inside joinRadius → joins.
        UnitPos close = u(4, 10.0 + PARAMS.joinRadius - 1.0, 0);
        List<AssignedGroup> joined = Grouping.groupAssignments(
                List.of(a, b, close), List.of(existing), PARAMS, ids::getAndIncrement);
        AssignedGroup withNew = findGroupContaining(joined, 1);
        assertTrue(withNew != null && contains(withNew, 4),
                "unit inside joinRadius must join; got " + describe(joined));
    }

    /** A group that drops below minSize dissolves; members become ungrouped. */
    private static void belowMinSizeDissolves() {
        AtomicInteger ids = new AtomicInteger(1);
        ExistingGroup pair = new ExistingGroup(1, FACTION, new int[]{1, 2}, 10.0, 0.0);
        List<AssignedGroup> groups = Grouping.groupAssignments(
                List.of(u(1, 0, 0)), List.of(pair), PARAMS, ids::getAndIncrement);
        assertEq(0, groups.size(), "undersized group must dissolve");
    }

    /** A lone unit never forms a group. */
    private static void loneUnitFormsNoGroup() {
        AtomicInteger ids = new AtomicInteger(1);
        List<AssignedGroup> groups = Grouping.groupAssignments(
                List.of(u(1, 0, 0)), List.of(), PARAMS, ids::getAndIncrement);
        assertEq(0, groups.size(), "lone unit forms no group");
    }

    /** New arrival joins the nearest existing group rather than spawning a duplicate beside it. */
    private static void newArrivalJoinsNearestExisting() {
        AtomicInteger ids = new AtomicInteger(50);
        ExistingGroup east = new ExistingGroup(1, FACTION, new int[]{1, 2}, 10.0, 0.0);
        ExistingGroup west = new ExistingGroup(2, FACTION, new int[]{10, 11}, 200.0, 0.0);
        UnitPos arrival = u(3, 25.0, 0); // nearer east centroid
        List<UnitPos> units = List.of(
                u(1, 0, 0), u(2, 20, 0),
                u(10, 190, 0), u(11, 210, 0),
                arrival);
        List<AssignedGroup> groups = Grouping.groupAssignments(
                units, List.of(east, west), PARAMS, ids::getAndIncrement);
        AssignedGroup joined = findGroupContaining(groups, 3);
        assertTrue(joined != null, "arrival must be grouped");
        assertEq(1, joined.groupId, "arrival must join nearest existing (east), not a new group");
        assertTrue(contains(joined, 1) && contains(joined, 2), "east core preserved");
        assertEq(2, groups.size(), "no duplicate group beside nearest");
    }

    // ---- helpers ----

    private static UnitPos u(int id, double x, double z) {
        return new UnitPos(id, FACTION, x, z);
    }

    private static void assertDistinctGroups(List<AssignedGroup> groups, Set<Integer> a, Set<Integer> b) {
        Set<Integer> foundA = null;
        Set<Integer> foundB = null;
        for (AssignedGroup g : groups) {
            Set<Integer> members = toSet(g.memberIds);
            if (members.equals(a)) foundA = members;
            if (members.equals(b)) foundB = members;
        }
        assertTrue(foundA != null && foundB != null,
                "expected clusters " + a + " and " + b + " got " + describe(groups));
    }

    private static void assertWithinDiameter(AssignedGroup g, Map<Integer, UnitPos> pos, double maxRadius) {
        for (int id : g.memberIds) {
            UnitPos u = pos.get(id);
            assertTrue(u != null, "missing position for " + id);
            double d = Grouping.dist(u, g.centroidX, g.centroidZ);
            assertTrue(d <= maxRadius + 1e-6,
                    "member " + id + " at dist " + d + " exceeds maxRadius " + maxRadius);
        }
    }

    private static AssignedGroup findGroupContaining(List<AssignedGroup> groups, int unitId) {
        for (AssignedGroup g : groups) {
            if (contains(g, unitId)) return g;
        }
        return null;
    }

    private static boolean contains(AssignedGroup g, int unitId) {
        for (int id : g.memberIds) {
            if (id == unitId) return true;
        }
        return false;
    }

    private static Set<Integer> toSet(int[] ids) {
        Set<Integer> set = new HashSet<>();
        for (int id : ids) set.add(id);
        return set;
    }

    private static String describe(List<AssignedGroup> groups) {
        List<String> parts = new ArrayList<>();
        for (AssignedGroup g : groups) parts.add(g.groupId + ":" + Arrays.toString(g.memberIds));
        return parts.toString();
    }

    private static void assertEq(int expected, int actual, String label) {
        assert expected == actual : label + ": expected " + expected + " got " + actual;
    }

    private static void assertTrue(boolean cond, String message) {
        assert cond : message;
    }
}
