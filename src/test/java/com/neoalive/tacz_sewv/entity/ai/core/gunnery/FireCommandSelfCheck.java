package com.neoalive.tacz_sewv.entity.ai.core.gunnery;

import java.util.ArrayList;
import java.util.List;

import com.neoalive.tacz_sewv.entity.ai.core.VehicleWeapons;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.FireCommand.Option;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.FireCommand.Pick;
import com.neoalive.tacz_sewv.entity.ai.core.gunnery.Round.Kind;

/**
 * Self-check for the fire command: kinds, target classes, the card filter, battlecarry and the
 * grapeshot cone. Numbers are the FCP 2.0 M1A2 loadout. Run via {@code ./gradlew selfCheckGunnery}.
 */
public final class FireCommandSelfCheck {

    private static final int C = VehicleWeapons.WEAPON_CANNON;
    private static final int M = VehicleWeapons.WEAPON_MG;
    private static final int S = VehicleWeapons.WEAPON_SPECIAL;

    private static final Round AP = round(0, 0, 3, "superbwarfare:large_shell_ap", "superbwarfare:cannon_shell", "AP", C, 774.2, 1, 0, 0, 0.02);
    private static final Round HE = round(0, 1, 3, "superbwarfare:large_shell_he", "superbwarfare:cannon_shell", "HE", C, 19.4, 1, 29, 19.5, 0.02);
    private static final Round GS = round(0, 2, 3, "superbwarfare:large_shell_gs", "superbwarfare:grapeshot", "", C, 18, 36, 0, 0, 5);
    private static final Round COAX = round(2, 0, 1, "@rifleammo", "superbwarfare:projectile", "", M, 8.3, 1, 0, 0, 0.25);
    private static final Round ATGM = round(3, 0, 1, "superbwarfare:medium_anti_ground_missile", "superbwarfare:wire_guide_missile", "", S, 290.3, 1, 14.8, 5, 0);
    private static final Round HEAT = round(0, 3, 4, "addon:large_shell_heat", "superbwarfare:cannon_shell", "HEAT", C, 300, 1, 10, 2, 0.02);
    private static final Round AP2 = round(0, 4, 5, "addon:large_shell_apfsds", "superbwarfare:cannon_shell", "", C, 900, 1, 0, 0, 0.02);

    public static void main(String[] args) {
        kinds();
        classes();
        fcpCard();
        troops();
        hardArmour();
        lightArmour();
        cone();
        idle();
        System.out.println("fire command self-check: OK");
    }

    private static void kinds() {
        check(AP.kind() == Kind.PENETRATOR, "AP shell type is a penetrator");
        check(HE.kind() == Kind.HE, "HE");
        check(GS.kind() == Kind.GRAPESHOT, "grapeshot by projectile");
        check(COAX.kind() == Kind.COAX, "MG role is the coax");
        check(ATGM.kind() == Kind.MISSILE, "special role is a missile");
        check(HEAT.kind() == Kind.HEAT, "HEAT");
        check(AP2.kind() == Kind.PENETRATOR, "apfsds suffix is a penetrator");
        check(Round.kindOf(C, "superbwarfare:medium_anti_ground_missile", "superbwarfare:wire_guide_missile", "", 14.8)
                == Kind.MISSILE, "a gun launched missile on the cannon slot is a missile");
        check(Round.kindOf(C, "superbwarfare:large_shell_wp", "superbwarfare:cannon_shell", "WP", 10) == Kind.OTHER,
                "WP is never on a list");
    }

    private static void classes() {
        check(TargetClass.ofHull(0.8, true) == TargetClass.TROOPS, "MG keeps most of its damage: unarmoured");
        check(TargetClass.ofHull(0.0, true) == TargetClass.HARD_ARMOUR, "MG does nothing: hard armour");
        check(TargetClass.ofHull(0.02, true) == TargetClass.HARD_ARMOUR, "a scratch counts as nothing");
        check(TargetClass.ofHull(0.2, true) == TargetClass.LIGHT_ARMOUR, "MG hurts a little: light armour");
        check(TargetClass.ofHull(0.0, false) == TargetClass.UNSCORED, "nothing hurts it: fallback table");
        check(TargetClass.ofHull(Double.NaN, true) == TargetClass.UNSCORED, "unmeasured: fallback table");
        check(TargetClass.ofLiving(true) == TargetClass.HEAVY_CREATURE, "tough mob");
        check(TargetClass.ofLiving(false) == TargetClass.TROOPS, "soft mob");
        check(FireCommand.listFor(TargetClass.UNSCORED).isEmpty(), "unscored has no list");
    }

    // FCP 2.0 tank rules: All * 0.2, projectile_hit * 1.25, custom_explosion * 2, grapeshot_hit * 0.3,
    // gunfire through the projectile tag, then All - 11.
    private static void fcpCard() {
        List<Round> loadout = List.of(AP, HE, GS, COAX);
        PenetrationCard.Card card = PenetrationCard.build(loadout,
                r -> r.grapeshot() ? raw -> raw * 0.2 * 0.3 - 11 : raw -> raw * 0.2 * 1.25 - 11,
                raw -> raw * 0.2 * 2 - 11, raw -> raw * 0.2 - 11);
        check(card.damage()[0] > 100, "AP gets through");
        check(card.damage()[2] == 0, "grapeshot is off the card");
        check(card.damage()[3] == 0, "the coax is off the card");
        check(TargetClass.ofHull(card.mgFraction(), card.otherRoundHurts()) == TargetClass.HARD_ARMOUR,
                "an FCP tank is hard armour");
        PenetrationCard.Card noMg = PenetrationCard.build(List.of(AP), r -> raw -> raw, raw -> raw, raw -> raw);
        check(noMg.mgFraction() == 1.0, "a seat with no MG is measured with the reference round");
    }

    private static void troops() {
        // Sabot chambered, coax wet: the coax fires and the sabot stays indexed.
        Pick p = decide(TargetClass.TROOPS, opt(AP, true), opt(HE, false), opt(GS, false), opt(COAX, true));
        check(p != null && p.option().round() == COAX && p.step() == 1 && !p.ammoChange(), "coax takes the riflemen");
        // Coax dry, grapeshot inside its cone: reload to grapeshot.
        p = decide(TargetClass.TROOPS, opt(AP, true), opt(HE, false), opt(GS, false), dry(COAX, true));
        check(p != null && p.option().round() == GS && p.step() == 2, "coax dry, close: grapeshot");
        // Coax dry, grapeshot out of its cone: HE.
        p = decide(TargetClass.TROOPS, opt(AP, true), opt(HE, false), gate(GS, false, "out-of-cone"), dry(COAX, true));
        check(p != null && p.option().round() == HE && p.step() == 2, "coax dry, far: HE");
        // HE chambered and suitable: fires without a reload even with the coax dry.
        p = decide(TargetClass.TROOPS, opt(AP, false), opt(HE, true), opt(GS, false), dry(COAX, true));
        check(p != null && p.option().round() == HE && p.step() == 1, "HE already up stays");
        // A ready missile is never fired at troops.
        p = decide(TargetClass.TROOPS, opt(AP, true), dry(COAX, true), opt(ATGM, true));
        check(p == null, "only sabot and a missile left: fallback, never the missile by the list");
    }

    private static void hardArmour() {
        Pick p = decide(TargetClass.HARD_ARMOUR, opt(AP, true), opt(HE, false), opt(COAX, true));
        check(p != null && p.option().round() == AP && p.step() == 1, "loaded AP fires");
        p = decide(TargetClass.HARD_ARMOUR, opt(AP, false), opt(HE, true), opt(COAX, true));
        check(p != null && p.option().round() == AP && p.step() == 2, "HE up against a tank: reload to AP");
        p = decide(TargetClass.HARD_ARMOUR, opt(AP, false), opt(HEAT, true), opt(COAX, true));
        check(p != null && p.option().round() == HEAT && p.step() == 1, "HEAT chambered keeps firing");
        p = decide(TargetClass.HARD_ARMOUR, opt(AP, false, 200), opt(AP2, false, 600), opt(HE, true));
        check(p != null && p.option().round() == AP2, "the better penetrator wins a reload");
        p = decide(TargetClass.HARD_ARMOUR, opt(HE, true), opt(ATGM, true));
        check(p != null && p.option().round() == ATGM && p.step() == 1, "lined-up missile against armour");
        p = decide(TargetClass.HARD_ARMOUR, opt(HE, true), gate(ATGM, true, "not-ready"));
        check(p == null, "a missile 9 degrees off is not a candidate");
    }

    private static void lightArmour() {
        Pick p = decide(TargetClass.LIGHT_ARMOUR, opt(AP, false), opt(HE, true), opt(COAX, true), opt(ATGM, true));
        check(p != null && p.option().round() == HE, "HE against light armour, not the missile or coax");
        p = decide(TargetClass.LIGHT_ARMOUR, opt(AP, true), dry(HE, false), opt(COAX, true), opt(ATGM, true));
        check(p != null && p.option().round() == ATGM && p.step() == 3, "cannon has nothing that works: missile");
        p = decide(TargetClass.LIGHT_ARMOUR, opt(AP, true), dry(HE, false), opt(COAX, true));
        check(p != null && p.option().round() == COAX && p.step() == 3, "then the coax");
        p = decide(TargetClass.HEAVY_CREATURE, opt(AP, false), opt(HE, false), opt(COAX, true));
        check(p != null && p.option().round() == HE && p.step() == 2,
                "a heavy creature gets HE, the coax only when the cannon is dry");
    }

    private static void cone() {
        check(FireCommand.inCone(5, 5, 0.6), "grapeshot covers a body at 5 blocks");
        check(!FireCommand.inCone(30, 5, 0.6), "not at 30");
        check(FireCommand.inCone(5, 0, 0.6), "no spread data: the 5 degree default");
    }

    private static void idle() {
        check(SeatWeaponPicker.idleKind(1.0) == Kind.PENETRATOR, "armour-minded indexes a penetrator");
        check(SeatWeaponPicker.idleKind(-1.0) == Kind.HE, "infantry-minded indexes HE");
        check(SeatWeaponPicker.idleKind(0.0) == null, "neutral leaves the round alone");
    }

    private static Pick decide(TargetClass cls, Option... options) {
        return FireCommand.decide(cls, new ArrayList<>(List.of(options)));
    }

    private static Option opt(Round r, boolean chambered) {
        return new Option(r, chambered, true, 1, null);
    }

    private static Option opt(Round r, boolean chambered, double card) {
        return new Option(r, chambered, true, card, null);
    }

    private static Option dry(Round r, boolean chambered) {
        return new Option(r, chambered, false, 1, "dry");
    }

    private static Option gate(Round r, boolean chambered, String why) {
        return new Option(r, chambered, false, 1, why);
    }

    private static Round round(int slot, int idx, int count, String ammo, String proj, String shell, int role,
                               double dmg, int pellets, double blast, double radius, double spread) {
        return new Round(slot, idx, count, ammo, proj, role, Round.kindOf(role, ammo, proj, shell, blast),
                dmg, pellets, blast, radius, spread);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FireCommandSelfCheck failed: " + what);
    }
}
