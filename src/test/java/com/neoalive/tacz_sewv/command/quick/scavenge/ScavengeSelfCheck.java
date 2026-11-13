package com.neoalive.tacz_sewv.command.quick.scavenge;

import com.neoalive.tacz_sewv.item.PlaneRole;

/** {@code ./gradlew selfCheckScavenge}. Pure rules only, no world. */
public final class ScavengeSelfCheck {

    private ScavengeSelfCheck() {}

    public static void main(String[] args) {
        // Priority order: ammo, then emplacements, then utilities, then done.
        check(ScavengeStage.values()[0] == ScavengeStage.AMMO, "ammo first");
        check(ScavengeRules.next(ScavengeStage.AMMO) == ScavengeStage.EMPLACEMENT, "emplacements second");
        check(ScavengeRules.next(ScavengeStage.EMPLACEMENT) == ScavengeStage.UTILITY, "utilities third");
        check(ScavengeRules.next(ScavengeStage.UTILITY) == ScavengeStage.ARMOR, "armor fourth");
        check(ScavengeRules.next(ScavengeStage.ARMOR) == null, "armor last");

        // Caps count what the unit already carries.
        check(ScavengeRules.remaining(4, 0) == 4, "empty pockets take the cap");
        check(ScavengeRules.remaining(4, 3) == 1, "carried lowers the cap");
        check(ScavengeRules.remaining(4, 9) == 0, "over the cap takes nothing");
        check(ScavengeRules.remaining(0, 0) == 0, "a zero cap takes nothing");

        // Full pockets: no empty slot and no stack that can grow.
        check(ScavengeRules.pocketsFull(new int[] {64, 1, 16}, new int[] {64, 1, 16}), "all maxed is full");
        check(!ScavengeRules.pocketsFull(new int[] {64, 0, 16}, new int[] {64, 1, 16}), "an empty slot is room");
        check(!ScavengeRules.pocketsFull(new int[] {64, 1, 15}, new int[] {64, 1, 16}), "a short stack is room");
        check(ScavengeRules.pocketsFull(new int[0], new int[0]), "no pockets is full");

        // Availability: a unit plans only what nobody else of the run has reserved.
        check(ScavengeRules.planned(60, 100, 0) == 60, "wants less than there is");
        check(ScavengeRules.planned(60, 100, 60) == 40, "takes what is left");
        check(ScavengeRules.planned(60, 100, 100) == 0, "nothing left means no walk");
        check(ScavengeRules.planned(0, 100, 0) == 0, "not wanted means no walk");

        // Armor swaps only for a piece no worse in either and better in one.
        check(ScavengeRules.dominates(8, 300, 6, 300), "more protection, same durability");
        check(ScavengeRules.dominates(6, 400, 6, 300), "same protection, more durability");
        check(!ScavengeRules.dominates(8, 200, 6, 300), "more protection but worn out");
        check(!ScavengeRules.dominates(6, 300, 6, 300), "identical is no swap");

        // Airplane Mode truth table.
        check(PlaneRole.AUTO.allows(true) && PlaneRole.AUTO.allows(false), "auto takes everything");
        check(!PlaneRole.CAS.allows(true) && PlaneRole.CAS.allows(false), "cas never takes planes");
        check(PlaneRole.FIGHTER.allows(true) && !PlaneRole.FIGHTER.allows(false), "fighter takes only planes");
        check(PlaneRole.byOrdinal(-1) == PlaneRole.AUTO && PlaneRole.byOrdinal(99) == PlaneRole.AUTO,
                "bad ordinal reads as auto");
        check(PlaneRole.AUTO.next() == PlaneRole.CAS && PlaneRole.FIGHTER.next() == PlaneRole.AUTO, "cycle");
        check(PlaneRole.AUTO.previous() == PlaneRole.FIGHTER, "cycle back");

        System.out.println("ScavengeSelfCheck: OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
