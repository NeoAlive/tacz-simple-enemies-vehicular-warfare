package com.neoalive.tacz_sewv.entity.ai.core;

import com.atsuishio.superbwarfare.data.gun.Ammo;

/**
 * Self-check for score-based weapon-slot classification. Run via {@code ./gradlew selfCheck}.
 *
 * <p>Plain {@code main} + {@code assert}, same harness shape as {@code UtilityWeightsSelfCheck}.
 * Placeholder / {@code isRealWeapon} boundary needs a live {@code VehicleEntity} — documented in
 * {@code docs/softcompat_corpus.md}, not asserted here.
 */
public final class VehicleWeaponsClassifySelfCheck {

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or this checks nothing");

        stockRegressions();
        corpusAnchors();
        deadHeatPrefersSpecial();
        mgWordBoundary();

        System.out.println("vehicle weapons classify self-check: OK");
    }

    private static void stockRegressions() {
        // Real shell type → CANNON
        assertRole(VehicleWeapons.WEAPON_CANNON,
                classify("", "ap", "", "", null), "shell type AP");

        // SPECIAL needle in projectile
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("", "", "superbwarfare:wire_guide_missile", "", null), "missile projectile");

        // Rifle ammo class → MG
        assertRole(VehicleWeapons.WEAPON_MG,
                classify("", "", "", "", Ammo.RIFLE), "rifle ammo class");

        // Default bullet projectile alone → MG
        assertRole(VehicleWeapons.WEAPON_MG,
                classify("", "", "superbwarfare:projectile", "", null), "default projectile");

        // Nothing → UNCLASSIFIED
        assertRole(VehicleWeapons.UNCLASSIFIED,
                classify("", "", "", "", null), "empty signals");

        // Cannon-named / shell projectile, no SPECIAL
        assertRole(VehicleWeapons.WEAPON_CANNON,
                classify("weapon.example.cannon", "", "superbwarfare:large_shell", "", null),
                "cannon name + shell proj");
    }

    private static void corpusAnchors() {
        // mcsp:tos_1a — launcher + rocket beat shell in mlrs_shells
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("weapon.mcsp.tos_1a_launcher", "", "superbwarfare:medium_rocket",
                        "mcsp:mlrs_shells", null),
                "tos_1a");

        // fcp:spg9 — rocket@ammo vs shell/cannon@proj → exact tie → SPECIAL
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("weapon.fcp.spg9", "", "superbwarfare:small_cannon_shell",
                        "superbwarfare:rpg_rocket_standard", null),
                "spg9");

        // fcp:grad — rocket > ShellType HE (1.0)
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("weapon.fcp.grad", "he", "superbwarfare:medium_rocket",
                        "superbwarfare:small_rocket", null),
                "grad");

        // vehicle mortar — mortar haystacks block CANNON text; ShellType alone loses
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("weapon.fcp.stryker_mortar", "he", "superbwarfare:mortar_shell",
                        "superbwarfare:mortar_shell", null),
                "mortar");

        // FCP proper-noun ATGM
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                classify("weapon.fcp.malyutka", "", "fcp:malyutka", "", null),
                "malyutka");
    }

    private static void deadHeatPrefersSpecial() {
        // Synthetic equal SPECIAL and CANNON — TIE_PRIORITY, not ascending role index
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                VehicleWeapons.pickWinnerFromScores(2.0, 0.0, 2.0),
                "dead heat SPECIAL==CANNON");
        // CANNON must not win a three-way by being index 0
        assertRole(VehicleWeapons.WEAPON_SPECIAL,
                VehicleWeapons.pickWinnerFromScores(2.0, 2.0, 2.0),
                "three-way tie");
    }

    private static void mgWordBoundary() {
        // Bare "mg" as a token → MG; substring inside an unrelated word must not
        assertRole(VehicleWeapons.WEAPON_MG,
                classify("seat mg left", "", "", "", null), "mg word");
        assertRole(VehicleWeapons.UNCLASSIFIED,
                classify("armguard", "", "", "", null), "mg inside armguard");
    }

    private static int classify(String name, String shell, String projectile, String ammoId, Ammo ammo) {
        return VehicleWeapons.classifyFromSignals(name, shell, projectile, ammoId, ammo);
    }

    private static void assertRole(int expected, int actual, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + " got " + actual);
        }
    }

    private VehicleWeaponsClassifySelfCheck() {}
}
