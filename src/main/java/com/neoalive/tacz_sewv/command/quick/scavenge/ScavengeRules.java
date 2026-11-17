package com.neoalive.tacz_sewv.command.quick.scavenge;

/** The pure arithmetic behind Scavenge, pinned by {@code ScavengeSelfCheck}. */
public final class ScavengeRules {

    private ScavengeRules() {}

    /** How many more a unit may take under {@code cap} when it already carries {@code carried}. */
    public static int remaining(int cap, int carried) {
        return Math.max(0, cap - Math.max(0, carried));
    }

    /**
     * Pockets are full when no slot is empty and no stack can grow. A unit in that state can take
     * nothing, so it is refused before it walks anywhere.
     */
    public static boolean pocketsFull(int[] counts, int[] maxes) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0 || counts[i] < maxes[i]) return false;
        }
        return true;
    }

    /** How much of an item a unit plans to take: what it wants, of what nobody else has reserved. */
    public static int planned(int wanted, int available, int reserved) {
        return Math.max(0, Math.min(wanted, available - Math.max(0, reserved)));
    }

    /** Armor swap rule: no worse in protection or durability left, and better in at least one. */
    public static boolean dominates(int defense, int durability, int wornDefense, int wornDurability) {
        return defense >= wornDefense && durability >= wornDurability
                && (defense > wornDefense || durability > wornDurability);
    }

    /** The stage after {@code stage}, or null after the last. */
    public static ScavengeStage next(ScavengeStage stage) {
        ScavengeStage[] all = ScavengeStage.values();
        int i = stage.ordinal() + 1;
        return i < all.length ? all[i] : null;
    }
}
