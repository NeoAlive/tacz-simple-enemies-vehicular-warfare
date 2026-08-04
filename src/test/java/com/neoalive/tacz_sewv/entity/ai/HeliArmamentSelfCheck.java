package com.neoalive.tacz_sewv.entity.ai;

import java.util.List;

/**
 * Headless check for heli pilot armament classification and ground-weapon pick.
 * Run via {@code ./gradlew selfCheckHeliArmament}.
 */
public final class HeliArmamentSelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        kindTables();
        emptyGroundSet();
        mi28ArmorPrefersDriverMissile();
        softPrefersRocket();
        armorLatchesGuidedWhenRocketsReady();
        aaNeverPicked();
        ah6ArmorPrefersRocketOverCannon();
        ah6SoftPrefersCannon();

        System.out.println("heli armament self-check: OK");
    }

    private static void kindTables() {
        // ah_6 / mi_28 Rocket — ballistic
        assertKind(HeliArmament.Kind.GROUND_USABLE,
                sig("superbwarfare:small_rocket", "superbwarfare:small_rocket", 0, false), true,
                "rocket");
        assert !HeliArmament.isGuidedProjectile("superbwarfare:small_rocket") : "rocket not guided";
        assert HeliArmament.isRocketSignals(
                sig("superbwarfare:small_rocket", "superbwarfare:small_rocket", 0, false))
                : "small_rocket is rocket";

        // DriverMissile after @Missile merge — wire-guide AG
        assertKind(HeliArmament.Kind.GROUND_USABLE,
                sig("superbwarfare:wire_guide_missile", "superbwarfare:medium_anti_ground_missile", 0, false),
                true, "driver missile");
        assert HeliArmament.isGuidedProjectile("superbwarfare:wire_guide_missile") : "wire_guide guided";

        // DriverAAMissile
        assertKind(HeliArmament.Kind.AIR_ONLY,
                sig("superbwarfare:ru_9m336_missile", "superbwarfare:medium_anti_air_missile", 32, true),
                true, "AA seek height");
        // Ammo-only AA fallback (no seek block)
        assertKind(HeliArmament.Kind.AIR_ONLY,
                sig("addon:foo_missile", "addon:anti_air_missile", 0, false), true, "AA ammo fallback");

        assertKind(HeliArmament.Kind.PLACEHOLDER,
                sig("", "", 0, false), false, "placeholder");
    }

    private static void emptyGroundSet() {
        List<HeliArmament.Candidate> onlyAa = List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.AIR_ONLY, false, true));
        assert HeliArmament.pickFromCandidates(onlyAa, true) < 0 : "AA-only seat must not pick";
        assert HeliArmament.pickFromCandidates(List.of(), false) < 0 : "empty list";
    }

    /** Mandatory stock regression: mi_28 seat 0 vs ground armor → DriverMissile, not Rocket. */
    private static void mi28ArmorPrefersDriverMissile() {
        List<HeliArmament.Candidate> mi28 = mi28PilotAllReady();
        int pick = HeliArmament.pickFromCandidates(mi28, true);
        assert pick == 1 : "mi_28 vs armor must pick DriverMissile (slot 1), got " + pick;
    }

    private static void softPrefersRocket() {
        // Pilot seat has rockets but no cannon — soft still lands on Rocket (slot 0).
        int pick = HeliArmament.pickFromCandidates(mi28PilotAllReady(), false);
        assert pick == 0 : "mi_28 vs soft must prefer Rocket (slot 0), got " + pick;
    }

    /**
     * Live regression: DriverMissile reloading / not canShoot while rockets ARE ready
     * must still pick slot 1 — the old firstReady(unguided) fallthrough sprayed rockets at tanks.
     */
    private static void armorLatchesGuidedWhenRocketsReady() {
        List<HeliArmament.Candidate> mi28 = List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.GROUND_USABLE, false, true, true),
                new HeliArmament.Candidate(1, HeliArmament.Kind.GROUND_USABLE, true, false, false),
                new HeliArmament.Candidate(2, HeliArmament.Kind.AIR_ONLY, true, true, false));
        int pick = HeliArmament.pickFromCandidates(mi28, true);
        assert pick == 1 : "armor must latch DriverMissile while reloading, got " + pick;
    }

    private static void aaNeverPicked() {
        // Guided depleted, unguided depleted, only AA ready — still must not pick AA;
        // latch prefers guided slot even when not ready (reload wait), never AIR_ONLY.
        List<HeliArmament.Candidate> mi28 = List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.GROUND_USABLE, false, false, true),
                new HeliArmament.Candidate(1, HeliArmament.Kind.GROUND_USABLE, true, false, false),
                new HeliArmament.Candidate(2, HeliArmament.Kind.AIR_ONLY, true, true, false));
        int pick = HeliArmament.pickFromCandidates(mi28, true);
        assert pick == 1 : "armor latch must be guided AG slot, not AA, got " + pick;
        assert pick != 2 : "must never pick AIR_ONLY";
    }

    /** ah_6 seat 0: Cannon then Rocket — armor must not stick on the cannon. */
    private static void ah6ArmorPrefersRocketOverCannon() {
        List<HeliArmament.Candidate> ah6 = List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.GROUND_USABLE, false, true, false),
                new HeliArmament.Candidate(1, HeliArmament.Kind.GROUND_USABLE, false, true, true));
        int pick = HeliArmament.pickFromCandidates(ah6, true);
        assert pick == 1 : "ah_6 vs armor must pick Rocket (slot 1), got " + pick;
    }

    /** Soft contacts keep the cannon when both are ready. */
    private static void ah6SoftPrefersCannon() {
        List<HeliArmament.Candidate> ah6 = List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.GROUND_USABLE, false, true, false),
                new HeliArmament.Candidate(1, HeliArmament.Kind.GROUND_USABLE, false, true, true));
        int pick = HeliArmament.pickFromCandidates(ah6, false);
        assert pick == 0 : "ah_6 vs soft must prefer Cannon (slot 0), got " + pick;
    }

    private static List<HeliArmament.Candidate> mi28PilotAllReady() {
        // Seat 0 order: Rocket, DriverMissile, DriverAAMissile — matches mi_28.json
        return List.of(
                new HeliArmament.Candidate(0, HeliArmament.Kind.GROUND_USABLE, false, true, true),
                new HeliArmament.Candidate(1, HeliArmament.Kind.GROUND_USABLE, true, true, false),
                new HeliArmament.Candidate(2, HeliArmament.Kind.AIR_ONLY, true, true, false));
    }

    private static HeliArmament.Signals sig(String proj, String ammo, double minH, boolean seek) {
        return new HeliArmament.Signals(proj, ammo, minH, seek);
    }

    private static void assertKind(HeliArmament.Kind expect, HeliArmament.Signals s, boolean real, String label) {
        HeliArmament.Kind got = HeliArmament.classifySignals(s, real);
        assert got == expect : label + ": expected " + expect + ", got " + got;
    }
}
